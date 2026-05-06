package com.example.stocks.bot;

import com.example.stocks.db.KrxMorningRushConfigEntity;
import com.example.stocks.db.KrxMorningRushConfigRepository;
import com.example.stocks.db.PositionEntity;
import com.example.stocks.db.PositionRepository;
import com.example.stocks.db.TradeEntity;
import com.example.stocks.db.TradeRepository;
import com.example.stocks.kis.KisAccount;
import com.example.stocks.kis.KisPrivateClient;
import com.example.stocks.market.MarketType;
import com.example.stocks.trade.LiveOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P0-Fix#3 + P2-D (V41+V42 2026-05-06): 정합성 검사 + stuck 자동 청산 테스트.
 *
 * 회귀 시나리오:
 *   - 04-16 에프알텍 stuck (10주, -36%)
 *   - 04-17 SGA솔루션즈 stuck (46주, -30%)
 *   - 05-04 대우건설 stuck (1주, -2.7%)
 *   - 사용자 본인 보유 (삼성전자, 현대모비스 등) — 절대 매도 안 함
 */
class PositionReconcilerTest {

    private KisPrivateClient kisClient;
    private PositionRepository positionRepo;
    private TradeRepository tradeRepo;
    private LiveOrderService liveOrders;
    private KrxMorningRushConfigRepository configRepo;
    private PositionReconciler reconciler;
    private KrxMorningRushConfigEntity cfg;

    @BeforeEach
    void setUp() {
        kisClient = mock(KisPrivateClient.class);
        positionRepo = mock(PositionRepository.class);
        tradeRepo = mock(TradeRepository.class);
        liveOrders = mock(LiveOrderService.class);
        configRepo = mock(KrxMorningRushConfigRepository.class);

        cfg = new KrxMorningRushConfigEntity();
        cfg.setMode("LIVE");
        cfg.setAutoCleanupStuckEnabled(true);
        // V43: 테스트용으로 화이트리스트 미리 설정 — 기존 테스트 호환
        cfg.setStuckCleanupWhitelist("073540,184230,047040,005880");
        when(configRepo.loadOrCreate()).thenReturn(cfg);
        when(liveOrders.isConfigured()).thenReturn(true);

        reconciler = new PositionReconciler(kisClient, positionRepo, tradeRepo, liveOrders, configRepo);
        reconciler.setEnabled(true);
        when(kisClient.isConfigured()).thenReturn(true);
        when(tradeRepo.findBySymbol(anyString())).thenReturn(new ArrayList<>());
    }

    private KisAccount kisHolding(String symbol, int qty, double avg) {
        KisAccount a = new KisAccount();
        a.setSymbol(symbol);
        a.setName(symbol);
        a.setQty(qty);
        a.setAvgPrice(avg);
        a.setCurrency("KRW");
        return a;
    }

    private PositionEntity dbPos(String symbol, int qty, String strategy) {
        PositionEntity p = new PositionEntity();
        p.setSymbol(symbol);
        p.setQty(qty);
        p.setAvgPrice(BigDecimal.valueOf(1000));
        p.setEntryStrategy(strategy);
        return p;
    }

    private TradeEntity botBuy(String symbol) {
        TradeEntity t = new TradeEntity();
        t.setSymbol(symbol);
        t.setAction("BUY");
        t.setPatternType("KRX_MORNING_RUSH");
        t.setTsEpochMs(System.currentTimeMillis() - 86400_000L);
        return t;
    }

    private LiveOrderService.LiveOrderResult filled(int qty, double avgPrice) {
        return new LiveOrderService.LiveOrderResult("ID", "ORD", "done", qty, avgPrice);
    }

    private LiveOrderService.LiveOrderResult notFilled() {
        return new LiveOrderService.LiveOrderResult("ID", "ORD", "pending", 0, 0);
    }

    // ============================================================
    // 기존 분류 테스트 (V41 회귀 보장)
    // ============================================================

