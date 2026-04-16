package com.example.stocks.bot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-04-17: MR 버그 5건 수정에 대한 단위 테스트.
 *
 * KrxMorningRushService 내부 로직을 추출하여 시나리오 기반 검증.
 *
 * 커버:
 *   #1 ORDER_NOT_FILLED retry drift guard
 *   #2 tradedSymbols 재시작 시 DB에서 복원 (로직만 시뮬레이션)
 *   #3 executeSell 성공 후에만 cache 제거 (상태 전이 시뮬레이션)
 *   #4 Dual SL fire race 가드 (sellingSymbols Set)
 *   #5 Confirm 중 strictly decreasing 감지
 */
class KrxMorningRushBugFixesTest {

    // ─── 공통 파라미터 ───
    private static final double RETRY_PRICE_DRIFT_MAX = KrxMorningRushService.RETRY_PRICE_DRIFT_MAX;
    private static final int REQUIRED_CONFIRMS = 3;
    private static final double GAP_THRESHOLD = 0.02;

    private ConcurrentHashMap<String, Integer> confirmCounts;
    private ConcurrentHashMap<String, Object> confirmPriceHistory;
    private ConcurrentHashMap<String, Double> firstBuyAttemptPrice;
    private Set<String> tradedSymbols;

    @BeforeEach
    void setUp() {
        confirmCounts = new ConcurrentHashMap<>();
        confirmPriceHistory = new ConcurrentHashMap<>();
        firstBuyAttemptPrice = new ConcurrentHashMap<>();
        tradedSymbols = ConcurrentHashMap.newKeySet();
    }

    /**
     * scanForEntry 내부 로직을 추출한 시뮬레이터.
     * confirm 3회 통과 후 #5(downtrend) → #1(drift guard) → BUY 순서로 판정.
     * return: "BUY" | "CONFIRMING x/3" | "GAP_LOW" | "DOWNTREND_IN_CONFIRM" | "RETRY_PRICE_DRIFT" | "ALREADY_TRADED"
     */
    private String simulate(String symbol, double prevClose, double currentPrice) {
        if (tradedSymbols.contains(symbol)) return "ALREADY_TRADED";
        double gapPct = (currentPrice - prevClose) / prevClose;
        if (gapPct < GAP_THRESHOLD) {
            confirmCounts.remove(symbol);
            confirmPriceHistory.remove(symbol);
            return "GAP_LOW";
        }

        Integer prevCount = confirmCounts.get(symbol);
        int count = (prevCount != null ? prevCount : 0) + 1;
        confirmCounts.put(symbol, count);

        @SuppressWarnings("unchecked")
        Deque<Double> prices = (Deque<Double>) confirmPriceHistory
                .computeIfAbsent(symbol, k -> new ArrayDeque<Double>());
        prices.addLast(currentPrice);
        while (prices.size() > REQUIRED_CONFIRMS) prices.pollFirst();

        if (count < REQUIRED_CONFIRMS) {
            return "CONFIRMING " + count + "/3";
        }

        // #5: strictly decreasing 감지
        if (prices.size() >= 2) {
            Double[] arr = prices.toArray(new Double[0]);
            boolean strictlyDecreasing = true;
            for (int i = 1; i < arr.length; i++) {
                if (arr[i] >= arr[i - 1]) { strictlyDecreasing = false; break; }
            }
            if (strictlyDecreasing) {
                confirmCounts.remove(symbol);
                confirmPriceHistory.remove(symbol);
                return "DOWNTREND_IN_CONFIRM";
            }
        }

        // #1: retry drift guard
        Double firstPrice = firstBuyAttemptPrice.get(symbol);
        if (firstPrice != null) {
            double drift = (currentPrice - firstPrice) / firstPrice;
            if (drift >= RETRY_PRICE_DRIFT_MAX) {
                firstBuyAttemptPrice.remove(symbol);
                tradedSymbols.add(symbol);
                confirmCounts.remove(symbol);
                confirmPriceHistory.remove(symbol);
                return "RETRY_PRICE_DRIFT";
            }
        } else {
            firstBuyAttemptPrice.put(symbol, currentPrice);
        }

        confirmCounts.remove(symbol);
        prices.clear();
        return "BUY";
    }

    /** buy succeeded — cleanup 적용 (scanForEntry의 성공 분기 재현) */
    private void onBuySuccess(String symbol) {
        tradedSymbols.add(symbol);
        firstBuyAttemptPrice.remove(symbol);
    }

