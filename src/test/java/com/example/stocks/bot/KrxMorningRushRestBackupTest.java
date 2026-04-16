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
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V34: monitorPositions REST 백업 경로 테스트.
 * WebSocket이 끊겼을 때 REST polling으로 Split-Exit / TP_TRAIL / 티어드 SL 동작 확인.
 *
 * 테스트 시나리오 (10개):
 *
 * ═══ REST Split-Exit ═══
 * R01: splitPhase=0 + pnl>=1.6% → SPLIT_1ST (REST backup)
 * R02: splitPhase=1 + breakeven → SPLIT_2ND_BEV (REST backup)
 * R03: splitPhase=1 + trail drop → SPLIT_2ND_TRAIL (REST backup)
 *
 * ═══ REST TP_TRAIL ═══
 * R04: splitExit 비활성 + pnl>=2.1% → trail 활성화 (캐시 업데이트)
 * R05: trail 활성 + peak drop 1.5% → TP_TRAIL 매도 (REST backup)
 *
 * ═══ REST 티어드 SL ═══
 * R06: Grace -10% → SL_EMERGENCY (REST backup)
 * R07: Wide -2% → SL_WIDE (REST backup)
 * R08: Tight -2% → SL_TIGHT (REST backup)
 *
 * ═══ REST TIME_STOP ═══
 * R09: 30min + pnl<0 → TIME_STOP (REST backup)
 *
 * ═══ REST Split 미달 유지 ═══
 * R10: splitPhase=0 + pnl<1.6% → 매도 안 함
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class KrxMorningRushRestBackupTest {

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
    // REST Split-Exit 테스트
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("R01: REST backup — splitPhase=0 + pnl>=1.6% → SPLIT_1ST")
    public void restSplitFirst() throws Exception {
        PositionEntity pe = buildPos("RS1", 10000, 100, 0, 60_000);
        when(positionRepo.findAll()).thenReturn(Arrays.asList(pe));
        when(positionRepo.findById("RS1")).thenReturn(Optional.of(pe));
        when(kisWs.getLatestPrice("RS1")).thenReturn(10160.0);

        // positionCache에 등록 (monitorPositions는 캐시에서 peak 읽음)
        putCache("RS1", 10000, 10000, 0, System.currentTimeMillis() - 60_000, 0);

        invokeMonitor(buildConfig(true));

        // SPLIT_1ST 실행 확인: positionRepo.save 호출 (분할 매도 후 잔량 저장)
        verify(positionRepo, atLeastOnce()).save(any(PositionEntity.class));
    }

    @Test
    @DisplayName("R02: REST backup — splitPhase=1 + breakeven → SPLIT_2ND_BEV")
    public void restSplitSecondBreakeven() throws Exception {
        PositionEntity pe = buildPos("RS2", 10000, 40, 1, 120_000);
        when(positionRepo.findAll()).thenReturn(Arrays.asList(pe));
        when(positionRepo.findById("RS2")).thenReturn(Optional.of(pe));
        when(kisWs.getLatestPrice("RS2")).thenReturn(10000.0);  // pnl=0%

        putCache("RS2", 10000, 10100, 0, System.currentTimeMillis() - 120_000, 1);

        invokeMonitor(buildConfig(true));

        verify(positionRepo, atLeastOnce()).deleteById("RS2");
    }

    @Test
    @DisplayName("R03: REST backup — splitPhase=1 + trail drop → SPLIT_2ND_TRAIL")
    public void restSplitSecondTrail() throws Exception {
        PositionEntity pe = buildPos("RS3", 10000, 40, 1, 120_000);
        when(positionRepo.findAll()).thenReturn(Arrays.asList(pe));
        when(positionRepo.findById("RS3")).thenReturn(Optional.of(pe));
        when(kisWs.getLatestPrice("RS3")).thenReturn(10140.0);  // peak 10300에서 -1.55%

        putCache("RS3", 10000, 10300, 0, System.currentTimeMillis() - 120_000, 1);

        invokeMonitor(buildConfig(true));

        verify(positionRepo, atLeastOnce()).deleteById("RS3");
    }

    // ═══════════════════════════════════════════════════
    // REST TP_TRAIL 테스트
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("R04: REST backup — splitExit 비활성 + pnl>=2.1% → trail 활성화")
    public void restTpTrailActivation() throws Exception {
        PositionEntity pe = buildPos("RT4", 10000, 10, 0, 60_000);
        when(positionRepo.findAll()).thenReturn(Arrays.asList(pe));
        when(kisWs.getLatestPrice("RT4")).thenReturn(10210.0);  // +2.1%

        putCache("RT4", 10000, 10000, 0, System.currentTimeMillis() - 60_000, 0);

        invokeMonitor(buildConfig(false));

        // trail 활성화 확인 (캐시의 [2]=1.0)
        double[] cached = getCache().get("RT4");
        assertNotNull(cached, "캐시 유지");
        assertEquals(1.0, cached[2], 0.01, "trail activated");
    }

    @Test
    @DisplayName("R05: REST backup — trail 활성 + peak drop 1.5% → TP_TRAIL 매도")
    public void restTpTrailSell() throws Exception {
        PositionEntity pe = buildPos("RT5", 10000, 10, 0, 60_000);
        when(positionRepo.findAll()).thenReturn(Arrays.asList(pe));
        when(positionRepo.findById("RT5")).thenReturn(Optional.of(pe));
        when(kisWs.getLatestPrice("RT5")).thenReturn(10340.0);  // peak 10500에서 -1.52%

        putCache("RT5", 10000, 10500, 1, System.currentTimeMillis() - 60_000, 0);

        invokeMonitor(buildConfig(false));

        verify(positionRepo, atLeastOnce()).deleteById("RT5");
    }

    // ═══════════════════════════════════════════════════
    // REST 티어드 SL 테스트
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("R06: REST backup — Grace -10% → SL_EMERGENCY")
    public void restSlEmergency() throws Exception {
        PositionEntity pe = buildPos("RE6", 10000, 10, 0, 10_000);  // 10초 전
        when(positionRepo.findAll()).thenReturn(Arrays.asList(pe));
        when(positionRepo.findById("RE6")).thenReturn(Optional.of(pe));
        when(kisWs.getLatestPrice("RE6")).thenReturn(8900.0);  // -11%

        putCache("RE6", 10000, 10000, 0, System.currentTimeMillis() - 10_000, 0);

        invokeMonitor(buildConfig(false));

        verify(positionRepo, atLeastOnce()).deleteById("RE6");
    }

    @Test
    @DisplayName("R07: REST backup — Wide -2% → SL_WIDE")
    public void restSlWide() throws Exception {
        PositionEntity pe = buildPos("RW7", 10000, 10, 0, 120_000);  // 2분 전
        when(positionRepo.findAll()).thenReturn(Arrays.asList(pe));
        when(positionRepo.findById("RW7")).thenReturn(Optional.of(pe));
        when(kisWs.getLatestPrice("RW7")).thenReturn(9790.0);  // -2.1%

        putCache("RW7", 10000, 10000, 0, System.currentTimeMillis() - 120_000, 0);

        invokeMonitor(buildConfig(false));

        verify(positionRepo, atLeastOnce()).deleteById("RW7");
    }

    @Test
    @DisplayName("R08: REST backup — Tight -2% → SL_TIGHT")
    public void restSlTight() throws Exception {
        PositionEntity pe = buildPos("RT8", 10000, 10, 0, 15 * 60_000);  // 15분 전
        when(positionRepo.findAll()).thenReturn(Arrays.asList(pe));
        when(positionRepo.findById("RT8")).thenReturn(Optional.of(pe));
        when(kisWs.getLatestPrice("RT8")).thenReturn(9790.0);  // -2.1%

        putCache("RT8", 10000, 10000, 0, System.currentTimeMillis() - 15 * 60_000, 0);

        invokeMonitor(buildConfig(false));

        verify(positionRepo, atLeastOnce()).deleteById("RT8");
    }

    // ═══════════════════════════════════════════════════
    // REST TIME_STOP
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("R09: REST backup — 30min + pnl<0 → TIME_STOP")
    public void restTimeStop() throws Exception {
        PositionEntity pe = buildPos("RT9", 10000, 10, 0, 35 * 60_000);  // 35분 전
        when(positionRepo.findAll()).thenReturn(Arrays.asList(pe));
        when(positionRepo.findById("RT9")).thenReturn(Optional.of(pe));
        when(kisWs.getLatestPrice("RT9")).thenReturn(9900.0);  // -1% (SL 미달이지만 time stop)

        putCache("RT9", 10000, 10000, 0, System.currentTimeMillis() - 35 * 60_000, 0);

        invokeMonitor(buildConfig(false));

        verify(positionRepo, atLeastOnce()).deleteById("RT9");
    }

    // ═══════════════════════════════════════════════════
    // REST Split 미달 유지
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("R10: REST backup — splitPhase=0 + pnl<1.6% → 매도 안 함")
    public void restSplitNotTriggered() throws Exception {
        PositionEntity pe = buildPos("RN10", 10000, 100, 0, 60_000);
        when(positionRepo.findAll()).thenReturn(Arrays.asList(pe));
        when(kisWs.getLatestPrice("RN10")).thenReturn(10150.0);  // +1.5% < 1.6%

        putCache("RN10", 10000, 10000, 0, System.currentTimeMillis() - 60_000, 0);

        invokeMonitor(buildConfig(true));

        verify(positionRepo, never()).deleteById("RN10");
        assertNotNull(getCache().get("RN10"), "캐시 유지");
    }

    // ═══════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════

    private void invokeMonitor(KrxMorningRushConfigEntity cfg) throws Exception {
        Method m = KrxMorningRushService.class.getDeclaredMethod(
                "monitorPositions", KrxMorningRushConfigEntity.class);
        m.setAccessible(true);
        m.invoke(service, cfg);
    }

    private void putCache(String symbol, double avgPrice, double peak,
                           double activated, long openedAtMs, int splitPhase) throws Exception {
        getCache().put(symbol, new double[]{avgPrice, peak, activated, openedAtMs, splitPhase});
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, double[]> getCache() throws Exception {
        Field f = KrxMorningRushService.class.getDeclaredField("positionCache");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, double[]>) f.get(service);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = KrxMorningRushService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private PositionEntity buildPos(String symbol, double avgPrice, int qty,
                                     int splitPhase, long elapsedMs) {
        PositionEntity pe = new PositionEntity();
        pe.setSymbol(symbol);
        pe.setQty(qty);
        pe.setAvgPrice(avgPrice);
        pe.setEntryStrategy("KRX_MORNING_RUSH");
        pe.setSplitPhase(splitPhase);
        pe.setSplitOriginalQty(qty);
        pe.setOpenedAt(Instant.now().minusMillis(elapsedMs));
        return pe;
    }

    private KrxMorningRushConfigEntity buildConfig(boolean splitEnabled) {
        KrxMorningRushConfigEntity cfg = new KrxMorningRushConfigEntity();
        cfg.setMode("PAPER");
        cfg.setSplitExitEnabled(splitEnabled);
        cfg.setSplitTpPct(BigDecimal.valueOf(1.6));
        cfg.setSplitRatio(BigDecimal.valueOf(0.40));
        cfg.setTrailDropAfterSplit(BigDecimal.valueOf(1.5));
        cfg.setTpTrailActivatePct(BigDecimal.valueOf(2.1));
        cfg.setTpTrailDropPct(BigDecimal.valueOf(1.5));
        cfg.setSlPct(BigDecimal.valueOf(2.0));
        cfg.setWideSlPct(BigDecimal.valueOf(2.0));
        cfg.setGracePeriodSec(30);
        cfg.setWidePeriodMin(10);
        cfg.setTimeStopMin(30);
        return cfg;
    }
}