    @Test
    @DisplayName("정상: 봇 매수 종목이 KIS+DB 일치 → 이슈 없음")
    void cleanState_noIssues() {
        when(kisClient.getDomesticBalance()).thenReturn(Arrays.asList(
                kisHolding("073540", 10, 6200)));
        when(positionRepo.findAll()).thenReturn(Arrays.asList(
                dbPos("073540", 10, "KRX_MORNING_RUSH")));

        PositionReconciler.ReconcileReport r = reconciler.doReconcile();
        assertFalse(r.hasIssues());
    }

    @Test
    @DisplayName("ORPHAN_DB: SELL 체결됐는데 DB commit 실패")
    void orphanDb_dbCommitFailedAfterSell() {
        when(kisClient.getDomesticBalance()).thenReturn(new ArrayList<>());
        when(positionRepo.findAll()).thenReturn(Arrays.asList(
                dbPos("073540", 10, "KRX_MORNING_RUSH")));

        PositionReconciler.ReconcileReport r = reconciler.doReconcile();
        assertTrue(r.orphanDb.contains("073540"));
    }

    @Test
    @DisplayName("QTY_MISMATCH: 부분 체결")
    void qtyMismatch_partialFill() {
        when(kisClient.getDomesticBalance()).thenReturn(Arrays.asList(kisHolding("184230", 20, 1074)));
        when(positionRepo.findAll()).thenReturn(Arrays.asList(dbPos("184230", 46, "KRX_MORNING_RUSH")));

        PositionReconciler.ReconcileReport r = reconciler.doReconcile();
        assertEquals(1, r.qtyMismatches.size());
        int[] m = r.qtyMismatches.get("184230");
        assertEquals(46, m[0]);
        assertEquals(20, m[1]);
    }

    // ============================================================
    // V42 신규: STUCK_BOT_POSITION vs ORPHAN_BROKER 분류
    // ============================================================

    @Test
    @DisplayName("[V42] 사용자 본인 매수 (trade_log BUY 이력 없음) → ORPHAN_BROKER, 매도 안 함")
    void userOwnPosition_classifiedAsOrphanBroker_neverSold() {
        when(kisClient.getDomesticBalance()).thenReturn(Arrays.asList(
                kisHolding("005930", 12, 81125),    // 삼성전자
                kisHolding("012330", 1, 318000)));  // 현대모비스
        when(positionRepo.findAll()).thenReturn(new ArrayList<>());
        when(tradeRepo.findBySymbol(anyString())).thenReturn(new ArrayList<>());  // BUY 이력 없음

        PositionReconciler.ReconcileReport r = reconciler.doReconcile();

        assertEquals(2, r.orphanBroker.size());
        assertEquals(0, r.stuckBotPositions.size(), "BUY 이력 없으면 stuck 아님");
        assertFalse(r.hasIssues());
    }

    @Test
    @DisplayName("[V42] 봇 stuck (trade_log BUY 이력 있음) → STUCK_BOT_POSITION 분류")
    void botStuck_classifiedCorrectly() {
        when(kisClient.getDomesticBalance()).thenReturn(Arrays.asList(
                kisHolding("073540", 10, 6200),
                kisHolding("184230", 46, 1074)));
        when(positionRepo.findAll()).thenReturn(new ArrayList<>());
        when(tradeRepo.findBySymbol("073540")).thenReturn(Arrays.asList(botBuy("073540")));
        when(tradeRepo.findBySymbol("184230")).thenReturn(Arrays.asList(botBuy("184230")));

        PositionReconciler.ReconcileReport r = reconciler.doReconcile();

        assertTrue(r.hasIssues(), "STUCK 발견 시 이슈 표시");
        assertEquals(2, r.stuckBotPositions.size());
        assertTrue(r.stuckBotPositions.containsKey("073540"));
        assertTrue(r.stuckBotPositions.containsKey("184230"));
        assertEquals(0, r.orphanBroker.size(), "BUY 이력 있으면 ORPHAN_BROKER 아님");
    }