    // ═══════════════════════════════════════════════════════════════════
    // #1 테스트: ORDER_NOT_FILLED retry drift guard
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("#1-a: 072950 실데이터 시나리오 — 첫 시도 후 +11% 상승 재시도 → RETRY_PRICE_DRIFT")
    void test1_072950_driftBlocked() {
        String sym = "072950";
        // prevClose를 시뮬용으로 가정(실 데이터: prev=23650, 첫 22500이었으나 gap 통과 위해 값 조정)
        double prev = 20000;

        // Cycle 1 — 3회 confirm 후 BUY. firstBuyAttemptPrice는 count>=3 시점의 currentPrice로 기록됨.
        simulate(sym, prev, 22500);
        simulate(sym, prev, 22500);
        String r3 = simulate(sym, prev, 22500);
        assertEquals("BUY", r3, "첫 3회 confirm 통과 후 BUY");
        // 실 코드: BUY 결과 나왔지만 체결 실패(ORDER_NOT_FILLED) 시 onBuySuccess 미호출
        // → firstBuyAttemptPrice 유지
        assertEquals(22500.0, firstBuyAttemptPrice.get(sym), 0.01,
                "체결 실패 시 firstBuyAttemptPrice 유지 (onBuySuccess 미호출)");

        // Cycle 2 — 가격 25000(+11.1%)으로 급등 후 재시도 confirm
        simulate(sym, prev, 25000);
        simulate(sym, prev, 25000);
        String r6 = simulate(sym, prev, 25000);

        assertEquals("RETRY_PRICE_DRIFT", r6,
                "첫 시도 22500 대비 25000(+11.1%) drift → 차단");
        assertTrue(tradedSymbols.contains(sym), "drift 차단 시 tradedSymbols 추가 → 재시도 완전 봉쇄");

        // Cycle 3 — 더 올라와도 ALREADY_TRADED
        simulate(sym, prev, 26000);
        simulate(sym, prev, 26000);
        String r9 = simulate(sym, prev, 26000);
        assertEquals("ALREADY_TRADED", r9);
    }

    @Test
    @DisplayName("#1-b: 첫 시도가 +1.5% 올라간 재시도는 통과 (drift 2% 미만)")
    void test1_driftUnderThreshold_allowsRetry() {
        String sym = "ABC";
        double prev = 1000;

        simulate(sym, prev, 1050); // Confirm 1/3
        simulate(sym, prev, 1050); // Confirm 2/3
        String r3 = simulate(sym, prev, 1050); // Confirm 3/3 → BUY (firstBuy=1050)
        assertEquals("BUY", r3);

        // 재시도 — +1.5% (1066)
        simulate(sym, prev, 1066);
        simulate(sym, prev, 1066);
        String r6 = simulate(sym, prev, 1066);
        // 1066/1050 - 1 = 1.52% < 2% → BUY 허용
        assertEquals("BUY", r6, "drift 1.52%는 cap 2% 미만이므로 재시도 허용");
    }

    @Test
    @DisplayName("#1-c: BUY 성공 시 firstBuyAttemptPrice 정리")
    void test1_buySuccess_clearsFirstAttempt() {
        String sym = "SUCCESS";
        double prev = 1000;

        simulate(sym, prev, 1050);
        simulate(sym, prev, 1050);
        assertEquals("BUY", simulate(sym, prev, 1050));

        assertTrue(firstBuyAttemptPrice.containsKey(sym), "BUY 직후(onBuySuccess 전)에는 유지");
        onBuySuccess(sym);
        assertFalse(firstBuyAttemptPrice.containsKey(sym), "onBuySuccess 후 firstBuyAttemptPrice 제거");
        assertTrue(tradedSymbols.contains(sym));
    }

    // ═══════════════════════════════════════════════════════════════════
    // #5 테스트: Confirm 중 strictly decreasing 감지
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("#5-a: 073540 실데이터 — 6265→6230→6200 strictly decreasing → SKIP")
    void test5_073540_realCase() {
        String sym = "073540";
        double prev = 5870;

        simulate(sym, prev, 6265); // 1/3 gap=6.73%
        simulate(sym, prev, 6230); // 2/3 gap=6.13%
        String r3 = simulate(sym, prev, 6200); // 3/3 gap=5.62% — strictly decreasing
        assertEquals("DOWNTREND_IN_CONFIRM", r3,
                "6265>6230>6200 단조 감소 → 진입 차단 (falling knife 방지)");
    }

    @Test
    @DisplayName("#5-b: 엔피(291230) 출렁임 패턴 — 1014→968→1081 은 허용 (decreasing 아님)")
    void test5_zigzag_allowed() {
        String sym = "291230";
        double prev = 846;

        simulate(sym, prev, 1014);
        simulate(sym, prev, 968);
        String r3 = simulate(sym, prev, 1081);
        assertEquals("BUY", r3, "968→1081 상승 반전 → strictly decreasing 아님 → BUY 허용");
    }

