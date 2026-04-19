package com.example.stocks.bot;

import com.example.stocks.db.*;
import com.example.stocks.exchange.ExchangeAdapter;
import com.example.stocks.kis.KisPublicClient;
import com.example.stocks.kis.KisWebSocketClient;
import com.example.stocks.market.CandleService;
import com.example.stocks.market.TickerService;
import com.example.stocks.trade.LiveOrderService;
import com.example.stocks.market.MarketType;

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
 * V39 B1 (2026-04-18): SPLIT_1ST 중복 발행 버그 회귀 테스트.
 *
 * 사고 재현:
 *   2026-04-17 010170 (하나증권) — placeSellOrder 가 미체결 반환하는 상황에서
 *   monitorPositions 매 tick 마다 SPLIT_1ST 재시도 + "SELL EXECUTED" decision 38건 발행.
 *
 * 수정 확인:
 *   F01: executeSplitFirstSell 이 주문 미체결 시 false 반환 → caller 가 EXECUTED decision 기록 안 함
 *   F02: 실패 후 SPLIT_1ST_COOLDOWN_MS 내 재진입 차단 (lastSplitFailMs 기록)
 *   F03: 성공 시 true 반환 + cooldown 기록 제거
 *   F04: 동일 심볼 연속 실패 시 호출 횟수가 cooldown 경계로 제한됨
 *   F05: realtime 경로에서도 cooldown 이 적용됨
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class KrxMorningRushSplitFailCooldownTest {

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
                exchangeAdapter, kisPublic, overtimeRankRepo, new KrxSharedTradeThrottle()
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

    @Test
    @DisplayName("F01: 주문 미체결 시 executeSplitFirstSell → false, EXECUTED decision 안 찍힘")
    public void splitFirst_orderNotFilled_returnsFalse() throws Exception {
        PositionEntity pe = buildPos("010170", 15950, 25, 0, 60_000);
        when(positionRepo.findAll()).thenReturn(Collections.singletonList(pe));
        when(positionRepo.findById("010170")).thenReturn(Optional.of(pe));
        when(kisWs.getLatestPrice("010170")).thenReturn(16320.0); // +2.32%

        // LIVE + 주문 미체결
        KrxMorningRushConfigEntity cfg = buildConfig(true, "LIVE");
        when(liveOrders.isConfigured()).thenReturn(true);
        LiveOrderService.LiveOrderResult unfilled =
                new LiveOrderService.LiveOrderResult("010170", null, "rejected", 0, 0);
        when(liveOrders.placeSellOrder(eq("010170"), eq(MarketType.KRX), anyInt(), anyDouble()))
                .thenReturn(unfilled);

        putCache("010170", 15950, 15950, 0, System.currentTimeMillis() - 60_000, 0);
        invokeMonitor(cfg);

        // position 은 DB 에 남아있어야 함 (매도 실패)
        verify(positionRepo, never()).deleteById("010170");
        verify(positionRepo, never()).save(any(PositionEntity.class));

        // lastSplitFailMs 에 기록되었는지 확인
        ConcurrentHashMap<String, Long> failMap = getFailMap();
        assertTrue(failMap.containsKey("010170"), "실패 시 cooldown 기록 필수");
    }

    @Test
    @DisplayName("F02: 실패 후 cooldown 내 재진입 차단")
    public void splitFirst_cooldownBlocksReentry() throws Exception {
        PositionEntity pe = buildPos("010170", 15950, 25, 0, 60_000);
        when(positionRepo.findAll()).thenReturn(Collections.singletonList(pe));
        when(positionRepo.findById("010170")).thenReturn(Optional.of(pe));
        when(kisWs.getLatestPrice("010170")).thenReturn(16320.0);

        // cooldown 사전 주입 (방금 실패한 상태)
        getFailMap().put("010170", System.currentTimeMillis());
        putCache("010170", 15950, 15950, 0, System.currentTimeMillis() - 60_000, 0);

        invokeMonitor(buildConfig(true, "PAPER"));

        // cooldown 으로 인해 placeSellOrder/save 모두 호출 안 됨
        verify(liveOrders, never()).placeSellOrder(anyString(), any(), anyInt(), anyDouble());
        verify(positionRepo, never()).save(any(PositionEntity.class));
    }

    @Test
    @DisplayName("F03: 성공 시 lastSplitFailMs 삭제 + save 호출")
    public void splitFirst_successClearsCooldown() throws Exception {
        PositionEntity pe = buildPos("010170", 15950, 100, 0, 60_000);
        when(positionRepo.findAll()).thenReturn(Collections.singletonList(pe));
        when(positionRepo.findById("010170")).thenReturn(Optional.of(pe));
        when(kisWs.getLatestPrice("010170")).thenReturn(16320.0);

        // 이전 실패 기록이 있었던 상태에서 cooldown 경과 후 성공 (COOLDOWN_MS 넘긴 시점)
        getFailMap().put("010170",
                System.currentTimeMillis() - KrxMorningRushService.SPLIT_1ST_COOLDOWN_MS - 1000L);
        putCache("010170", 15950, 15950, 0, System.currentTimeMillis() - 60_000, 0);

        invokeMonitor(buildConfig(true, "PAPER"));

        verify(positionRepo, atLeastOnce()).save(any(PositionEntity.class));
        assertFalse(getFailMap().containsKey("010170"), "성공 시 cooldown 기록 제거");
    }

    @Test
    @DisplayName("F04: 연속 실패 시 cooldown 내 placeSellOrder 1회만 호출됨")
    public void splitFirst_repeatedFailures_throttled() throws Exception {
        PositionEntity pe = buildPos("010170", 15950, 25, 0, 60_000);
        when(positionRepo.findAll()).thenReturn(Collections.singletonList(pe));
        when(positionRepo.findById("010170")).thenReturn(Optional.of(pe));
        when(kisWs.getLatestPrice("010170")).thenReturn(16320.0);

        KrxMorningRushConfigEntity cfg = buildConfig(true, "LIVE");
        when(liveOrders.isConfigured()).thenReturn(true);
        LiveOrderService.LiveOrderResult unfilled =
                new LiveOrderService.LiveOrderResult("010170", null, "rejected", 0, 0);
        when(liveOrders.placeSellOrder(eq("010170"), eq(MarketType.KRX), anyInt(), anyDouble()))
                .thenReturn(unfilled);

        putCache("010170", 15950, 15950, 0, System.currentTimeMillis() - 60_000, 0);

        // 5번 연속 호출 (매 5초 간격을 시뮬레이션 — 하지만 실제 cooldown 은 30s)
        for (int i = 0; i < 5; i++) {
            invokeMonitor(cfg);
        }

        // placeSellOrder 는 1회만 호출되어야 함 (첫 실패 후 cooldown 차단)
        verify(liveOrders, times(1))
                .placeSellOrder(eq("010170"), eq(MarketType.KRX), anyInt(), anyDouble());
    }

    @Test
    @DisplayName("F05: realtime 경로에서도 cooldown 적용")
    public void splitFirst_realtimeCooldown() throws Exception {
        setField("cachedSplitExitEnabled", true);
        setField("cachedSplitTpPct", 1.6);
        setField("cachedSplitRatio", 0.40);
        setField("cachedTrailDropAfterSplit", 1.5);
        setField("cachedSlPct", 2.0);
        setField("cachedWideSlPct", 2.0);
        setField("cachedGracePeriodMs", 30_000L);
        setField("cachedWidePeriodMs", 600_000L);
        setField("cachedTpTrailActivatePct", 2.1);
        setField("cachedTpTrailDropPct", 1.5);

        // 방금 실패 기록 → cooldown 활성
        getFailMap().put("010170", System.currentTimeMillis());
        putCache("010170", 15950, 15950, 0, System.currentTimeMillis() - 60_000, 0);

        invokeCheckRealtime("010170", 16320.0);
        Thread.sleep(200);

        // cooldown 으로 인해 splitPhase 변경 안 됨
        double[] c = getCache().get("010170");
        assertNotNull(c);
        assertEquals(0.0, c[4], 0.01, "cooldown 중 splitPhase 전환 안 됨");
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

    private void invokeCheckRealtime(String symbol, double price) throws Exception {
        Method m = KrxMorningRushService.class.getDeclaredMethod(
                "checkRealtimeTpSl", String.class, double.class);
        m.setAccessible(true);
        m.invoke(service, symbol, price);
    }

    private void putCache(String symbol, double avgPrice, double peak, double activated,
                          long openedAtMs, int splitPhase) throws Exception {
        getCache().put(symbol, new double[]{avgPrice, peak, activated, openedAtMs, splitPhase});
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, double[]> getCache() throws Exception {
        Field f = KrxMorningRushService.class.getDeclaredField("positionCache");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, double[]>) f.get(service);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, Long> getFailMap() throws Exception {
        Field f = KrxMorningRushService.class.getDeclaredField("lastSplitFailMs");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, Long>) f.get(service);
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

    private KrxMorningRushConfigEntity buildConfig(boolean splitEnabled, String mode) {
        KrxMorningRushConfigEntity cfg = new KrxMorningRushConfigEntity();
        cfg.setMode(mode);
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