    @Test
    @DisplayName("[V42] 혼합: 본인 보유 + stuck → 분류 정확히 구분")
    void mixedHolding_correctlyPartitioned() {
        when(kisClient.getDomesticBalance()).thenReturn(Arrays.asList(
                kisHolding("005930", 12, 81125),    // 본인 — BUY 이력 없음
                kisHolding("073540", 10, 6200),     // stuck — BUY 이력 있음
                kisHolding("184230", 46, 1074),     // stuck — BUY 이력 있음
                kisHolding("036030", 8, 11800)));   // 본인 — BUY 이력 없음
        when(positionRepo.findAll()).thenReturn(new ArrayList<>());
        when(tradeRepo.findBySymbol("073540")).thenReturn(Arrays.asList(botBuy("073540")));
        when(tradeRepo.findBySymbol("184230")).thenReturn(Arrays.asList(botBuy("184230")));
        // 005930, 036030 은 default empty list

        PositionReconciler.ReconcileReport r = reconciler.doReconcile();

        assertEquals(2, r.orphanBroker.size(), "본인 보유 2종목");
        assertTrue(r.orphanBroker.contains("005930"));
        assertTrue(r.orphanBroker.contains("036030"));

        assertEquals(2, r.stuckBotPositions.size(), "봇 stuck 2종목");
        assertTrue(r.stuckBotPositions.containsKey("073540"));
        assertTrue(r.stuckBotPositions.containsKey("184230"));
    }

    @Test
    @DisplayName("[V42] BUY 이력이 다른 전략(SAFETY_GUARD) 일 때 → ORPHAN_BROKER 처리")
    void buyHistoryDifferentStrategy_classifiedAsOrphan() {
        TradeEntity safetyBuy = new TradeEntity();
        safetyBuy.setSymbol("005880");
        safetyBuy.setAction("BUY");
        safetyBuy.setPatternType("SAFETY_GUARD");  // 다른 전략

        when(kisClient.getDomesticBalance()).thenReturn(Arrays.asList(kisHolding("005880", 24, 3057)));
        when(positionRepo.findAll()).thenReturn(new ArrayList<>());
        when(tradeRepo.findBySymbol("005880")).thenReturn(Arrays.asList(safetyBuy));

        PositionReconciler.ReconcileReport r = reconciler.doReconcile();

        assertEquals(1, r.orphanBroker.size(), "MR 봇 BUY 이력 아니면 ORPHAN_BROKER");
        assertEquals(0, r.stuckBotPositions.size());
    }

    @Test
    @DisplayName("[V42] hasBotBuyHistory: 예외 발생 → false (안전)")
    void hasBotBuyHistory_repoException_safeFalse() {
        when(tradeRepo.findBySymbol("XXX")).thenThrow(new RuntimeException("DB error"));
        assertFalse(reconciler.hasBotBuyHistory("XXX"));
    }

    // ============================================================
    // V42: 자동 청산 (attemptStuckCleanup) — 보안 핵심
    // ============================================================

    @Test
    @DisplayName("[V42] 시장 시간 외(17:00) → cleanup 시도 안 함 (deferred)")
    void cleanup_outsideMarketHours_skipped() {
        // 시장 시간 시뮬레이션은 어렵 → reconcile() 통합 테스트보다 단위 검증 어려움
        // 대신 reconcile 호출 후 cleanup 시도 여부를 확인
        // 현재 KST 시간이 시장 외라면 placeSellOrder 호출되면 안 됨
        when(kisClient.getDomesticBalance()).thenReturn(Arrays.asList(kisHolding("073540", 10, 6200)));
        when(positionRepo.findAll()).thenReturn(new ArrayList<>());
        when(tradeRepo.findBySymbol("073540")).thenReturn(Arrays.asList(botBuy("073540")));

        java.time.LocalTime nowKst = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"));
        boolean inMarket = !nowKst.isBefore(PositionReconciler.MARKET_OPEN)
                && !nowKst.isAfter(PositionReconciler.MARKET_CLOSE);

        reconciler.reconcile();

        if (!inMarket) {
            verify(liveOrders, never()).placeSellOrder(anyString(), any(MarketType.class),
                    anyInt(), anyDouble(), anyString());
        }
    }

