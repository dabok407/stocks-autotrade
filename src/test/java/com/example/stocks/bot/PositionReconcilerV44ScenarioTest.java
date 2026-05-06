package com.example.stocks.bot;

import com.example.stocks.db.KrxMorningRushConfigEntity;
import com.example.stocks.db.KrxMorningRushConfigRepository;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V44 (2026-05-06): stuck 자동 청산 활성화 시나리오 테스트.
 *
 * V44 마이그레이션 SQL 의 실제 운영 시뮬:
 *   stuck_cleanup_whitelist = '073540,184230,047040'
 *   auto_cleanup_stuck_enabled = TRUE
 *
 * 보장:
 *   - 화이트리스트 3종목 매도 시도
 *   - 005880 대한해운 (사용자 본인) 절대 매도 안 함
 *   - 005930/012330/036030 (사용자 본인 — trade_log BUY 이력 없음) 절대 매도 안 함
 */
class PositionReconcilerV44ScenarioTest {

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

        // V44 운영 설정 — 실제 마이그레이션 SQL 과 동일
        cfg = new KrxMorningRushConfigEntity();
        cfg.setMode("LIVE");
        cfg.setAutoCleanupStuckEnabled(true);
        cfg.setStuckCleanupWhitelist("073540,184230,047040");

        when(configRepo.loadOrCreate()).thenReturn(cfg);
        when(liveOrders.isConfigured()).thenReturn(true);
        when(kisClient.isConfigured()).thenReturn(true);

        reconciler = new PositionReconciler(kisClient, positionRepo, tradeRepo, liveOrders, configRepo);
        reconciler.setEnabled(true);

        // 사용자 실제 잔고 정확히 시뮬 (KIS API 결과 기반)
        when(kisClient.getDomesticBalance()).thenReturn(Arrays.asList(
                kisHolding("005880", 24, 3057.21),  // 사용자 본인 (봇 BUY 있지만 SELL 도 있음)
                kisHolding("005930", 12, 81125),    // 사용자 본인 (봇 BUY 이력 X)
                kisHolding("012330", 1, 318000),    // 사용자 본인
                kisHolding("036030", 8, 11800),     // 사용자 본인
                kisHolding("047040", 1, 33150),     // 봇 stuck (V44 화이트리스트)
                kisHolding("073540", 10, 6200),     // 봇 stuck (V44 화이트리스트)
                kisHolding("184230", 46, 1074)));   // 봇 stuck (V44 화이트리스트)

        when(positionRepo.findAll()).thenReturn(new ArrayList<>());

