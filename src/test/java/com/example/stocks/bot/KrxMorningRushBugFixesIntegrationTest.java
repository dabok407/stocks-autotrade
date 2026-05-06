package com.example.stocks.bot;

import com.example.stocks.db.*;
import com.example.stocks.exchange.ExchangeAdapter;
import com.example.stocks.kis.KisPublicClient;
import com.example.stocks.kis.KisWebSocketClient;
import com.example.stocks.market.CandleService;
import com.example.stocks.market.MarketType;
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
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2026-04-17: MR 버그 5건 + confirmPriceHistory 누락 수정분에 대한
 * "실제 Service 인스턴스" 기반 통합 테스트.
 *
 * KrxMorningRushBugFixesTest(시뮬레이션 기반)와 달리, 실제 KrxMorningRushService
 * 인스턴스를 생성하고 private 메서드를 reflection으로 호출하여 프로덕션 코드 경로를 검증.
 *
 * 커버:
 *   #1 scanForEntry RETRY_PRICE_DRIFT 실제 decision 기록 검증
 *   #2 restoreTradedSymbolsFromDb 실제 호출 + TradeRepository mock 필터링 검증
 *   #3 executeSell boolean 반환 (ORDER_NOT_FILLED / DB_COMMIT_FAIL / success)
 *   #5 scanForEntry DOWNTREND_IN_CONFIRM 실제 decision 기록 검증
 *   confirmPriceHistory.clear() — start() 호출 시 실제 clear 되는지 검증
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class KrxMorningRushBugFixesIntegrationTest {

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

        // 기본 capital 500,000,000 — orderSize 5% = 25,000,000 (50,000 최소 통과)
        BotConfigEntity bot = new BotConfigEntity();
        bot.setCapitalKrw(BigDecimal.valueOf(500_000_000L));
        when(botConfigRepo.findAll()).thenReturn(Collections.singletonList(bot));

        // tradeLogRepo.findByTsEpochMsBetween 기본 empty (각 테스트에서 override)
        when(tradeLogRepo.findByTsEpochMsBetween(anyLong(), anyLong()))
                .thenReturn(Collections.<TradeEntity>emptyList());
    }

    // ═══════════════════════════════════════════════════════════
    // #2: restoreTradedSymbolsFromDb — @PostConstruct 실제 호출
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("#2-a: @PostConstruct restore — KRX_MORNING_RUSH 패턴 trade만 tradedSymbols에 복원")
    public void test2_restore_filtersMrPatternOnly() throws Exception {
        TradeEntity t1 = new TradeEntity();
        t1.setSymbol("005880");
        t1.setPatternType("KRX_MORNING_RUSH");
        t1.setTsEpochMs(System.currentTimeMillis());

        TradeEntity t2 = new TradeEntity();
        t2.setSymbol("072950");
        t2.setPatternType("KRX_OPENING_BREAK"); // 다른 패턴 — 복원 대상 아님
        t2.setTsEpochMs(System.currentTimeMillis());

        TradeEntity t3 = new TradeEntity();
        t3.setSymbol("491000");
        t3.setPatternType("KRX_MORNING_RUSH");
        t3.setTsEpochMs(System.currentTimeMillis());

        when(tradeLogRepo.findByTsEpochMsBetween(anyLong(), anyLong()))
                .thenReturn(Arrays.asList(t1, t2, t3));

        // 실제 @PostConstruct 메서드 직접 호출
        service.restoreTradedSymbolsFromDb();

        Set<String> traded = getTradedSymbols();
        assertEquals(2, traded.size(), "MR 2건만 복원");
        assertTrue(traded.contains("005880"));
        assertTrue(traded.contains("491000"));
        assertFalse(traded.contains("072950"), "OPENING 건은 복원 대상 아님");
    }

    @Test
    @DisplayName("#2-b: restore — TradeRepository findByTsEpochMsBetween 인자는 오늘 KST 00:00 ~ now")
    public void test2_restore_usesTodayKstRange() throws Exception {
        service.restoreTradedSymbolsFromDb();

        org.mockito.ArgumentCaptor<Long> fromCap = org.mockito.ArgumentCaptor.forClass(Long.class);
        org.mockito.ArgumentCaptor<Long> toCap = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(tradeLogRepo).findByTsEpochMsBetween(fromCap.capture(), toCap.capture());

        long from = fromCap.getValue();
        long to = toCap.getValue();
        long now = System.currentTimeMillis();
        // from은 오늘 KST 00:00 — 현재 시각 기준 24시간 이내
        assertTrue(from <= now, "from <= now");
        assertTrue(now - from <= 25L * 60 * 60 * 1000, "from은 최근 25시간 이내 (KST 타임존 버퍼)");
        // to는 호출 시점의 now
        assertTrue(Math.abs(to - now) < 5000, "to는 현재 시각 (5초 오차)");
    }

    @Test
    @DisplayName("#2-c: restore — symbol이 null인 trade는 스킵")
    public void test2_restore_skipsNullSymbol() throws Exception {
        TradeEntity t1 = new TradeEntity();
        t1.setSymbol(null);
        t1.setPatternType("KRX_MORNING_RUSH");
        t1.setTsEpochMs(System.currentTimeMillis());

        TradeEntity t2 = new TradeEntity();
        t2.setSymbol("005880");
        t2.setPatternType("KRX_MORNING_RUSH");
        t2.setTsEpochMs(System.currentTimeMillis());

        when(tradeLogRepo.findByTsEpochMsBetween(anyLong(), anyLong()))
                .thenReturn(Arrays.asList(t1, t2));

        service.restoreTradedSymbolsFromDb();

        Set<String> traded = getTradedSymbols();
        assertEquals(1, traded.size());
        assertTrue(traded.contains("005880"));
    }

    // ═══════════════════════════════════════════════════════════
    // #3: executeSell boolean 반환 경로 3종 (ORDER_NOT_FILLED / DB_COMMIT_FAIL / success)
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("#3-a: executeSell LIVE mode + placeSellOrder.isFilled()=false → false 반환, cache 유지")
    public void test3_executeSell_orderNotFilled_returnsFalse() throws Exception {
        cfg.setMode("LIVE");
        when(liveOrders.isConfigured()).thenReturn(true);

        // placeSellOrder는 state="reject"로 반환 → isFilled()=false
        LiveOrderService.LiveOrderResult notFilled = new LiveOrderService.LiveOrderResult(
                "ID", "ORD", "reject", 0, 0.0);
        when(liveOrders.placeSellOrder(anyString(), any(MarketType.class), anyInt(), anyDouble(), anyString()))
                .thenReturn(notFilled);

        PositionEntity pe = buildPosition("005930", 10, 70000.0);
        // cache에 미리 등록 — 호출 후 유지 검증
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        cache.put("005930", new double[]{70000.0, 70000.0, 0, System.currentTimeMillis(), 0});

        boolean sold = invokeExecuteSell(pe, 71000.0, "TEST");

        assertFalse(sold, "주문 미체결 → executeSell 은 false 반환");
        assertTrue(cache.containsKey("005930"),
                "실패 시 호출자가 cache 제거하지 않아야 함 (재시도 가능하도록)");
        verifyNoInteractions(txTemplate); // DB 커밋 시도 없어야 함
    }

    @Test
    @DisplayName("#3-b: executeSell LIVE mode + txTemplate.execute throws → false + DB_COMMIT_FAIL decision")
    public void test3_executeSell_dbCommitFails_returnsFalse() throws Exception {
        cfg.setMode("LIVE");
        when(liveOrders.isConfigured()).thenReturn(true);

        LiveOrderService.LiveOrderResult filled = new LiveOrderService.LiveOrderResult(
                "ID", "ORD", "done", 10, 71000.0);
        when(liveOrders.placeSellOrder(anyString(), any(MarketType.class), anyInt(), anyDouble(), anyString()))
                .thenReturn(filled);

        // DB 커밋 실패 시뮬레이션
        when(txTemplate.execute(any())).thenThrow(new RuntimeException("DB commit fail"));

        PositionEntity pe = buildPosition("005930", 10, 70000.0);
        ConcurrentHashMap<String, double[]> cache = getPositionCache();
        cache.put("005930", new double[]{70000.0, 70000.0, 0, System.currentTimeMillis(), 0});

        boolean sold = invokeExecuteSell(pe, 71000.0, "TEST");

        assertFalse(sold, "DB commit 실패 → executeSell 은 false 반환");
        assertTrue(cache.containsKey("005930"), "DB 실패 시 cache 유지 (주문은 이미 체결됨 — 다음 reconcile 필요)");

        // DB_COMMIT_FAIL decision이 decisionLog에 기록됐는지 확인
        List<Map<String, Object>> decisions = service.getRecentDecisions(10);
        boolean foundDbFail = false;
        for (Map<String, Object> d : decisions) {
            if ("DB_COMMIT_FAIL".equals(d.get("reasonCode"))) { foundDbFail = true; break; }
        }
        assertTrue(foundDbFail, "DB_COMMIT_FAIL decision 기록 확인");
    }

    @Test
    @DisplayName("#3-c: executeSell PAPER mode + 정상 → true, txTemplate 호출")
    public void test3_executeSell_paperSuccess_returnsTrue() throws Exception {
        cfg.setMode("PAPER");
        // txTemplate.execute 는 PAPER 모드에서도 호출됨 — 기본 mock은 null 반환(정상 동작)

        PositionEntity pe = buildPosition("005930", 10, 70000.0);

        boolean sold = invokeExecuteSell(pe, 71000.0, "TEST");

        assertTrue(sold, "PAPER 성공 시 true 반환");
        verify(txTemplate).execute(any()); // DB 저장 시도됨
    }

    @Test
    @DisplayName("#3-d: executeSell LIVE + API 키 미설정 → false, 주문/DB 호출 없음")
    public void test3_executeSell_liveApiNotConfigured_returnsFalse() throws Exception {
        cfg.setMode("LIVE");
        when(liveOrders.isConfigured()).thenReturn(false);

        PositionEntity pe = buildPosition("005930", 10, 70000.0);

        boolean sold = invokeExecuteSell(pe, 71000.0, "TEST");

        assertFalse(sold);
        verify(liveOrders, never()).placeSellOrder(anyString(), any(MarketType.class), anyInt(), anyDouble(), anyString());
        verifyNoInteractions(txTemplate);
    }

    // ═══════════════════════════════════════════════════════════
    // #5: scanForEntry DOWNTREND_IN_CONFIRM 실제 decision 기록
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("#5-a: 실제 scanForEntry 3회 호출 (6265→6230→6200) → DOWNTREND_IN_CONFIRM decision")
    public void test5_scanForEntry_strictlyDecreasing_recordsSkip() throws Exception {
        String sym = "073540";
        double prev = 5870;
        getPrevCloseMap().put(sym, prev);

        when(kisWs.getLatestPrice(sym)).thenReturn(6265.0, 6230.0, 6200.0);

        invokeScanForEntry();
        invokeScanForEntry();
        invokeScanForEntry();

        String lastReasonCode = latestReasonCodeFor(sym);
        assertEquals("DOWNTREND_IN_CONFIRM", lastReasonCode,
                "confirm 3/3 strictly decreasing → DOWNTREND_IN_CONFIRM 기록");
    }

    @Test
    @DisplayName("#5-b: zigzag (1014→968→1081) 은 decreasing 아님 → DOWNTREND_IN_CONFIRM 기록 없음")
    public void test5_scanForEntry_zigzag_doesNotTriggerDowntrend() throws Exception {
        String sym = "291230";
        double prev = 846;
        getPrevCloseMap().put(sym, prev);

        when(kisWs.getLatestPrice(sym)).thenReturn(1014.0, 968.0, 1081.0);

        invokeScanForEntry();
        invokeScanForEntry();
        invokeScanForEntry();

        // zigzag는 DOWNTREND_IN_CONFIRM을 발생시키지 않아야 함
        assertFalse(hasReasonCode(sym, "DOWNTREND_IN_CONFIRM"),
                "968→1081 상승 반전 — strictly decreasing 아님");
    }

    // ═══════════════════════════════════════════════════════════
    // #1: scanForEntry RETRY_PRICE_DRIFT 실제 decision 기록
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("#1-a: firstBuyAttemptPrice 기록 상태 + 가격 +2.5% 재시도 → RETRY_PRICE_DRIFT")
    public void test1_scanForEntry_retryDriftBlocked() throws Exception {
        // V39 R5 (2026-04-18): gap 과열(>=10%) 차단 도입으로 prices 재설정.
        //   기존: prev=20000, first=22500, now=25000 → gap 25% → GAP_OVERHEATED 로 먼저 걸림.
        //   변경: prev=20000, first=20500, now=21000 → gap 5% (통과), drift +2.44% → RETRY_PRICE_DRIFT.
        String sym = "072950";
        double prev = 20000;
        getPrevCloseMap().put(sym, prev);

        // 이전 사이클에서 첫 시도가 20500에 일어났다고 가정 (gap 2.5%, threshold 2% 통과)
        getFirstBuyAttemptPrice().put(sym, 20500.0);

        // 재시도 — 21000 (prev 대비 +5%, first 대비 +2.44%) → drift 2% 초과로 차단
        when(kisWs.getLatestPrice(sym)).thenReturn(21000.0, 21000.0, 21000.0);

        invokeScanForEntry();
        invokeScanForEntry();
        invokeScanForEntry();

        assertEquals("RETRY_PRICE_DRIFT", latestReasonCodeFor(sym),
                "첫 시도 20500 대비 21000(+2.44%) drift → 차단");

        // RETRY_PRICE_DRIFT 발생 시 tradedSymbols.add로 재시도 완전 봉쇄
        assertTrue(getTradedSymbols().contains(sym),
                "RETRY_PRICE_DRIFT 후 tradedSymbols 추가 → 당일 재시도 완전 봉쇄");
        // firstBuyAttemptPrice는 정리됨
        assertFalse(getFirstBuyAttemptPrice().containsKey(sym));
    }

    @Test
    @DisplayName("#1-b: firstBuyAttemptPrice 없는 첫 사이클 — 첫 시도 가격 기록만 하고 통과")
    public void test1_scanForEntry_firstCycle_recordsAttemptPrice() throws Exception {
        String sym = "ABC";
        double prev = 1000;
        getPrevCloseMap().put(sym, prev);

        // 첫 confirm 사이클 (3회 모두 1050) — firstBuyAttemptPrice 비어있음
        when(kisWs.getLatestPrice(sym)).thenReturn(1050.0, 1050.0, 1050.0);

        invokeScanForEntry();
        invokeScanForEntry();
        invokeScanForEntry();

        // drift guard 자체는 통과 → firstBuyAttemptPrice에 기록됐거나 BUY 시도 단계로 진입했음
        // (BUY 실행은 PAPER 모드에서도 orderSize/capital 등 다른 검사 필요하므로
        //  RETRY_PRICE_DRIFT로 막히지 않은 것만 검증 — 핵심은 drift 차단이 걸리지 않았다는 것)
        assertFalse(hasReasonCode(sym, "RETRY_PRICE_DRIFT"),
                "첫 사이클(firstBuyAttemptPrice 미존재)은 drift 차단 걸리면 안 됨");
    }

    // ═══════════════════════════════════════════════════════════
    // confirmPriceHistory.clear() — start() 호출 시 실제 clear
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("start() 호출 시 confirmPriceHistory clear — 어제 이력 잔존 방지")
    public void test_start_clearsConfirmPriceHistory() throws Exception {
        // 이전 사이클 잔존 데이터 주입
        ConcurrentHashMap<String, Object> history = getConfirmPriceHistory();
        history.put("YESTERDAY_SYM", new ArrayDeque<Double>(Arrays.asList(1000.0, 1001.0)));
        assertFalse(history.isEmpty());

        try {
            service.start();
            assertTrue(history.isEmpty(), "start() 호출 시 confirmPriceHistory 가 clear 되어야 함");
        } finally {
            // start() 는 scheduler/WebSocket listener 를 띄우므로 반드시 stop()
            service.stop();
        }
    }

    @Test
    @DisplayName("Phase.IDLE → COLLECTING_RANGE 전환 로직이 confirmPriceHistory clear 포함")
    public void test_idleToCollecting_codePathClearsHistory() throws Exception {
        // 이 테스트는 mainLoop()를 직접 호출하지 않고,
        // "IDLE → COLLECTING_RANGE 전환 블록에서 confirmPriceHistory.clear()가 호출되는지"
        // 를 소스 정적 검증으로 확인 (runtime 전환은 ZonedDateTime 의존으로 직접 호출 불가).
        //
        // KrxMorningRushService.java 에 실제로 `confirmPriceHistory.clear()` 가
        // COLLECTING_RANGE 전환 블록 안에 존재하는지 정적으로 체크.
        String src = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/com/example/stocks/bot/KrxMorningRushService.java")));
        // "IDLE → COLLECTING_RANGE" 블록 내부에 clear 호출 존재 확인
        int idleIdx = src.indexOf("currentPhase = Phase.COLLECTING_RANGE;");
        assertTrue(idleIdx > 0, "IDLE → COLLECTING_RANGE 전환 코드 존재");
        // 전환 직후 ~300자 내에 clear 호출이 있어야 함
        String nearby = src.substring(idleIdx, Math.min(src.length(), idleIdx + 500));
        assertTrue(nearby.contains("confirmPriceHistory.clear()"),
                "IDLE → COLLECTING_RANGE 블록 안에 confirmPriceHistory.clear() 호출 있어야 함");
    }

    // ═══════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════

    private KrxMorningRushConfigEntity buildConfig() {
        KrxMorningRushConfigEntity c = new KrxMorningRushConfigEntity();
        c.setEnabled(true);
        c.setMode("PAPER");
        c.setMaxPositions(3);
        c.setConfirmCount(3);
        c.setGapThresholdPct(BigDecimal.valueOf(2.0));
        c.setVolumeMult(BigDecimal.valueOf(1.0)); // 1.0 이하면 volume 체크 스킵
        c.setMinPriceKrw(100);
        c.setOrderSizingMode("FIXED");
        c.setOrderSizingValue(BigDecimal.valueOf(1_000_000L));
        c.setEntryDelaySec(30);
        c.setSessionEndHour(10);
        c.setSessionEndMin(0);
        return c;
    }

    private PositionEntity buildPosition(String symbol, int qty, double avgPrice) {
        PositionEntity pe = new PositionEntity();
        pe.setSymbol(symbol);
        pe.setMarketType("KRX");
        pe.setQty(qty);
        pe.setAvgPrice(avgPrice);
        pe.setEntryStrategy("KRX_MORNING_RUSH");
        pe.setOpenedAt(java.time.Instant.now());
        return pe;
    }

    @SuppressWarnings("unchecked")
    private Set<String> getTradedSymbols() throws Exception {
        Field f = KrxMorningRushService.class.getDeclaredField("tradedSymbols");
        f.setAccessible(true);
        return (Set<String>) f.get(service);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, Double> getPrevCloseMap() throws Exception {
        Field f = KrxMorningRushService.class.getDeclaredField("prevCloseMap");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, Double>) f.get(service);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, Double> getFirstBuyAttemptPrice() throws Exception {
        Field f = KrxMorningRushService.class.getDeclaredField("firstBuyAttemptPrice");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, Double>) f.get(service);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, Object> getConfirmPriceHistory() throws Exception {
        Field f = KrxMorningRushService.class.getDeclaredField("confirmPriceHistory");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, Object>) f.get(service);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, double[]> getPositionCache() throws Exception {
        Field f = KrxMorningRushService.class.getDeclaredField("positionCache");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, double[]>) f.get(service);
    }

    private boolean invokeExecuteSell(PositionEntity pe, double price, String reason) throws Exception {
        Method m = KrxMorningRushService.class.getDeclaredMethod("executeSell",
                PositionEntity.class, double.class,
                com.example.stocks.strategy.Signal.class, KrxMorningRushConfigEntity.class);
        m.setAccessible(true);
        com.example.stocks.strategy.Signal s = com.example.stocks.strategy.Signal.of(
                com.example.stocks.strategy.SignalAction.SELL, null, reason);
        return (Boolean) m.invoke(service, pe, price, s, cfg);
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