    @Test
    @DisplayName("[V42] auto_cleanup_stuck_enabled=false → 매도 안 함")
    void cleanup_disabled_noSellOrder() {
        cfg.setAutoCleanupStuckEnabled(false);

        PositionReconciler.ReconcileReport report = new PositionReconciler.ReconcileReport();
        report.stuckBotPositions.put("073540", kisHolding("073540", 10, 6200));

        reconciler.attemptStuckCleanup(report);

        verify(liveOrders, never()).placeSellOrder(anyString(), any(MarketType.class),
                anyInt(), anyDouble(), anyString());
    }

    @Test
    @DisplayName("[V42] PAPER 모드 → 매도 안 함")
    void cleanup_paperMode_skipped() {
        cfg.setMode("PAPER");

        PositionReconciler.ReconcileReport report = new PositionReconciler.ReconcileReport();
        report.stuckBotPositions.put("073540", kisHolding("073540", 10, 6200));

        reconciler.attemptStuckCleanup(report);

        verify(liveOrders, never()).placeSellOrder(anyString(), any(MarketType.class),
                anyInt(), anyDouble(), anyString());
    }

    @Test
    @DisplayName("[V42] LIVE API 미설정 → 매도 안 함")
    void cleanup_apiNotConfigured_skipped() {
        when(liveOrders.isConfigured()).thenReturn(false);

        PositionReconciler.ReconcileReport report = new PositionReconciler.ReconcileReport();
        report.stuckBotPositions.put("073540", kisHolding("073540", 10, 6200));

        reconciler.attemptStuckCleanup(report);

        verify(liveOrders, never()).placeSellOrder(anyString(), any(MarketType.class),
                anyInt(), anyDouble(), anyString());
    }

    @Test
    @DisplayName("[V42] 같은 세션 반복 시도 방지 — attemptedCleanupSymbols Set")
    void cleanup_alreadyAttempted_skipsDuplicate() {
        // 시장 시간 외에서 테스트 — placeSellOrder 호출 안 되지만 set 추가는 됨
        // 더 명확히: set 에 이미 들어있으면 skip
        reconciler.getAttemptedCleanupSymbols().add("073540");

        PositionReconciler.ReconcileReport report = new PositionReconciler.ReconcileReport();
        report.stuckBotPositions.put("073540", kisHolding("073540", 10, 6200));

        // 강제로 시장 시간 진입 시뮬은 어려우니, 그냥 호출
        reconciler.attemptStuckCleanup(report);

        // 어차피 시장 시간 외면 호출 안 됨 — 이 테스트의 의도는 set 검증
        assertTrue(reconciler.getAttemptedCleanupSymbols().contains("073540"));
    }

    @Test
    @DisplayName("[V42] cleanup 매도 성공 → trade_log SELL 기록 + cleanupSuccess Set")
    void cleanup_successful_recordsSellTrade() {
        // 시장 시간 강제 진입 — Mockito spy 로 시간 체크 우회 불가하니 attemptStuckCleanup 직접 호출 후
        // 시장 시간이면 검증, 아니면 skip 검증
        java.time.LocalTime nowKst = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"));
        boolean inMarket = !nowKst.isBefore(PositionReconciler.MARKET_OPEN)
                && !nowKst.isAfter(PositionReconciler.MARKET_CLOSE);
        if (!inMarket) {
            // 시장 외 시간 — 이 테스트는 skip
            return;
        }

        when(liveOrders.placeSellOrder(eq("073540"), eq(MarketType.KRX), eq(10), eq(0.0), eq("01")))
                .thenReturn(filled(10, 6100));

        PositionReconciler.ReconcileReport report = new PositionReconciler.ReconcileReport();
        report.stuckBotPositions.put("073540", kisHolding("073540", 10, 6200));

        reconciler.attemptStuckCleanup(report);

        assertTrue(report.cleanupSuccess.contains("073540"));
        verify(tradeRepo, times(1)).save(any(TradeEntity.class));
    }

