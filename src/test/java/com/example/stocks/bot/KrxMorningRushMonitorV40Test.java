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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * V40 (2026-04-20): monitorPositions REST 경로 — SL/TP 판정 제거 검증.
 *
 * 배경:
 *   2026-04-20 운영 로그에서 348080 종목이 Grace 45초 종료 직후 REST 5초 폴링이
 *   WS 다음 틱보다 먼저 -3.07%를 포착하여 "SL_WIDE (REST backup)" 으로 조기 매도됨.
 *   SL/TP 판정은 WebSocket realtime(checkRealtimeTpSl) 단독 책임으로 일원화.
 *
 * 시나리오:
 *   V01 — splitExitEnabled=true 여도 REST 경로에서 SPLIT_1ST 매도 안 함
 *   V02 — pnl=-5% (Wide 구간 SL 임계 초과) 에서도 REST 매도 안 함
 *   V03 — pnl=+5% (TP_TRAIL 활성 임계 초과) 에서도 REST 매도 안 함
 *   V04 — elapsed >= timeStopMin + pnl<0 → TIME_STOP 매도
 *   V05 — elapsed >= timeStopMin + pnl>=0 → TIME_STOP 발동 안 함
 *   V06 — peak 업데이트는 유지 (POS_STATUS 로그 발행)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class KrxMorningRushMonitorV40Test {

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
    @DisplayName("V01: splitExitEnabled=true 여도 REST 경로에서 SPLIT_1ST 매도 안 함")
    public void v01_noSplit1stFromRest() throws Exception {
        KrxMorningRushConfigEntity cfg = buildConfig(true);
        PositionEntity pos = buildPos("010170", 16170, 3, 0, Instant.now().minusSeconds(60));
        when(positionRepo.findAll()).thenReturn(Collections.singletonList(pos));
        when(positionRepo.findById("010170")).thenReturn(Optional.of(pos));
        when(kisWs.getLatestPrice("010170")).thenReturn(16430.0);  // +1.61% (splitTpPct 1.6% 초과)

        invokeMonitor(cfg);

        verifyNoInteractions(liveOrders);
        assertFalse(hasSellDecision("010170"),
                "V40: splitTpPct 초과해도 REST 경로는 매도 발행 안 함 — WS 단독 책임");
    }

    @Test
    @DisplayName("V02: Wide 구간 -5% 손실에서도 REST 경로는 SL 안 함")
    public void v02_noSlWideFromRest() throws Exception {
        KrxMorningRushConfigEntity cfg = buildConfig(false);
        PositionEntity pos = buildPos("TEST2", 10000, 1, 0, Instant.now().minusSeconds(120));
        when(positionRepo.findAll()).thenReturn(Collections.singletonList(pos));
        when(positionRepo.findById("TEST2")).thenReturn(Optional.of(pos));
        when(kisWs.getLatestPrice("TEST2")).thenReturn(9500.0);  // -5.0%

        invokeMonitor(cfg);

        verifyNoInteractions(liveOrders);
        assertFalse(hasSellDecision("TEST2"),
                "V40: Wide 구간 큰 손실이어도 REST 경로 SL 안 함");
    }

    @Test
    @DisplayName("V03: TP_TRAIL 활성 임계 +5% 수익에서도 REST 경로는 TP 매도 안 함")
    public void v03_noTpTrailFromRest() throws Exception {
        KrxMorningRushConfigEntity cfg = buildConfig(false);
        PositionEntity pos = buildPos("TEST3", 10000, 1, 0, Instant.now().minusSeconds(60));
        when(positionRepo.findAll()).thenReturn(Collections.singletonList(pos));
        when(kisWs.getLatestPrice("TEST3")).thenReturn(10500.0);  // +5%

        invokeMonitor(cfg);

        verifyNoInteractions(liveOrders);
        assertFalse(hasSellDecision("TEST3"),
                "V40: 수익 임계 초과해도 REST 경로 TP_TRAIL 매도 안 함");
    }

    @Test
    @DisplayName("V04: elapsed >= timeStopMin + pnl<0 → TIME_STOP 매도")
    public void v04_timeStopFires() throws Exception {
        KrxMorningRushConfigEntity cfg = buildConfig(false);
        cfg.setTimeStopMin(30);
        PositionEntity pos = buildPos("TSTOP", 10000, 1, 0,
                Instant.now().minusSeconds(35 * 60L));  // 35분 경과
        when(positionRepo.findAll()).thenReturn(Collections.singletonList(pos));
        when(positionRepo.findById("TSTOP")).thenReturn(Optional.of(pos));
        when(kisWs.getLatestPrice("TSTOP")).thenReturn(9900.0);  // -1%

        invokeMonitor(cfg);

        assertTrue(hasDecisionWithReasonCode("TSTOP", "TIME_STOP"),
                "V40: TIME_STOP 경로는 REST 유지");
    }

    @Test
    @DisplayName("V05: elapsed >= timeStopMin + pnl>=0 → TIME_STOP 발동 안 함")
    public void v05_timeStopNotFireWhenProfit() throws Exception {
        KrxMorningRushConfigEntity cfg = buildConfig(false);
        cfg.setTimeStopMin(30);
        PositionEntity pos = buildPos("PROFIT", 10000, 1, 0,
                Instant.now().minusSeconds(35 * 60L));
        when(positionRepo.findAll()).thenReturn(Collections.singletonList(pos));
        when(kisWs.getLatestPrice("PROFIT")).thenReturn(10100.0);  // +1%

        invokeMonitor(cfg);

        verifyNoInteractions(liveOrders);
        assertFalse(hasSellDecision("PROFIT"),
                "V40: 수익 상태면 TIME_STOP 발동 안 함");
    }

    @Test
    @DisplayName("V06: peak 업데이트 유지 + POS_STATUS 로그 발행")
    public void v06_peakAndStatusLogged() throws Exception {
        KrxMorningRushConfigEntity cfg = buildConfig(false);
        PositionEntity pos = buildPos("PEAK", 10000, 1, 0, Instant.now().minusSeconds(60));
        when(positionRepo.findAll()).thenReturn(Collections.singletonList(pos));
        when(kisWs.getLatestPrice("PEAK")).thenReturn(10300.0);

        // positionCache에 peak=10000 초기화
        getCache().put("PEAK", new double[]{10000, 10000, 0, Instant.now().toEpochMilli(), 0});

        invokeMonitor(cfg);

        double[] cached = getCache().get("PEAK");
        assertEquals(10300.0, cached[1], 0.01, "V40: peak 업데이트 유지");
        assertTrue(hasDecisionWithReasonCode("PEAK", "POS_STATUS"),
                "V40: POS_STATUS 로그 유지");
    }

    // ═══ Helpers ═══

    private KrxMorningRushConfigEntity buildConfig(boolean splitExitEnabled) {
        KrxMorningRushConfigEntity cfg = new KrxMorningRushConfigEntity();
        cfg.setMode("PAPER");
        cfg.setSplitExitEnabled(splitExitEnabled);
        cfg.setSplitTpPct(BigDecimal.valueOf(1.6));
        cfg.setTrailDropAfterSplit(BigDecimal.valueOf(1.5));
        cfg.setTpTrailActivatePct(BigDecimal.valueOf(3.0));
        cfg.setTpTrailDropPct(BigDecimal.valueOf(1.5));
        cfg.setSlPct(BigDecimal.valueOf(2.4));
        cfg.setWideSlPct(BigDecimal.valueOf(2.4));
        cfg.setGracePeriodSec(60);
        cfg.setWidePeriodMin(10);
        cfg.setTimeStopMin(30);
        return cfg;
    }

    private PositionEntity buildPos(String symbol, double avgPrice, int qty, int splitPhase, Instant openedAt) {
        PositionEntity pe = new PositionEntity();
        pe.setSymbol(symbol);
        pe.setQty(qty);
        pe.setAvgPrice(avgPrice);
        pe.setEntryStrategy("KRX_MORNING_RUSH");
        pe.setSplitPhase(splitPhase);
        pe.setOpenedAt(openedAt);
        return pe;
    }

    private void invokeMonitor(KrxMorningRushConfigEntity cfg) throws Exception {
        Method m = KrxMorningRushService.class.getDeclaredMethod(
                "monitorPositions", KrxMorningRushConfigEntity.class);
        m.setAccessible(true);
        m.invoke(service, cfg);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, double[]> getCache() throws Exception {
        Field f = KrxMorningRushService.class.getDeclaredField("positionCache");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, double[]>) f.get(service);
    }

    private boolean hasSellDecision(String symbol) {
        List<Map<String, Object>> decisions = service.getRecentDecisions(100);
        for (Map<String, Object> d : decisions) {
            if (symbol.equals(d.get("symbol")) && "SELL".equals(d.get("action"))
                    && "EXECUTED".equals(d.get("state"))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasDecisionWithReasonCode(String symbol, String reasonCode) {
        List<Map<String, Object>> decisions = service.getRecentDecisions(100);
        for (Map<String, Object> d : decisions) {
            if (symbol.equals(d.get("symbol")) && reasonCode.equals(d.get("reasonCode"))) {
                return true;
            }
        }
        return false;
    }

    private void setField(String name, Object value) throws Exception {
        Field f = KrxMorningRushService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }
}