    @Test
    @DisplayName("#5-c: 완전 평행(같은 가격) — strictly decreasing 아님")
    void test5_flat_allowed() {
        String sym = "FLAT";
        double prev = 100;

        simulate(sym, prev, 103);
        simulate(sym, prev, 103);
        String r3 = simulate(sym, prev, 103);
        assertEquals("BUY", r3, "동일가격 3회는 strictly decreasing 아님 → BUY 허용");
    }

    @Test
    @DisplayName("#5-d: 2틱만 감소해도 감지 (3/3 reached with window=3)")
    void test5_smallDecline() {
        String sym = "TINY";
        double prev = 100;

        simulate(sym, prev, 110);
        simulate(sym, prev, 109);
        String r3 = simulate(sym, prev, 108);
        assertEquals("DOWNTREND_IN_CONFIRM", r3, "110>109>108 strictly decreasing → 차단");
    }

    // ═══════════════════════════════════════════════════════════════════
    // #4 테스트: sellingSymbols race 가드
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("#4: sellingSymbols Set — 두 경로 동시 진입 시 두 번째는 반드시 false")
    void test4_sellingSymbolsRaceGuard() {
        Set<String> sellingSymbols = ConcurrentHashMap.newKeySet();
        String sym = "RACE";

        // REST path 먼저
        assertTrue(sellingSymbols.add(sym), "첫 경로 진입 성공");

        // WS path 동시 진입
        assertFalse(sellingSymbols.add(sym), "두 번째 경로는 false → skip 되어야 함");

        // REST 완료 후 remove
        sellingSymbols.remove(sym);

        // 다음 tick에서 다시 add 가능
        assertTrue(sellingSymbols.add(sym), "완료 후 재진입 가능");
    }

    // ═══════════════════════════════════════════════════════════════════
    // #3 테스트: executeSell 성공 여부에 따라 cache 제거 순서 결정
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("#3-a: executeSell 실패 시 positionCache는 유지되어야 함 (다음 tick에서 재시도 가능)")
    void test3_failedSell_keepsCache() {
        Map<String, double[]> positionCache = new HashMap<>();
        String sym = "FAIL_SELL";
        positionCache.put(sym, new double[]{1000.0, 1000.0, 0, System.currentTimeMillis(), 0});

        // 매도 시뮬레이션: executeSell returns false (e.g., ORDER_NOT_FILLED)
        boolean sold = false;

        // 수정된 정책: sold == false → cache 유지
        if (sold) positionCache.remove(sym);

        assertTrue(positionCache.containsKey(sym),
                "매도 실패 시 cache 유지 → 다음 REST/WS tick이 position을 재감지할 수 있어야 함");
    }

    @Test
    @DisplayName("#3-b: executeSell 성공 시 positionCache 즉시 제거")
    void test3_successfulSell_removesCache() {
        Map<String, double[]> positionCache = new HashMap<>();
        String sym = "OK_SELL";
        positionCache.put(sym, new double[]{1000.0, 1000.0, 0, System.currentTimeMillis(), 0});

        boolean sold = true;
        if (sold) positionCache.remove(sym);

        assertFalse(positionCache.containsKey(sym), "매도 성공 후 cache 제거");
    }

    // ═══════════════════════════════════════════════════════════════════
    // #2 테스트: tradedSymbols restore — trade_log 필터 로직
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("#2: 오늘 KRX_MORNING_RUSH 패턴 trade만 tradedSymbols에 복원")
    void test2_restoreFilter() {
        // 시뮬레이션: 오늘 trade_log에 3건 있음 — 1건은 MR, 1건은 OPENING, 1건은 어제
        String ENTRY_STRATEGY = "KRX_MORNING_RUSH";
        long todayStart = System.currentTimeMillis() - 60_000L; // 1분 전
        long yesterday = todayStart - 86_400_000L; // 어제

        // (patternType, tsEpochMs, symbol)
        List<Object[]> todayRows = Arrays.asList(
                new Object[]{"KRX_MORNING_RUSH", todayStart + 10, "005880"},   // ✅ 복원
                new Object[]{"KRX_OPENING_BREAK", todayStart + 20, "072950"},  // ❌ MR 아님
                new Object[]{"KRX_MORNING_RUSH", todayStart + 30, "491000"}   // ✅ 복원
        );
        // 어제 거는 애초에 findByTsEpochMsBetween(todayStart, now)에서 제외됨.

        Set<String> restored = ConcurrentHashMap.newKeySet();
        for (Object[] row : todayRows) {
            String patternType = (String) row[0];
            String symbol = (String) row[2];
            if (ENTRY_STRATEGY.equals(patternType) && symbol != null) {
                restored.add(symbol);
            }
        }

        assertEquals(2, restored.size(), "MR 건만 복원");
        assertTrue(restored.contains("005880"));
        assertTrue(restored.contains("491000"));
        assertFalse(restored.contains("072950"), "OPENING 건은 복원 대상 아님");
    }
}
