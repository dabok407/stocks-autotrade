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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V45 (2026-05-14) 시나리오:
 *   - botNet 기반 STUCK 정밀 분류 (005880 false positive 차단)
 *   - max_value_krw 금액 한도 자동매도 (화이트리스트 없이도)
 *   - QTY_MISMATCH 자동 동기화 (broker > db + bot BUY history)
 *
 * 5/14 운영 사고 회귀:
 *   - 222420 쎄노텍: 봇 BUY 78주 → broker 156주 (분할체결, 봇 DB 78주만 인식)
 *                    SESSION_END 매도 78주 후 잔량 78주 STUCK
 */
class PositionReconcilerV45Test {

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
        cfg.setStuckCleanupWhitelist(""); // 화이트리스트 없이 max_value 만으로
        cfg.setStuckCleanupMaxValueKrw(500_000L);
        cfg.setQtyMismatchAutoSyncEnabled(true);

        when(configRepo.loadOrCreate()).thenReturn(cfg);
        when(liveOrders.isConfigured()).thenReturn(true);
        when(kisClient.isConfigured()).thenReturn(true);

        reconciler = new PositionReconciler(kisClient, positionRepo, tradeRepo, liveOrders, configRepo);
        reconciler.setEnabled(true);
    }

    private KisAccount holding(String s, int qty, double avg) {
        KisAccount a = new KisAccount();
        a.setSymbol(s); a.setName(s); a.setQty(qty); a.setAvgPrice(avg); a.setCurrency("KRW");
        return a;
    }

    private PositionEntity dbPos(String s, int qty, double avg) {
        PositionEntity p = new PositionEntity();
        p.setSymbol(s); p.setQty(qty);
        p.setAvgPrice(BigDecimal.valueOf(avg));
        p.setEntryStrategy("KRX_MORNING_RUSH");
        return p;
    }

    private TradeEntity buy(String s, int qty) {
        TradeEntity t = new TradeEntity();
        t.setSymbol(s); t.setAction("BUY");
        t.setPatternType("KRX_MORNING_RUSH");
        t.setQty(qty);
        t.setTsEpochMs(System.currentTimeMillis() - 86400_000L);
        return t;
    }

    private TradeEntity sell(String s, int qty) {
        TradeEntity t = new TradeEntity();
        t.setSymbol(s); t.setAction("SELL");
        t.setPatternType("KRX_MORNING_RUSH");
        t.setQty(qty);
        t.setTsEpochMs(System.currentTimeMillis() - 86400_000L);
        return t;
    }

    private LiveOrderService.LiveOrderResult filled(int qty, double price) {
        return new LiveOrderService.LiveOrderResult("ID", "ORD", "done", qty, price);
    }

    @Test
    @DisplayName("[V45] 184230 BUY 46 → STUCK 분류, cleanup 시 botBuyTotal 46 = broker 46 → 매도")
    void botBuyTotal_buyOnly_classifiedStuck_andSold() {
        java.time.LocalTime nowKst = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"));
        boolean inMarket = !nowKst.isBefore(PositionReconciler.MARKET_OPEN)
                && !nowKst.isAfter(PositionReconciler.MARKET_CLOSE);

        when(kisClient.getDomesticBalance()).thenReturn(Arrays.asList(holding("184230", 46, 1074)));
        when(positionRepo.findAll()).thenReturn(new ArrayList<>());
        when(tradeRepo.findBySymbol("184230")).thenReturn(Arrays.asList(buy("184230", 46)));

        PositionReconciler.ReconcileReport r = reconciler.doReconcile();
        assertTrue(r.stuckBotPositions.containsKey("184230"));

        if (inMarket) {
            when(liveOrders.placeSellOrder(eq("184230"), any(MarketType.class), eq(46), eq(0.0), eq("01")))
                    .thenReturn(filled(46, 1050));
            reconciler.attemptStuckCleanup(r);
            verify(liveOrders, times(1)).placeSellOrder(eq("184230"), any(), eq(46), eq(0.0), eq("01"));
        }
    }

    @Test
    @DisplayName("[V45] 005880 BUY 24 — broker 24 = botBuyTotal → cleanup 통과, broker 48 (외부추가) → SKIP")
    void botBuyTotal_externalAddition_protected() {
        java.time.LocalTime nowKst = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"));
        boolean inMarket = !nowKst.isBefore(PositionReconciler.MARKET_OPEN)
                && !nowKst.isAfter(PositionReconciler.MARKET_CLOSE);
        if (!inMarket) return;

        // 봇 BUY 24, broker 48 (24주 외부 추가매수) → botBuyTotal < brokerQty → 보호
        when(tradeRepo.findBySymbol("005880")).thenReturn(Arrays.asList(buy("005880", 24)));

        PositionReconciler.ReconcileReport r = new PositionReconciler.ReconcileReport();
        r.stuckBotPositions.put("005880", holding("005880", 48, 3057));
        reconciler.attemptStuckCleanup(r);

        verify(liveOrders, never()).placeSellOrder(eq("005880"), any(MarketType.class),
                anyInt(), anyDouble(), anyString());
        assertFalse(r.cleanupSuccess.contains("005880"));
    }

    @Test
    @DisplayName("[V45] max_value 한도 이하 STUCK → 화이트리스트 없이도 자동 매도")
    void cleanup_underValueLimit_autoSoldWithoutWhitelist() {
        java.time.LocalTime nowKst = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"));
        boolean inMarket = !nowKst.isBefore(PositionReconciler.MARKET_OPEN)
                && !nowKst.isAfter(PositionReconciler.MARKET_CLOSE);
        if (!inMarket) return;

        // 184230: 46주 × 1074 = 49,404원 < 500,000원 → 자동매도
        cfg.setStuckCleanupWhitelist("");
        cfg.setStuckCleanupMaxValueKrw(500_000L);

        when(liveOrders.placeSellOrder(eq("184230"), any(MarketType.class), eq(46), eq(0.0), eq("01")))
                .thenReturn(filled(46, 1050));
        when(tradeRepo.findBySymbol("184230")).thenReturn(Arrays.asList(buy("184230", 46)));

        PositionReconciler.ReconcileReport r = new PositionReconciler.ReconcileReport();
        r.stuckBotPositions.put("184230", holding("184230", 46, 1074));
        reconciler.attemptStuckCleanup(r);

        verify(liveOrders, times(1)).placeSellOrder(eq("184230"), any(), eq(46), eq(0.0), eq("01"));
        assertTrue(r.cleanupSuccess.contains("184230"));
    }

    @Test
    @DisplayName("[V45] max_value 한도 초과 + 화이트리스트 없음 → 매도 안 함 (대형 자산 보호)")
    void cleanup_overValueLimit_noWhitelist_neverSells() {
        java.time.LocalTime nowKst = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"));
        boolean inMarket = !nowKst.isBefore(PositionReconciler.MARKET_OPEN)
                && !nowKst.isAfter(PositionReconciler.MARKET_CLOSE);
        if (!inMarket) return;

        cfg.setStuckCleanupWhitelist("");
        cfg.setStuckCleanupMaxValueKrw(100_000L);  // 한도 10만

        // 1,000주 × 5000 = 5,000,000원 — 한도 초과
        PositionReconciler.ReconcileReport r = new PositionReconciler.ReconcileReport();
        r.stuckBotPositions.put("999999", holding("999999", 1000, 5000));
        reconciler.attemptStuckCleanup(r);

        verify(liveOrders, never()).placeSellOrder(eq("999999"), any(MarketType.class),
                anyInt(), anyDouble(), anyString());
        assertFalse(r.cleanupSuccess.contains("999999"));
    }

    @Test
    @DisplayName("[V45] QTY_MISMATCH 자동 sync: brokerQty 156 > dbQty 78 + bot BUY 이력 → DB 156 으로 동기화")
    void qtyMismatch_autoSync_brokerLarger_synced() {
        when(positionRepo.findById("222420")).thenReturn(Optional.of(dbPos("222420", 78, 1551)));
        when(tradeRepo.findBySymbol("222420")).thenReturn(Arrays.asList(buy("222420", 78)));

        PositionReconciler.ReconcileReport r = new PositionReconciler.ReconcileReport();
        r.qtyMismatches.put("222420", new int[]{78, 156});

        reconciler.attemptQtyMismatchSync(r);

        // DB qty 가 156 으로 업데이트되었는지 검증
        verify(positionRepo, times(1)).save(argThat(p ->
                "222420".equals(p.getSymbol()) && p.getQty() == 156));
    }

    @Test
    @DisplayName("[V45] QTY_MISMATCH sync 안 함: brokerQty < dbQty (broker 가 봇보다 적음)")
    void qtyMismatch_brokerSmaller_noSync() {
        PositionReconciler.ReconcileReport r = new PositionReconciler.ReconcileReport();
        r.qtyMismatches.put("222420", new int[]{156, 78});  // db 156, broker 78

        reconciler.attemptQtyMismatchSync(r);

        verify(positionRepo, never()).save(any(PositionEntity.class));
    }

    @Test
    @DisplayName("[V45] QTY_MISMATCH sync 안 함: 봇 BUY 이력 없음 (사용자 외부 매수)")
    void qtyMismatch_noBotBuyHistory_noSync() {
        when(tradeRepo.findBySymbol(anyString())).thenReturn(new ArrayList<>());

        PositionReconciler.ReconcileReport r = new PositionReconciler.ReconcileReport();
        r.qtyMismatches.put("005930", new int[]{0, 100});  // 봇 없음, broker 100

        reconciler.attemptQtyMismatchSync(r);

        verify(positionRepo, never()).save(any(PositionEntity.class));
    }

    @Test
    @DisplayName("[V45-final] 005880: 봇 BUY 24/SELL 24 (net=0) + broker 24 → cleanup 시점 SKIP")
    void cleanup_botNetZero_externalBuyProtected() {
        java.time.LocalTime nowKst = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"));
        boolean inMarket = !nowKst.isBefore(PositionReconciler.MARKET_OPEN)
                && !nowKst.isAfter(PositionReconciler.MARKET_CLOSE);
        if (!inMarket) return;

        // 005880 사용자 케이스: 봇이 BUY 24/SELL 24 완결, broker 24는 사용자가 별도 시점 매수
        cfg.setStuckCleanupWhitelist("");
        cfg.setStuckCleanupMaxValueKrw(500_000L);
        when(tradeRepo.findBySymbol("005880")).thenReturn(Arrays.asList(
                buy("005880", 24), sell("005880", 24)));  // net=0

        PositionReconciler.ReconcileReport r = new PositionReconciler.ReconcileReport();
        r.stuckBotPositions.put("005880", holding("005880", 24, 3057));
        reconciler.attemptStuckCleanup(r);

        verify(liveOrders, never()).placeSellOrder(eq("005880"), any(MarketType.class),
                anyInt(), anyDouble(), anyString());
        assertFalse(r.cleanupSuccess.contains("005880"));
    }

    @Test
    @DisplayName("[V45-final] 화이트리스트 명시 종목은 net=0 검사 우회 → 매도")
    void cleanup_botNetZero_butWhitelisted_sold() {
        java.time.LocalTime nowKst = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"));
        boolean inMarket = !nowKst.isBefore(PositionReconciler.MARKET_OPEN)
                && !nowKst.isAfter(PositionReconciler.MARKET_CLOSE);
        if (!inMarket) return;

        // net=0 이지만 사용자가 명시 화이트리스트 등록 → 사용자 의도이므로 매도
        cfg.setStuckCleanupWhitelist("005880");
        when(tradeRepo.findBySymbol("005880")).thenReturn(Arrays.asList(
                buy("005880", 24), sell("005880", 24)));  // net=0
        when(liveOrders.placeSellOrder(eq("005880"), any(MarketType.class), eq(24), eq(0.0), eq("01")))
                .thenReturn(filled(24, 2535));

        PositionReconciler.ReconcileReport r = new PositionReconciler.ReconcileReport();
        r.stuckBotPositions.put("005880", holding("005880", 24, 3057));
        reconciler.attemptStuckCleanup(r);

        verify(liveOrders, times(1)).placeSellOrder(eq("005880"), any(), eq(24), eq(0.0), eq("01"));
        assertTrue(r.cleanupSuccess.contains("005880"));
    }

    @Test
    @DisplayName("[V45] QTY_MISMATCH sync 비활성화 (config flag) → 동기화 안 함")
    void qtyMismatch_syncDisabled_noChange() {
        cfg.setQtyMismatchAutoSyncEnabled(false);

        PositionReconciler.ReconcileReport r = new PositionReconciler.ReconcileReport();
        r.qtyMismatches.put("222420", new int[]{78, 156});

        reconciler.attemptQtyMismatchSync(r);

        verify(positionRepo, never()).save(any(PositionEntity.class));
    }
}
