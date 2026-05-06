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
import java.time.LocalTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P3 (V41 2026-05-06): 진입 필터 강화 테스트.
 *
 * - P3-A: KOSPI200 (KODEX 200 069500) 시장 regime veto
 * - P3-B: 1분 chase guard
 *
 * 회귀 시나리오:
 *   - 0183V0 (05-06): 09:03 매수 → 30분 TIME_STOP, +0.04% — 시장이 약한 날에 무리 진입
 *   - chase guard: 09:00 분봉 high 11465 → 진입가 11465 (= high × 1.000) — 통과
 *     하지만 만약 09:01 이후 11525 (high × 1.0052) 라면 차단
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class KrxMorningRushP3Test {

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
        when(configRepo.loadOrCreate()).thenReturn(cfg);
        when(positionRepo.findAll()).thenReturn(Collections.emptyList());

        BotConfigEntity bot = new BotConfigEntity();
        bot.setCapitalKrw(BigDecimal.valueOf(500_000L));
        when(botConfigRepo.findAll()).thenReturn(Collections.singletonList(bot));
        when(tradeLogRepo.findByTsEpochMsBetween(anyLong(), anyLong()))
                .thenReturn(Collections.<TradeEntity>emptyList());
        when(liveOrders.isConfigured()).thenReturn(true);
    }

    private Map<String, Object> kospiQuote(double prdyCtrtPct) {
        Map<String, Object> m = new HashMap<>();
        m.put("prdy_ctrt", String.valueOf(prdyCtrtPct));
        m.put("stck_prpr", "30000");
        return m;
    }

    // ============================================================
    // P3-A: KOSPI regime veto
    // ============================================================

    @Test
    @DisplayName("[P3-A] KODEX200 +0.5% → RISK_ON")
    public void regime_riskOn() {
        when(kisPublic.getDomesticCurrentPrice("069500")).thenReturn(kospiQuote(0.5));
        assertEquals(KrxMorningRushService.MarketRegime.RISK_ON, service.updateMarketRegime());
    }

    @Test
    @DisplayName("[P3-A] KODEX200 -0.5% → CAUTIOUS")
    public void regime_cautious() {
        when(kisPublic.getDomesticCurrentPrice("069500")).thenReturn(kospiQuote(-0.5));
        assertEquals(KrxMorningRushService.MarketRegime.CAUTIOUS, service.updateMarketRegime());
    }

    @Test
    @DisplayName("[P3-A] KODEX200 -0.8% → RISK_OFF")
    public void regime_riskOff() {
        when(kisPublic.getDomesticCurrentPrice("069500")).thenReturn(kospiQuote(-0.8));
        assertEquals(KrxMorningRushService.MarketRegime.RISK_OFF, service.updateMarketRegime());
    }

    @Test
    @DisplayName("[P3-A] boundary: 정확히 -0.7% → RISK_OFF")
    public void regime_boundary_minus07() {
        when(kisPublic.getDomesticCurrentPrice("069500")).thenReturn(kospiQuote(-0.7));
        assertEquals(KrxMorningRushService.MarketRegime.RISK_OFF, service.updateMarketRegime());
    }

    @Test
    @DisplayName("[P3-A] boundary: 정확히 -0.3% → CAUTIOUS")
    public void regime_boundary_minus03() {
        when(kisPublic.getDomesticCurrentPrice("069500")).thenReturn(kospiQuote(-0.3));
        assertEquals(KrxMorningRushService.MarketRegime.CAUTIOUS, service.updateMarketRegime());
    }

    @Test
    @DisplayName("[P3-A] API 빈 응답 → 기존 regime 유지 (UNKNOWN)")
    public void regime_emptyResponse_unchanged() {
        when(kisPublic.getDomesticCurrentPrice("069500")).thenReturn(null);
        assertEquals(KrxMorningRushService.MarketRegime.UNKNOWN, service.updateMarketRegime());
    }

    @Test
    @DisplayName("[P3-A] API 예외 → 기존 regime 유지")
    public void regime_apiException_unchanged() {
        when(kisPublic.getDomesticCurrentPrice("069500")).thenThrow(new RuntimeException("KIS error"));
        assertEquals(KrxMorningRushService.MarketRegime.UNKNOWN, service.updateMarketRegime());
    }

    @Test
    @DisplayName("[P3-A] scanForEntry: RISK_OFF 시 REGIME_RISK_OFF decision 기록 + 즉시 return")
    public void scanForEntry_riskOff_blocksAll() throws Exception {
        when(kisPublic.getDomesticCurrentPrice("069500")).thenReturn(kospiQuote(-1.0));

        @SuppressWarnings("unchecked")
        Map<String, Double> pcMap = (Map<String, Double>) getField(service, "prevCloseMap");
        pcMap.put("FAKE", 1000.0);

        invokeScanForEntry();

        List<Map<String, Object>> decisions = service.getRecentDecisions(50);
        boolean blocked = decisions.stream().anyMatch(d ->
                "REGIME_RISK_OFF".equals(d.get("reasonCode")));
        assertTrue(blocked, "RISK_OFF 시 REGIME_RISK_OFF 차단 기록");
    }

    @Test
    @DisplayName("[P3-A] scanForEntry: CAUTIOUS 는 차단 안 함 (regime 알람만)")
    public void scanForEntry_cautious_proceeds() throws Exception {
        when(kisPublic.getDomesticCurrentPrice("069500")).thenReturn(kospiQuote(-0.5));

        @SuppressWarnings("unchecked")
        Map<String, Double> pcMap = (Map<String, Double>) getField(service, "prevCloseMap");
        pcMap.put("FAKE", 1000.0);

        invokeScanForEntry();

        List<Map<String, Object>> decisions = service.getRecentDecisions(50);
        boolean halted = decisions.stream().anyMatch(d ->
                "REGIME_RISK_OFF".equals(d.get("reasonCode")));
        assertFalse(halted, "CAUTIOUS 는 차단 안 됨");
    }

    // ============================================================
    // P3-B: 1분 chase guard
    // ============================================================

    @Test
    @DisplayName("[P3-B] 09:00 분봉 중 호출: high 만 추적, 진입 허용")
    public void chaseGuard_during0900Minute_trackingOnly() {
        LocalTime t = LocalTime.of(9, 0, 30);
        assertTrue(service.checkChaseGuard("0183V0", 11400, t));
        assertTrue(service.checkChaseGuard("0183V0", 11465, t));
        assertTrue(service.checkChaseGuard("0183V0", 11500, t));  // 더 높은 가격도 허용 (추적용)
    }

    @Test
    @DisplayName("[P3-B] 09:01 이후 진입가 <= first1MinHigh × 1.005 → 통과")
    public void chaseGuard_afterMinute_belowHeadroom_pass() {
        LocalTime t0 = LocalTime.of(9, 0, 30);
        service.checkChaseGuard("ABC", 11465, t0);  // first1MinHigh = 11465

        LocalTime t1 = LocalTime.of(9, 1, 30);
        // 11465 × 1.005 = 11522.325 — 11500 < 11522 → 통과
        assertTrue(service.checkChaseGuard("ABC", 11500, t1));
        assertTrue(service.checkChaseGuard("ABC", 11522, t1));
    }

    @Test
    @DisplayName("[P3-B] 09:01 이후 진입가 > first1MinHigh × 1.005 → 차단 (CHASE)")
    public void chaseGuard_afterMinute_aboveHeadroom_block() {
        LocalTime t0 = LocalTime.of(9, 0, 0);
        service.checkChaseGuard("XYZ", 11465, t0);  // first1MinHigh = 11465

        LocalTime t1 = LocalTime.of(9, 2);
        // 11465 × 1.005 = 11522.325 — 11525 > 11522 → 차단
        assertFalse(service.checkChaseGuard("XYZ", 11525, t1));
        assertFalse(service.checkChaseGuard("XYZ", 12000, t1));
    }

    @Test
    @DisplayName("[P3-B] first1MinHigh 데이터 없음 (09:00 누락) → 통과 (보수적 기본)")
    public void chaseGuard_noData_pass() {
        LocalTime t = LocalTime.of(9, 5);
        assertTrue(service.checkChaseGuard("UNTRACKED", 11500, t));
    }

    @Test
    @DisplayName("[P3-B] 08:55 (09:00 이전) → 가드 비활성, 통과")
    public void chaseGuard_before0900_disabled() {
        LocalTime t = LocalTime.of(8, 55);
        assertTrue(service.checkChaseGuard("ABC", 12000, t));
    }

    @Test
    @DisplayName("[P3-B] null time → 통과 (안전 기본)")
    public void chaseGuard_nullTime_pass() {
        assertTrue(service.checkChaseGuard("ABC", 11500, null));
    }

    @Test
    @DisplayName("[P3-B] 0183V0 회귀 시뮬: 09:00 분봉 high 11465 → 09:03 진입가 11465 (chase guard 통과)")
    public void chaseGuard_0183V0_regressionSim() {
        // 05-06 실제 케이스: BUY EXECUTED GAP_UP price=11465, prevClose=10905
        // 09:00 분봉 동안 high 추적
        LocalTime t0 = LocalTime.of(9, 0, 0);
        service.checkChaseGuard("0183V0", 11400, t0);
        service.checkChaseGuard("0183V0", 11465, t0);  // first1MinHigh = 11465

        // 09:03 진입 시도
        LocalTime t1 = LocalTime.of(9, 3, 0);
        // 진입가 11465 = high (×1.000) → 통과 OK
        assertTrue(service.checkChaseGuard("0183V0", 11465, t1),
                "high 와 동일가는 통과 (실제 진입 케이스 동일)");
        // 만약 +0.6% 추가 상승했다면 차단됐을 것
        assertFalse(service.checkChaseGuard("0183V0", 11534, t1),
                "+0.6% 추격은 차단");
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