        // 봇 BUY 이력 (trade_log)
        // 005880 — 봇이 매수 했었음 (04-16) — 하지만 SELL 도 같이 있음 → V42 hasBotBuyHistory 는 true 반환
        when(tradeRepo.findBySymbol("005880")).thenReturn(Arrays.asList(botBuy("005880")));
        when(tradeRepo.findBySymbol("073540")).thenReturn(Arrays.asList(botBuy("073540")));
        when(tradeRepo.findBySymbol("184230")).thenReturn(Arrays.asList(botBuy("184230")));
        when(tradeRepo.findBySymbol("047040")).thenReturn(Arrays.asList(botBuy("047040")));
        // 005930, 012330, 036030 은 default empty list
    }

    private KisAccount kisHolding(String s, int qty, double avg) {
        KisAccount a = new KisAccount();
        a.setSymbol(s);
        a.setName(s);
        a.setQty(qty);
        a.setAvgPrice(avg);
        a.setCurrency("KRW");
        return a;
    }

    private TradeEntity botBuy(String symbol) {
        TradeEntity t = new TradeEntity();
        t.setSymbol(symbol);
        t.setAction("BUY");
        t.setPatternType("KRX_MORNING_RUSH");
        t.setTsEpochMs(System.currentTimeMillis() - 86400_000L);
        return t;
    }

    private LiveOrderService.LiveOrderResult filled(int qty, double price) {
        return new LiveOrderService.LiveOrderResult("ID", "ORD-" + qty, "done", qty, price);
    }

    // ============================================================
    // V44 운영 시나리오
    // ============================================================

    @Test
    @DisplayName("[V44] 분류 단계: 4건 STUCK_BOT_POSITION + 3건 ORPHAN_BROKER")
    void classification_v44Scenario() {
        PositionReconciler.ReconcileReport r = reconciler.doReconcile();

        // BUY 이력 있는 4건 (V42 알고리즘) — 005880 false positive 포함
        assertEquals(4, r.stuckBotPositions.size());
        assertTrue(r.stuckBotPositions.containsKey("005880"));
        assertTrue(r.stuckBotPositions.containsKey("073540"));
        assertTrue(r.stuckBotPositions.containsKey("184230"));
        assertTrue(r.stuckBotPositions.containsKey("047040"));

        // BUY 이력 없는 3건
        assertEquals(3, r.orphanBroker.size());
        assertTrue(r.orphanBroker.contains("005930"));
        assertTrue(r.orphanBroker.contains("012330"));
        assertTrue(r.orphanBroker.contains("036030"));
    }

    @Test
    @DisplayName("[V44] 청산 단계: 화이트리스트 3종목만 매도, 005880 보호")
    void cleanup_onlyWhitelisted_005880Protected() {
        java.time.LocalTime nowKst = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"));
        boolean inMarket = !nowKst.isBefore(PositionReconciler.MARKET_OPEN)
                && !nowKst.isAfter(PositionReconciler.MARKET_CLOSE);
        if (!inMarket) return;  // 시장 외 시간 — skip

        // 매도 mock 설정
        when(liveOrders.placeSellOrder(eq("073540"), any(MarketType.class), eq(10), eq(0.0), eq("01")))
                .thenReturn(filled(10, 3965));
        when(liveOrders.placeSellOrder(eq("184230"), any(MarketType.class), eq(46), eq(0.0), eq("01")))
                .thenReturn(filled(46, 754));
        when(liveOrders.placeSellOrder(eq("047040"), any(MarketType.class), eq(1), eq(0.0), eq("01")))
                .thenReturn(filled(1, 32250));

        reconciler.reconcile();

        // 화이트리스트 3건 매도 시도
        verify(liveOrders, times(1)).placeSellOrder(eq("073540"), any(), eq(10), eq(0.0), eq("01"));
        verify(liveOrders, times(1)).placeSellOrder(eq("184230"), any(), eq(46), eq(0.0), eq("01"));
        verify(liveOrders, times(1)).placeSellOrder(eq("047040"), any(), eq(1), eq(0.0), eq("01"));

        // 005880 절대 매도 시도 안 함 (false positive 방어)
        verify(liveOrders, never()).placeSellOrder(eq("005880"), any(), anyInt(), anyDouble(), anyString());
        // 사용자 본인 보유분 절대 매도 안 함
        verify(liveOrders, never()).placeSellOrder(eq("005930"), any(), anyInt(), anyDouble(), anyString());
        verify(liveOrders, never()).placeSellOrder(eq("012330"), any(), anyInt(), anyDouble(), anyString());
        verify(liveOrders, never()).placeSellOrder(eq("036030"), any(), anyInt(), anyDouble(), anyString());

        // trade_log SELL 기록 3건
        verify(tradeRepo, times(3)).save(any(TradeEntity.class));
    }

    @Test
    @DisplayName("[V44] 시장 외 시간: 매도 시도 0건, deferred 로그만")
    void cleanup_outsideMarketHours_zeroSellAttempts() {
        java.time.LocalTime nowKst = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"));
        boolean inMarket = !nowKst.isBefore(PositionReconciler.MARKET_OPEN)
                && !nowKst.isAfter(PositionReconciler.MARKET_CLOSE);
        if (inMarket) return;  // 시장 시간 — skip

        reconciler.reconcile();

        // 시장 외에는 화이트리스트 있어도 매도 시도 X
        verify(liveOrders, never()).placeSellOrder(anyString(), any(MarketType.class),
                anyInt(), anyDouble(), anyString());
    }

    @Test
    @DisplayName("[V44] 같은 세션 반복 방지: 두 번째 reconcile 사이클에서 매도 안 함")
    void cleanup_secondCycle_noDuplicateAttempt() {
        java.time.LocalTime nowKst = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"));
        boolean inMarket = !nowKst.isBefore(PositionReconciler.MARKET_OPEN)
                && !nowKst.isAfter(PositionReconciler.MARKET_CLOSE);
        if (!inMarket) return;

        when(liveOrders.placeSellOrder(anyString(), any(MarketType.class), anyInt(), anyDouble(), anyString()))
                .thenReturn(filled(10, 3965));

        reconciler.reconcile();
        reconciler.reconcile();  // 두번째 cycle

        // 각 종목당 최대 1회 매도 시도
        verify(liveOrders, atMost(1)).placeSellOrder(eq("073540"), any(), anyInt(), anyDouble(), anyString());
        verify(liveOrders, atMost(1)).placeSellOrder(eq("184230"), any(), anyInt(), anyDouble(), anyString());
        verify(liveOrders, atMost(1)).placeSellOrder(eq("047040"), any(), anyInt(), anyDouble(), anyString());
    }
}