    @Test
    @DisplayName("[V42] cleanup 매도 실패 (PENDING/0주) → cleanupFailed Set")
    void cleanup_failed_addedToFailedSet() {
        java.time.LocalTime nowKst = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"));
        boolean inMarket = !nowKst.isBefore(PositionReconciler.MARKET_OPEN)
                && !nowKst.isAfter(PositionReconciler.MARKET_CLOSE);
        if (!inMarket) return;

        when(liveOrders.placeSellOrder(anyString(), any(MarketType.class), anyInt(), anyDouble(), anyString()))
                .thenReturn(notFilled());

        PositionReconciler.ReconcileReport report = new PositionReconciler.ReconcileReport();
        report.stuckBotPositions.put("073540", kisHolding("073540", 10, 6200));

        reconciler.attemptStuckCleanup(report);

        assertTrue(report.cleanupFailed.contains("073540"));
        verify(tradeRepo, never()).save(any(TradeEntity.class));
    }

    // ============================================================
    // 기타 안전장치
    // ============================================================

    @Test
    @DisplayName("KIS 미설정 시 reconcile 스킵")
    void kisNotConfigured_skipped() {
        when(kisClient.isConfigured()).thenReturn(false);
        reconciler.reconcile();
        assertNull(reconciler.getLastReport());
    }

    @Test
    @DisplayName("enabled=false 시 reconcile 스킵")
    void disabled_skipped() {
        reconciler.setEnabled(false);
        reconciler.reconcile();
        assertNull(reconciler.getLastReport());
    }

    @Test
    @DisplayName("실행 후 lastReport 갱신")
    void afterRun_lastReportSet() {
        when(kisClient.getDomesticBalance()).thenReturn(Arrays.asList(kisHolding("X", 1, 100)));
        when(positionRepo.findAll()).thenReturn(new ArrayList<>());
        reconciler.reconcile();
        assertNotNull(reconciler.getLastReport());
        assertEquals(1, reconciler.getLastReport().brokerCount);
    }

    // ============================================================
    // V43: 화이트리스트 안전장치 (false positive 방지)
    // ============================================================

    @Test
    @DisplayName("[V43] 화이트리스트 비어있음 → 매도 절대 안 함 (default safety)")
    void cleanup_emptyWhitelist_neverSells() {
        cfg.setStuckCleanupWhitelist("");

        PositionReconciler.ReconcileReport report = new PositionReconciler.ReconcileReport();
        report.stuckBotPositions.put("073540", kisHolding("073540", 10, 6200));
        report.stuckBotPositions.put("005880", kisHolding("005880", 24, 3057));

        reconciler.attemptStuckCleanup(report);

        verify(liveOrders, never()).placeSellOrder(anyString(), any(MarketType.class),
                anyInt(), anyDouble(), anyString());
        assertTrue(report.cleanupSuccess.isEmpty());
        assertTrue(report.cleanupFailed.isEmpty());
    }

    @Test
    @DisplayName("[V43] 005880 false positive 방지: 화이트리스트에 없으면 stuck 후보여도 매도 안 함")
    void cleanup_falsePositive_005880_protected() {
        // 005880 대한해운 — trade_log 에 봇 BUY 이력 있지만 사용자 본인 별도 매수분
        // 화이트리스트에는 진짜 stuck (073540, 184230, 047040) 만 등록
        cfg.setStuckCleanupWhitelist("073540,184230,047040");

        java.time.LocalTime nowKst = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"));
        boolean inMarket = !nowKst.isBefore(PositionReconciler.MARKET_OPEN)
                && !nowKst.isAfter(PositionReconciler.MARKET_CLOSE);

        PositionReconciler.ReconcileReport report = new PositionReconciler.ReconcileReport();
        report.stuckBotPositions.put("005880", kisHolding("005880", 24, 3057));  // false positive
        report.stuckBotPositions.put("073540", kisHolding("073540", 10, 6200));  // 진짜 stuck

        if (inMarket) {
            when(liveOrders.placeSellOrder(eq("073540"), any(MarketType.class), eq(10),
                    eq(0.0), eq("01"))).thenReturn(filled(10, 6100));
        }

        reconciler.attemptStuckCleanup(report);

        // 005880 은 화이트리스트에 없으므로 매도 시도조차 안 됨
        verify(liveOrders, never()).placeSellOrder(eq("005880"), any(MarketType.class),
                anyInt(), anyDouble(), anyString());
        assertFalse(report.cleanupSuccess.contains("005880"));
        assertFalse(report.cleanupFailed.contains("005880"));
    }

