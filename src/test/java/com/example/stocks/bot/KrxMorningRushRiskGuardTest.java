package com.example.stocks.bot;

import com.example.stocks.db.*;
import com.example.stocks.exchange.ExchangeAdapter;
import com.example.stocks.kis.KisPublicClient;
import com.example.stocks.kis.KisWebSocketClient;
import com.example.stocks.market.CandleService;
import com.example.stocks.market.TickerService;
import com.example.stocks.trade.LiveOrderService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P2 (V41 2026-05-06): 리스크 가드 단위/통합 테스트.
 *
 * 회귀 시나리오:
 *   - 04-21 -4.46% 손실 → DAILY_LOSS_HALT 가 -2.0% 시점에 차단했어야 함
 *   - 05-06 322310 매수 시도 3회 거절 (예수금 부족) → reserveKrw 가 미리 capital 에서 차감했어야 함
 *
 * 검증:
 *   - getTodayRealizedPnlKrw: KRX_MORNING_RUSH SELL only, 자정 기준 합산
 *   - DAILY_LOSS_HALT decision: pnl% <= dailyLossLimitPct 시점에 매수 차단
 *   - reserveKrw 차감: remainingBudget = capital - invested - reserveKrw
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class KrxMorningRushRiskGuardTest {

    @Mock private KrxMorningRushConfigRepository configRepo;
    @Mock private BotConfigRepository botConfigRepo;
    @Mock private PositionRepository positionRepo;
    @Mock private TradeRepository tradeLogRepo;
    @Mock private LiveOrderService liveOrders;
    @Mock private TickerService tickerService;
    @Mock private CandleService candleService;
    @Mock private KisWebSocketClient kisWs;
    @Mock private TransactionTemplate txTemplate;
    @Mock private ExchangeAdapter exchangeAdapter;
    @Mock private KisPublicClient kisPublic;
    @Mock private KrxOvertimeRankLogRepository overtimeRankRepo;

    private KrxMorningRushService service;
    private KrxMorningRushConfigEntity cfg;

    @BeforeEach
    public void setUp() {
        service = new KrxMorningRushService(
                configRepo, botConfigRepo, positionRepo, tradeLogRepo,
                liveOrders, tickerService, candleService, kisWs, txTemplate,
                exchangeAdapter, kisPublic, overtimeRankRepo, new KrxSharedTradeThrottle()
        );
        cfg = new KrxMorningRushConfigEntity();
        cfg.setMode("LIVE");
        cfg.setMaxPositions(2);
        cfg.setDailyLossLimitPct(BigDecimal.valueOf(-2.0));
        cfg.setReserveKrw(30000);

        when(configRepo.loadOrCreate()).thenReturn(cfg);
        when(positionRepo.findAll()).thenReturn(Collections.emptyList());

        BotConfigEntity bot = new BotConfigEntity();
        bot.setCapitalKrw(BigDecimal.valueOf(500_000L));
        when(botConfigRepo.findAll()).thenReturn(Collections.singletonList(bot));

        when(tradeLogRepo.findByTsEpochMsBetween(anyLong(), anyLong()))
                .thenReturn(Collections.<TradeEntity>emptyList());

        // LIVE 모드 — API 설정됐다고 가정 (그렇지 않으면 API_KEY_MISSING 으로 조기 return)
        when(liveOrders.isConfigured()).thenReturn(true);
    }

    private TradeEntity sellTrade(String symbol, double pnlKrw, String pattern) {
        TradeEntity t = new TradeEntity();
        t.setSymbol(symbol);
        t.setAction("SELL");
        t.setPatternType(pattern);
        t.setTsEpochMs(System.currentTimeMillis());
        t.setPnlKrw(pnlKrw);
        return t;
    }

    private TradeEntity buyTrade(String symbol, double pnlKrw) {
        TradeEntity t = new TradeEntity();
        t.setSymbol(symbol);
        t.setAction("BUY");
        t.setPatternType("KRX_MORNING_RUSH");
        t.setTsEpochMs(System.currentTimeMillis());
        t.setPnlKrw(pnlKrw);
        return t;
    }

    // ============================================================
    // P2-B: getTodayRealizedPnlKrw 정확성
    // ============================================================

    @Test
    @DisplayName("[P2-B] 당일 SELL pnl 합계 — KRX_MORNING_RUSH 만, BUY 무시")
    public void todayPnl_sumsOnlyMrSells() {
        when(tradeLogRepo.findByTsEpochMsBetween(anyLong(), anyLong())).thenReturn(Arrays.asList(
                sellTrade("A", -1000, "KRX_MORNING_RUSH"),
                sellTrade("B",  +500, "KRX_MORNING_RUSH"),
                sellTrade("C", -2000, "KRX_MORNING_RUSH"),
                sellTrade("D", +100000, "OTHER_STRATEGY"),  // 다른 전략 무시
                buyTrade("E", -999999)                      // BUY pnl 무시 (보통 0이지만 강제 테스트)
        ));

        double pnl = service.getTodayRealizedPnlKrw();

        assertEquals(-2500.0, pnl, 0.01, "MR SELL 합계: -1000 + 500 - 2000 = -2500");
    }

    @Test
    @DisplayName("[P2-B] 당일 거래 없음 → PnL = 0")
    public void todayPnl_noTrades_zero() {
        // 기본 mock = empty list
        assertEquals(0.0, service.getTodayRealizedPnlKrw());
    }

    @Test
    @DisplayName("[P2-B] tradeLogRepo 예외 발생 → 0 반환 (안전 fallback)")
    public void todayPnl_repoException_safe() {
        when(tradeLogRepo.findByTsEpochMsBetween(anyLong(), anyLong()))
                .thenThrow(new RuntimeException("DB error"));

        assertEquals(0.0, service.getTodayRealizedPnlKrw());
    }

    // ============================================================
    // P2-A: V41 컬럼 default
    // ============================================================

    @Test
    @DisplayName("[P2-A] dailyLossLimitPct default = -2.0")
    public void cfgEntity_dailyLossLimitPct_default() {
        KrxMorningRushConfigEntity c = new KrxMorningRushConfigEntity();
        assertEquals(0, c.getDailyLossLimitPct().compareTo(BigDecimal.valueOf(-2.0)));
    }

    @Test
    @DisplayName("[P2-A] reserveKrw default = 30000")
    public void cfgEntity_reserveKrw_default() {
        KrxMorningRushConfigEntity c = new KrxMorningRushConfigEntity();
        assertEquals(30000L, c.getReserveKrw());
    }

    @Test
    @DisplayName("[P2-A] dailyLossLimitPct setter null 안전")
    public void cfgEntity_dailyLossLimitPct_nullSafe() {
        KrxMorningRushConfigEntity c = new KrxMorningRushConfigEntity();
        c.setDailyLossLimitPct(null);
        assertEquals(0, c.getDailyLossLimitPct().compareTo(BigDecimal.valueOf(-2.0)));
    }

    @Test
    @DisplayName("[P2-A] reserveKrw setter 음수 → 0")
    public void cfgEntity_reserveKrw_negativeClampedToZero() {
        KrxMorningRushConfigEntity c = new KrxMorningRushConfigEntity();
        c.setReserveKrw(-1000);
        assertEquals(0L, c.getReserveKrw());
    }

    @Test
    @DisplayName("[P2-C] maxPositions setter clamp [1,10]")
    public void cfgEntity_maxPositions_clamp() {
        KrxMorningRushConfigEntity c = new KrxMorningRushConfigEntity();
        c.setMaxPositions(0);
        assertEquals(1, c.getMaxPositions());
        c.setMaxPositions(100);
        assertEquals(10, c.getMaxPositions());
        c.setMaxPositions(2);
        assertEquals(2, c.getMaxPositions());
    }

    // ============================================================
    // P2-B: scanForEntry — DAILY_LOSS_HALT decision (reflection 호출)
    // ============================================================

    @Test
    @DisplayName("[P2-B] 04-21 회귀: 당일 누적 손실 -10000 KRW (capital 500K, -2.0% = -10000) → 정확히 boundary 차단")
    public void scanForEntry_dailyLossAtThreshold_blocks() throws Exception {
        // capital 500K, dailyLossLimitPct -2.0% → 한도 = -10000 KRW
        when(tradeLogRepo.findByTsEpochMsBetween(anyLong(), anyLong())).thenReturn(Arrays.asList(
                sellTrade("A", -3000, "KRX_MORNING_RUSH"),
                sellTrade("B", -7000, "KRX_MORNING_RUSH")
        ));

        // prevCloseMap 비우면 scanForEntry 가 NO_RANGE 로 빠지므로 시뮬용으로 1건 추가
        @SuppressWarnings("unchecked")
        Map<String, Double> pcMap = (Map<String, Double>) getField(service, "prevCloseMap");
        pcMap.put("FAKE", 1000.0);

        invokeScanForEntry();

        List<Map<String, Object>> decisions = service.getRecentDecisions(50);
        boolean blocked = decisions.stream().anyMatch(d ->
                "DAILY_LOSS_HALT".equals(d.get("reasonCode")));
        assertTrue(blocked, "당일 손실 -2.0% 도달 시 DAILY_LOSS_HALT 기록되어야 함");
    }

    @Test
    @DisplayName("[P2-B] 손실이 한도 미만 (-1.5%) → 매수 진행 허용")
    public void scanForEntry_dailyLossBelowThreshold_allows() throws Exception {
        // capital 500K, -1.5% = -7500 KRW (한도 -10000 안 도달)
        when(tradeLogRepo.findByTsEpochMsBetween(anyLong(), anyLong())).thenReturn(Arrays.asList(
                sellTrade("A", -7500, "KRX_MORNING_RUSH")
        ));
        @SuppressWarnings("unchecked")
        Map<String, Double> pcMap = (Map<String, Double>) getField(service, "prevCloseMap");
        pcMap.put("FAKE", 1000.0);

        invokeScanForEntry();

        List<Map<String, Object>> decisions = service.getRecentDecisions(50);
        boolean halted = decisions.stream().anyMatch(d ->
                "DAILY_LOSS_HALT".equals(d.get("reasonCode")));
        assertFalse(halted, "한도 미달이면 DAILY_LOSS_HALT 기록되면 안 됨");
    }

    @Test
    @DisplayName("[P2-B] dailyLossLimitPct 가 0 또는 양수면 비활성 (안전)")
    public void scanForEntry_disabledLimit_neverBlocks() throws Exception {
        cfg.setDailyLossLimitPct(BigDecimal.ZERO);
        when(tradeLogRepo.findByTsEpochMsBetween(anyLong(), anyLong())).thenReturn(Arrays.asList(
                sellTrade("A", -50000, "KRX_MORNING_RUSH")  // 큰 손실
        ));
        @SuppressWarnings("unchecked")
        Map<String, Double> pcMap = (Map<String, Double>) getField(service, "prevCloseMap");
        pcMap.put("FAKE", 1000.0);

        invokeScanForEntry();

        List<Map<String, Object>> decisions = service.getRecentDecisions(50);
        boolean halted = decisions.stream().anyMatch(d ->
                "DAILY_LOSS_HALT".equals(d.get("reasonCode")));
        assertFalse(halted, "limit=0 일 때 DAILY_LOSS_HALT 비활성");
    }

    // ============================================================
    // 헬퍼
    // ============================================================

    private void invokeScanForEntry() throws Exception {
        Method m = KrxMorningRushService.class.getDeclaredMethod(
                "scanForEntry", KrxMorningRushConfigEntity.class);
        m.setAccessible(true);
        m.invoke(service, cfg);
    }

    private Object getField(Object obj, String name) throws Exception {
        java.lang.reflect.Field f = KrxMorningRushService.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }
}
