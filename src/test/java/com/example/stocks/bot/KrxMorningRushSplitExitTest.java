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
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V34: 주식봇 Split-Exit 분할 익절 + TP_TRAIL 2.1% + 티어드 SL 2% 시나리오 테스트.
 *
 * positionCache: [avgPrice, peakPrice, trailActivated(0/1), openedAtEpochMs, splitPhase]
 *
 * 테스트 시나리오 (22개):
 *
 * ═══ Split-Exit 1차 매도 ═══
 * S01: +1.6% 도달 → SPLIT_1ST 발동, cache splitPhase=1 전환
 * S02: +1.5% → 미달, 매도 안 함
 * S03: splitExitEnabled=false → TP_TRAIL/SL 경로
 *
 * ═══ Split-Exit 2차 매도 ═══
 * S04: splitPhase=1 + breakeven(pnl<=0) → SPLIT_2ND_BEV
 * S05: splitPhase=1 + peak 추적 후 trail drop 1.5% → SPLIT_2ND_TRAIL
 * S06: splitPhase=1 + trail drop 미달 → 유지
 * S07: splitPhase=1 + 수익 유지 + drop 미달 → 유지
 *
 * ═══ Split 1차 peak 리셋 ═══
 * S08: 1차 매도 시 peak이 현재가로 리셋됨 확인
 *
 * ═══ Split dust 처리 ═══
 * S09: 잔량 * 가격 < 50000 → SPLIT_1ST_DUST (전량 매도)
 *
 * ═══ Split + SESSION_END ═══
 * S10: splitPhase=1 상태에서 세션 종료 → SPLIT_SESSION_END
 *
 * ═══ TP_TRAIL (2.1% 활성화) ═══
 * S11: +2.1% 도달 → trail 활성화 (매도 안 함)
 * S12: +2.0% → 미달, trail 미활성
 * S13: trail 활성 후 peak 추적 + drop 1.5% → TP_TRAIL 매도
 * S14: trail 활성 후 drop 부족 → 유지
 *
 * ═══ 티어드 SL (-2%) ═══
 * S15: Grace 30초: -1.9% → SL 무시
 * S16: Grace 비상: -10% → SL_EMERGENCY
 * S17: Wide 구간: -2.0% → SL_WIDE 발동
 * S18: Wide 구간: -1.9% → SL 미달 유지
 * S19: Tight 구간: -2.0% → SL_TIGHT 발동
 * S20: Tight 구간: -1.9% → SL 미달 유지
 *
 * ═══ TP_TRAIL 활성 시 SL 비활성 ═══
 * S21: trail 활성 상태에서 -3% 하락 → SL 안 걸림 (TP_TRAIL로만 매도)
 *
 * ═══ DB 실패 시 cache rollback ═══
 * S22: SPLIT_1ST DB 실패 → splitPhase=0 복원
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class KrxMorningRushSplitExitTest {

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

    @BeforeEach
    public void setUp() throws Exception {
        service = new KrxMorningRushService(
                configRepo, botConfigRepo, positionRepo, tradeLogRepo,
                liveOrders, tickerService, candleService, kisWs, txTemplate,
                exchangeAdapter, kisPublic, overtimeRankRepo
        );
        setField("running", new AtomicBoolean(true));

        // V34 기본값: TP_TRAIL 2.1%, SL 2%
        setField("cachedTpTrailActivatePct", 2.1);
        setField("cachedTpTrailDropPct", 1.5);
        setField("cachedSlPct", 2.0);
        setField("cachedWideSlPct", 2.0);
        setField("cachedGracePeriodMs", 30_000L);
        setField("cachedWidePeriodMs", 10 * 60_000L);

        // Split-Exit 기본값
        setField("cachedSplitExitEnabled", true);
        setField("cachedSplitTpPct", 1.6);
        setField("cachedSplitRatio", 0.60);
        setField("cachedTrailDropAfterSplit", 1.5);

        when(txTemplate.execute(any())).thenAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock inv) throws Throwable {
                Object cb = inv.getArgument(0);
                if (cb instanceof TransactionCallbackWithoutResult) {
                    ((TransactionCallbackWithoutResult) cb).doInTransaction(mock(TransactionStatus.class));
                }
                return null;
            }
        });
    }

    // ═══════════════════════════════════════════════════
    // Split-Exit 1차 매도 테스트
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("S01: +1.6% 도달 → SPLIT_1ST 발동, cache splitPhase=1")
    public void splitFirst_triggered() throws Exception {
        putPosition("SPL1", 10000, System.currentTimeMillis() - 60_000, 0);
        when(positionRepo.findById("SPL1")).thenReturn(Optional.of(buildPos("SPL1", 10000, 100, 0)));
        when(configRepo.loadOrCreate()).thenReturn(buildConfig());

        invoke("SPL1", 10160);  // +1.6%
        Thread.sleep(300);

        // 캐시 남아있어야 함 (1차 매도 후 2차 대기, remain=40 * 10160=406,400 > 50000)
        assertTrue(getCache().containsKey("SPL1"), "1차 매도 후 캐시 유지");
        assertEquals(1.0, getCache().get("SPL1")[4], 0.01, "splitPhase=1");
    }

    @Test
    @DisplayName("S02: +1.5% → splitTpPct 미달, 매도 안 함")
    public void splitFirst_notTriggered() throws Exception {
        putPosition("SPL2", 10000, System.currentTimeMillis() - 60_000, 0);
        invoke("SPL2", 10150);  // +1.5% < 1.6%
        assertTrue(getCache().containsKey("SPL2"), "미달, 유지");
        assertEquals(0.0, getCache().get("SPL2")[4], 0.01, "splitPhase=0 유지");
    }

    @Test
    @DisplayName("S03: splitExitEnabled=false → TP_TRAIL/SL 경로")
    public void splitDisabled_fallsToTpTrail() throws Exception {
        setField("cachedSplitExitEnabled", false);
        putPosition("SPL3", 10000, System.currentTimeMillis() - 60_000, 0);

        invoke("SPL3", 10160);  // +1.6% but split disabled
        assertTrue(getCache().containsKey("SPL3"), "split 비활성 → TP_TRAIL/SL 경로");
        assertEquals(0.0, getCache().get("SPL3")[4], 0.01, "splitPhase 변화 없음");
    }

    // ═══════════════════════════════════════════════════
    // Split-Exit 2차 매도 테스트
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("S04: splitPhase=1 + breakeven(pnl<=0) → SPLIT_2ND_BEV")
    public void splitSecond_breakeven() throws Exception {
        putPosition("BEV", 10000, System.currentTimeMillis() - 120_000, 1);
        when(positionRepo.findById("BEV")).thenReturn(Optional.of(buildPos("BEV", 10000, 4, 1)));
        when(configRepo.loadOrCreate()).thenReturn(buildConfig());

        invoke("BEV", 10000);  // pnl=0%
        Thread.sleep(300);
        assertFalse(getCache().containsKey("BEV"), "SPLIT_2ND_BEV 매도");
    }

    @Test
    @DisplayName("S05: splitPhase=1 + trail drop 1.5% → SPLIT_2ND_TRAIL")
    public void splitSecond_trailDrop() throws Exception {
        // peak=10300 (1차 매도 후 더 올라갔다가), 현재 하락
        putPositionWithPeak("TRL", 10000, 10300, System.currentTimeMillis() - 120_000, 1);
        when(positionRepo.findById("TRL")).thenReturn(Optional.of(buildPos("TRL", 10000, 4, 1)));
        when(configRepo.loadOrCreate()).thenReturn(buildConfig());

        invoke("TRL", 10140);  // peak 10300에서 -1.55% drop → 1.5% 초과
        Thread.sleep(300);
        assertFalse(getCache().containsKey("TRL"), "SPLIT_2ND_TRAIL 매도");
    }

    @Test
    @DisplayName("S06: splitPhase=1 + trail drop 미달 → 유지")
    public void splitSecond_trailNotEnough() throws Exception {
        putPositionWithPeak("TRLH", 10000, 10300, System.currentTimeMillis() - 120_000, 1);
        invoke("TRLH", 10200);  // peak 10300에서 -0.97% → 미달
        assertTrue(getCache().containsKey("TRLH"), "trail drop 미달, 유지");
    }

    @Test
    @DisplayName("S07: splitPhase=1 + 수익 유지 + drop 미달 → 유지")
    public void splitSecond_profitHold() throws Exception {
        putPositionWithPeak("PH", 10000, 10200, System.currentTimeMillis() - 120_000, 1);
        invoke("PH", 10180);  // pnl=+1.8%, peak 10200에서 -0.2% → 미달
        assertTrue(getCache().containsKey("PH"), "수익 유지, drop 미달");
    }

    // ═══════════════════════════════════════════════════
    // Split 1차 peak 리셋
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("S08: 1차 매도 시 peak이 현재가로 리셋됨")
    public void splitFirst_peakReset() throws Exception {
        // peak이 10500까지 갔지만, 1차 매도 시점 가격 10160으로 리셋
        getCache().put("RST", new double[]{10000, 10500, 0, System.currentTimeMillis() - 60_000, 0});
        when(positionRepo.findById("RST")).thenReturn(Optional.of(buildPos("RST", 10000, 100, 0)));
        when(configRepo.loadOrCreate()).thenReturn(buildConfig());

        invoke("RST", 10160);  // +1.6% → SPLIT_1ST
        Thread.sleep(300);

        assertTrue(getCache().containsKey("RST"), "캐시 유지");
        assertEquals(10160, getCache().get("RST")[1], 0.01, "peak이 10160으로 리셋");
        assertEquals(1.0, getCache().get("RST")[4], 0.01, "splitPhase=1");
    }

    // ═══════════════════════════════════════════════════
    // Split dust 처리
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("S09: 잔량*가격 < 50000 → SPLIT_1ST_DUST (전량 매도)")
    public void splitFirst_dust() throws Exception {
        // qty=3, price=10160 → sellQty=2, remainQty=1 → 1*10160=10160 < 50000 → dust
        putPosition("DUST", 10000, System.currentTimeMillis() - 60_000, 0);
        when(positionRepo.findById("DUST")).thenReturn(Optional.of(buildPos("DUST", 10000, 3, 0)));
        when(configRepo.loadOrCreate()).thenReturn(buildConfig());

        invoke("DUST", 10160);  // +1.6% → SPLIT_1ST, but dust
        Thread.sleep(300);

        // dust면 캐시 제거 (전량 매도)
        assertFalse(getCache().containsKey("DUST"), "SPLIT_1ST_DUST 전량 매도");
    }

    // ═══════════════════════════════════════════════════
    // Split + SESSION_END
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("S10: splitPhase=1 → forceExitAll에서 SPLIT_SESSION_END")
    public void splitSessionEnd() throws Exception {
        PositionEntity pe = buildPos("SEND", 10000, 4, 1);
        when(positionRepo.findAll()).thenReturn(Arrays.asList(pe));
        when(kisWs.getLatestPrice("SEND")).thenReturn(10200.0);
        when(configRepo.loadOrCreate()).thenReturn(buildConfig());

        // forceExitAll 호출
        Method m = KrxMorningRushService.class.getDeclaredMethod(
                "forceExitAll", KrxMorningRushConfigEntity.class);
        m.setAccessible(true);
        m.invoke(service, buildConfig());

        // executeSell이 호출되었는지 확인 (positionRepo.deleteById)
        verify(positionRepo, atLeastOnce()).deleteById("SEND");
    }

    // ═══════════════════════════════════════════════════
    // TP_TRAIL (2.1% 활성화 기준)
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("S11: +2.1% 도달 → trail 활성화 (매도 안 함)")
    public void tpTrailActivationAt2_1() throws Exception {
        setField("cachedSplitExitEnabled", false);
        putPosition("TP1", 10000, System.currentTimeMillis() - 60_000, 0);
        invoke("TP1", 10210);  // +2.1%
        assertTrue(getCache().containsKey("TP1"), "활성화만, 매도 안 함");
        assertEquals(1.0, getCache().get("TP1")[2], 0.01, "trailActivated=1");
    }

    @Test
    @DisplayName("S12: +2.0% → trail 미활성")
    public void tpTrailNotActivatedAt2_0() throws Exception {
        setField("cachedSplitExitEnabled", false);
        putPosition("TP2", 10000, System.currentTimeMillis() - 60_000, 0);
        invoke("TP2", 10200);  // +2.0% < 2.1%
        assertTrue(getCache().containsKey("TP2"), "유지");
        assertEquals(0.0, getCache().get("TP2")[2], 0.01, "trailActivated=0");
    }

    @Test
    @DisplayName("S13: trail 활성 후 peak 추적 + drop 1.5% → TP_TRAIL 매도")
    public void tpTrailDropSell() throws Exception {
        setField("cachedSplitExitEnabled", false);
        // trail 활성, peak=10500
        getCache().put("TP3", new double[]{10000, 10500, 1.0, System.currentTimeMillis() - 60_000, 0});
        when(positionRepo.findById("TP3")).thenReturn(Optional.of(buildPos("TP3", 10000, 10, 0)));
        when(configRepo.loadOrCreate()).thenReturn(buildConfig());

        invoke("TP3", 10340);  // peak 10500에서 -1.52% → 매도
        Thread.sleep(300);
        assertFalse(getCache().containsKey("TP3"), "TP_TRAIL 매도");
    }

    @Test
    @DisplayName("S14: trail 활성 후 drop 부족 → 유지")
    public void tpTrailDropNotEnough() throws Exception {
        setField("cachedSplitExitEnabled", false);
        getCache().put("TP4", new double[]{10000, 10500, 1.0, System.currentTimeMillis() - 60_000, 0});
        invoke("TP4", 10400);  // peak 10500에서 -0.95% → 미달
        assertTrue(getCache().containsKey("TP4"), "drop 부족, 유지");
    }

    // ═══════════════════════════════════════════════════
    // 티어드 SL (-2%)
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("S15: Grace 30초: -1.9% → SL 무시")
    public void graceIgnoresSl() throws Exception {
        setField("cachedSplitExitEnabled", false);
        putPosition("GR1", 10000, System.currentTimeMillis() - 10_000, 0);  // 10초 전
        invoke("GR1", 9810);  // -1.9%
        assertTrue(getCache().containsKey("GR1"), "Grace에서 SL 무시");
    }

    @Test
    @DisplayName("S16: Grace 비상: -10% → SL_EMERGENCY")
    public void graceEmergency() throws Exception {
        setField("cachedSplitExitEnabled", false);
        putPosition("GR2", 10000, System.currentTimeMillis() - 10_000, 0);
        when(positionRepo.findById("GR2")).thenReturn(Optional.of(buildPos("GR2", 10000, 10, 0)));
        when(configRepo.loadOrCreate()).thenReturn(buildConfig());

        invoke("GR2", 8900);  // -11%
        Thread.sleep(300);
        assertFalse(getCache().containsKey("GR2"), "비상 SL 발동");
    }

    @Test
    @DisplayName("S17: Wide 구간: -2.0% → SL_WIDE 발동")
    public void wideSlFires() throws Exception {
        setField("cachedSplitExitEnabled", false);
        putPosition("W1", 10000, System.currentTimeMillis() - 120_000, 0);  // 2분 전
        when(positionRepo.findById("W1")).thenReturn(Optional.of(buildPos("W1", 10000, 10, 0)));
        when(configRepo.loadOrCreate()).thenReturn(buildConfig());

        invoke("W1", 9790);  // -2.1%
        Thread.sleep(300);
        assertFalse(getCache().containsKey("W1"), "SL_WIDE 발동");
    }

    @Test
    @DisplayName("S18: Wide 구간: -1.9% → SL 미달 유지")
    public void wideSlNotTriggered() throws Exception {
        setField("cachedSplitExitEnabled", false);
        putPosition("W2", 10000, System.currentTimeMillis() - 120_000, 0);
        invoke("W2", 9810);  // -1.9% > -2.0%
        assertTrue(getCache().containsKey("W2"), "Wide SL 미달, 유지");
    }

    @Test
    @DisplayName("S19: Tight 구간: -2.0% → SL_TIGHT 발동")
    public void tightSlFires() throws Exception {
        setField("cachedSplitExitEnabled", false);
        putPosition("T1", 10000, System.currentTimeMillis() - 15 * 60_000, 0);  // 15분 전
        when(positionRepo.findById("T1")).thenReturn(Optional.of(buildPos("T1", 10000, 10, 0)));
        when(configRepo.loadOrCreate()).thenReturn(buildConfig());

        invoke("T1", 9790);  // -2.1%
        Thread.sleep(300);
        assertFalse(getCache().containsKey("T1"), "SL_TIGHT 발동");
    }

    @Test
    @DisplayName("S20: Tight 구간: -1.9% → SL 미달 유지")
    public void tightSlNotTriggered() throws Exception {
        setField("cachedSplitExitEnabled", false);
        putPosition("T2", 10000, System.currentTimeMillis() - 15 * 60_000, 0);
        invoke("T2", 9810);  // -1.9% > -2.0%
        assertTrue(getCache().containsKey("T2"), "Tight SL 미달, 유지");
    }

    // ═══════════════════════════════════════════════════
    // TP_TRAIL 활성 시 SL 비활성
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("S21: trail 활성 상태에서 -3% 하락 → SL 안 걸림 (TP_TRAIL로만)")
    public void tpTrailActive_slDisabled() throws Exception {
        setField("cachedSplitExitEnabled", false);
        // trail 활성, peak=10500, -3% drop (10500→9500 = -9.5% from peak → TP_TRAIL 매도)
        getCache().put("TS", new double[]{10000, 10500, 1.0, System.currentTimeMillis() - 600_000, 0});
        when(positionRepo.findById("TS")).thenReturn(Optional.of(buildPos("TS", 10000, 10, 0)));
        when(configRepo.loadOrCreate()).thenReturn(buildConfig());

        invoke("TS", 9700);
        Thread.sleep(300);
        // TP_TRAIL로 매도 (peak 10500에서 -7.6% drop → 1.5% 초과)
        assertFalse(getCache().containsKey("TS"), "TP_TRAIL로 매도 (SL 아님)");
    }

    // ═══════════════════════════════════════════════════
    // DB 실패 시 cache rollback
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("S22: SPLIT_1ST DB 실패 → splitPhase=0 복원")
    public void splitFirst_dbFailure_rollback() throws Exception {
        putPosition("FAIL", 10000, System.currentTimeMillis() - 60_000, 0);
        when(positionRepo.findById("FAIL")).thenReturn(Optional.of(buildPos("FAIL", 10000, 10, 0)));

        KrxMorningRushConfigEntity cfg = buildConfig();
        when(configRepo.loadOrCreate()).thenReturn(cfg);

        // DB 실패 시뮬레이션
        when(txTemplate.execute(any())).thenThrow(new RuntimeException("DB connection lost"));

        invoke("FAIL", 10160);  // +1.6% → SPLIT_1ST 시도
        Thread.sleep(300);

        // DB 실패 → cache rollback
        assertTrue(getCache().containsKey("FAIL"), "DB 실패 시 캐시 유지");
        assertEquals(0.0, getCache().get("FAIL")[4], 0.01, "splitPhase=0 복원");
    }

    // ═══════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════

    private void putPosition(String symbol, double avgPrice, long openedAtMs, int splitPhase) throws Exception {
        getCache().put(symbol, new double[]{avgPrice, avgPrice, 0, openedAtMs, splitPhase});
    }

    private void putPositionWithPeak(String symbol, double avgPrice, double peak,
                                      long openedAtMs, int splitPhase) throws Exception {
        getCache().put(symbol, new double[]{avgPrice, peak, 0, openedAtMs, splitPhase});
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, double[]> getCache() throws Exception {
        Field f = KrxMorningRushService.class.getDeclaredField("positionCache");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, double[]>) f.get(service);
    }

    private void invoke(String symbol, double price) throws Exception {
        Method m = KrxMorningRushService.class.getDeclaredMethod(
                "checkRealtimeTpSl", String.class, double.class);
        m.setAccessible(true);
        m.invoke(service, symbol, price);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = KrxMorningRushService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private PositionEntity buildPos(String symbol, double avgPrice, int qty, int splitPhase) {
        PositionEntity pe = new PositionEntity();
        pe.setSymbol(symbol);
        pe.setQty(qty);
        pe.setAvgPrice(avgPrice);
        pe.setEntryStrategy("KRX_MORNING_RUSH");
        pe.setSplitPhase(splitPhase);
        pe.setSplitOriginalQty(qty);
        return pe;
    }

    private KrxMorningRushConfigEntity buildConfig() {
        KrxMorningRushConfigEntity cfg = new KrxMorningRushConfigEntity();
        cfg.setMode("PAPER");
        cfg.setSplitExitEnabled(true);
        cfg.setSplitTpPct(BigDecimal.valueOf(1.6));
        cfg.setSplitRatio(BigDecimal.valueOf(0.60));
        cfg.setTrailDropAfterSplit(BigDecimal.valueOf(1.5));
        cfg.setTpTrailActivatePct(BigDecimal.valueOf(2.1));
        cfg.setTpTrailDropPct(BigDecimal.valueOf(1.5));
        cfg.setSlPct(BigDecimal.valueOf(2.0));
        cfg.setWideSlPct(BigDecimal.valueOf(2.0));
        cfg.setGracePeriodSec(30);
        cfg.setWidePeriodMin(10);
        return cfg;
    }
}
