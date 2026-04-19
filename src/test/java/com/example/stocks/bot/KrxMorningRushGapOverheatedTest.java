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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * V39 R5 (2026-04-18): Gap 과열 스킵 테스트.
 *
 * gap >= 10% 이면 GAP_OVERHEATED 로 매수 차단.
 * 2026-04-17 184230 gap 17.63% → SPLIT_1ST 반복 트리거 손실 케이스.
 *
 * 시나리오:
 *  R01 — gap 9.9% 통과 (과열 아님)
 *  R02 — gap 10% 정확히 차단
 *  R03 — gap 17.63% (184230 실제 손실 케이스) 차단
 *  R04 — GAP_OVERHEATED 발생 후 confirmCounts 정리
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class KrxMorningRushGapOverheatedTest {

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
    public void setUp() throws Exception {
        service = new KrxMorningRushService(
                configRepo, botConfigRepo, positionRepo, tradeLogRepo,
                liveOrders, tickerService, candleService, kisWs, txTemplate,
                exchangeAdapter, kisPublic, overtimeRankRepo, new KrxSharedTradeThrottle()
        );
        cfg = buildConfig();
        when(configRepo.loadOrCreate()).thenReturn(cfg);
        when(positionRepo.findAll()).thenReturn(Collections.emptyList());

        BotConfigEntity bot = new BotConfigEntity();
        bot.setCapitalKrw(BigDecimal.valueOf(500_000_000L));
        when(botConfigRepo.findAll()).thenReturn(Collections.singletonList(bot));

        when(tradeLogRepo.findByTsEpochMsBetween(anyLong(), anyLong()))
                .thenReturn(Collections.<TradeEntity>emptyList());
    }

    @Test
    @DisplayName("R01: gap 9.9% — GAP_OVERHEATED 기록 없음")
    public void r01_gapBelow10Pct_notBlocked() throws Exception {
        String sym = "ABC";
        getPrevCloseMap().put(sym, 1000.0);
        when(kisWs.getLatestPrice(sym)).thenReturn(1099.0); // gap 9.9%

        invokeScanForEntry();

        assertFalse(hasReasonCode(sym, "GAP_OVERHEATED"),
                "gap 9.9% 는 과열 임계(10%) 미만 — 통과해야 함");
    }

    @Test
    @DisplayName("R02: gap 10% 정확히 — GAP_OVERHEATED 차단")
    public void r02_gapExactly10Pct_blocked() throws Exception {
        String sym = "DEF";
        getPrevCloseMap().put(sym, 1000.0);
        when(kisWs.getLatestPrice(sym)).thenReturn(1100.0); // gap 10.0%

        invokeScanForEntry();

        assertEquals("GAP_OVERHEATED", latestReasonCodeFor(sym),
                "gap 10% 정확히 과열 임계 차단");
    }

    @Test
    @DisplayName("R03: 184230 실제 케이스 — gap 17.63% → 차단")
    public void r03_realLossCase_184230() throws Exception {
        String sym = "184230";
        getPrevCloseMap().put(sym, 913.0);
        when(kisWs.getLatestPrice(sym)).thenReturn(1074.0); // gap 17.63%

        invokeScanForEntry();

        assertEquals("GAP_OVERHEATED", latestReasonCodeFor(sym),
                "2026-04-17 184230 손실 케이스 — 차단되어야 함");
    }

    @Test
    @DisplayName("R04: GAP_OVERHEATED 발생 시 confirmCounts 정리")
    public void r04_overheatedClearsConfirmCounts() throws Exception {
        String sym = "GHI";
        getPrevCloseMap().put(sym, 1000.0);
        // 먼저 confirm 1회 누적 (정상 gap)
        getConfirmCounts().put(sym, 1);
        when(kisWs.getLatestPrice(sym)).thenReturn(1200.0); // gap 20%

        invokeScanForEntry();

        assertEquals("GAP_OVERHEATED", latestReasonCodeFor(sym));
        assertFalse(getConfirmCounts().containsKey(sym),
                "GAP_OVERHEATED 차단 시 confirmCounts 정리됨");
    }

    // ═══════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════

    private KrxMorningRushConfigEntity buildConfig() {
        KrxMorningRushConfigEntity c = new KrxMorningRushConfigEntity();
        c.setEnabled(true);
        c.setMode("PAPER");
        c.setMaxPositions(3);
        c.setConfirmCount(1); // 1회로 바로 진입
        c.setGapThresholdPct(BigDecimal.valueOf(2.0));
        c.setVolumeMult(BigDecimal.valueOf(1.0));
        c.setMinPriceKrw(100);
        c.setOrderSizingMode("FIXED");
        c.setOrderSizingValue(BigDecimal.valueOf(1_000_000L));
        c.setEntryDelaySec(30);
        c.setSessionEndHour(10);
        c.setSessionEndMin(0);
        return c;
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, Double> getPrevCloseMap() throws Exception {
        Field f = KrxMorningRushService.class.getDeclaredField("prevCloseMap");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, Double>) f.get(service);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, Integer> getConfirmCounts() throws Exception {
        Field f = KrxMorningRushService.class.getDeclaredField("confirmCounts");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, Integer>) f.get(service);
    }

    private void invokeScanForEntry() throws Exception {
        Method m = KrxMorningRushService.class.getDeclaredMethod("scanForEntry",
                KrxMorningRushConfigEntity.class);
        m.setAccessible(true);
        m.invoke(service, cfg);
    }

    private String latestReasonCodeFor(String symbol) {
        List<Map<String, Object>> decisions = service.getRecentDecisions(50);
        for (Map<String, Object> d : decisions) {
            if (symbol.equals(d.get("symbol"))) {
                return (String) d.get("reasonCode");
            }
        }
        return null;
    }

    private boolean hasReasonCode(String symbol, String reasonCode) {
        List<Map<String, Object>> decisions = service.getRecentDecisions(50);
        for (Map<String, Object> d : decisions) {
            if (symbol.equals(d.get("symbol")) && reasonCode.equals(d.get("reasonCode"))) {
                return true;
            }
        }
        return false;
    }
}