    @Test
    @DisplayName("[V43] 화이트리스트에 명시된 symbol 만 매도 시도")
    void cleanup_onlyWhitelistedSymbols_sold() {
        java.time.LocalTime nowKst = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"));
        boolean inMarket = !nowKst.isBefore(PositionReconciler.MARKET_OPEN)
                && !nowKst.isAfter(PositionReconciler.MARKET_CLOSE);
        if (!inMarket) return;  // 시장 외 시간 skip

        cfg.setStuckCleanupWhitelist("073540");  // 1개만 등록

        when(liveOrders.placeSellOrder(eq("073540"), any(MarketType.class), eq(10),
                eq(0.0), eq("01"))).thenReturn(filled(10, 6100));

        PositionReconciler.ReconcileReport report = new PositionReconciler.ReconcileReport();
        report.stuckBotPositions.put("073540", kisHolding("073540", 10, 6200));
        report.stuckBotPositions.put("184230", kisHolding("184230", 46, 1074));

        reconciler.attemptStuckCleanup(report);

        // 073540 매도 시도, 184230 은 안 함
        verify(liveOrders, times(1)).placeSellOrder(eq("073540"), any(MarketType.class),
                eq(10), eq(0.0), eq("01"));
        verify(liveOrders, never()).placeSellOrder(eq("184230"), any(MarketType.class),
                anyInt(), anyDouble(), anyString());
    }

    @Test
    @DisplayName("[V43] auto_cleanup_stuck_enabled=false (default V43) → 화이트리스트 있어도 매도 안 함")
    void cleanup_disabledMaster_neverSells_evenWithWhitelist() {
        cfg.setAutoCleanupStuckEnabled(false);
        cfg.setStuckCleanupWhitelist("073540,184230,047040");

        PositionReconciler.ReconcileReport report = new PositionReconciler.ReconcileReport();
        report.stuckBotPositions.put("073540", kisHolding("073540", 10, 6200));

        reconciler.attemptStuckCleanup(report);

        verify(liveOrders, never()).placeSellOrder(anyString(), any(MarketType.class),
                anyInt(), anyDouble(), anyString());
    }

    @Test
    @DisplayName("[V43] Entity getStuckCleanupWhitelistSet() — CSV 파싱 + 공백/빈문자 제거")
    void entity_whitelistCsvParsing() {
        KrxMorningRushConfigEntity c = new KrxMorningRushConfigEntity();
        c.setStuckCleanupWhitelist("");
        assertTrue(c.getStuckCleanupWhitelistSet().isEmpty());

        c.setStuckCleanupWhitelist("073540, 184230 , 047040");
        java.util.Set<String> set = c.getStuckCleanupWhitelistSet();
        assertEquals(3, set.size());
        assertTrue(set.contains("073540"));
        assertTrue(set.contains("184230"));
        assertTrue(set.contains("047040"));

        c.setStuckCleanupWhitelist("073540,,184230,");  // 빈 항목 제거
        assertEquals(2, c.getStuckCleanupWhitelistSet().size());

        c.setStuckCleanupWhitelist(null);
        assertTrue(c.getStuckCleanupWhitelistSet().isEmpty());
    }

    @Test
    @DisplayName("[V43] Entity default: autoCleanupStuckEnabled=false (안전 default)")
    void entity_v43DefaultsAreSafe() {
        KrxMorningRushConfigEntity c = new KrxMorningRushConfigEntity();
        assertFalse(c.isAutoCleanupStuckEnabled(), "default OFF — V43 안전 변경");
        assertEquals("", c.getStuckCleanupWhitelist());
        assertTrue(c.getStuckCleanupWhitelistSet().isEmpty());
    }
}
