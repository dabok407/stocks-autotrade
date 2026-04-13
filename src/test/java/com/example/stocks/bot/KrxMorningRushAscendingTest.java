package com.example.stocks.bot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 4/10 엔피(291230) 실거래 데이터 기반 ascending check 시나리오 테스트.
 *
 * 문제: 엔피가 prevClose 846 → 시가 922 (+8.98% gap)으로 gap 통과했지만,
 *       confirm 3회 ascending check에서 가격 등락(1014→968→1081)으로 NOT_ASCENDING 리셋 반복.
 *       10분 entry window 내 매수 불가.
 *
 * 이 테스트는 scanForEntry의 confirm + ascending 로직을 추출하여
 * 실제 가격 데이터로 검증합니다.
 */
class KrxMorningRushAscendingTest {

    // scanForEntry의 confirm + ascending 로직 시뮬레이션
    private ConcurrentHashMap<String, Integer> confirmCounts;
    private ConcurrentHashMap<String, Object> confirmPriceHistory;
    private double prevClose;
    private double gapThreshold;
    private int requiredConfirms;

    @BeforeEach
    void setUp() {
        confirmCounts = new ConcurrentHashMap<>();
        confirmPriceHistory = new ConcurrentHashMap<>();
        gapThreshold = 0.02; // 2%
        requiredConfirms = 3;
    }

    /**
     * scanForEntry의 confirm + ascending 로직 (ascending 있는 버전).
     */
    private String simulateOneCheckWithAscending(String symbol, double currentPrice) {
        if (currentPrice <= 0) return "NO_PRICE";

        double gapPct = (currentPrice - prevClose) / prevClose;
        if (gapPct < gapThreshold) {
            confirmCounts.remove(symbol);
            confirmPriceHistory.remove(symbol);
            return "GAP_LOW gap=" + String.format("%.2f%%", gapPct * 100);
        }

        Integer prevCount = confirmCounts.get(symbol);
        int count = (prevCount != null ? prevCount : 0) + 1;
        confirmCounts.put(symbol, count);

        @SuppressWarnings("unchecked")
        Deque<Double> prices = (Deque<Double>) confirmPriceHistory
                .computeIfAbsent(symbol, k -> new ArrayDeque<Double>());
        prices.addLast(currentPrice);
        while (prices.size() > requiredConfirms) prices.pollFirst();

        if (count < requiredConfirms) {
            return "CONFIRMING " + count + "/" + requiredConfirms;
        }

        // ascending check (이전 로직)
        if (prices.size() >= requiredConfirms) {
            double lastPrice = prices.peekLast();
            boolean ascending = true;
            for (Double p : prices) {
                if (p == lastPrice) continue;
                if (lastPrice <= p) {
                    ascending = false;
                    break;
                }
            }
            if (!ascending) {
                confirmCounts.remove(symbol);
                prices.clear();
                return "NOT_ASCENDING";
            }
        }

        confirmCounts.remove(symbol);
        prices.clear();
        return "BUY";
    }

    /**
     * scanForEntry의 confirm만 (ascending 제거된 현재 버전).
     */
    private String simulateOneCheck(String symbol, double currentPrice) {
        if (currentPrice <= 0) return "NO_PRICE";

        double gapPct = (currentPrice - prevClose) / prevClose;
        if (gapPct < gapThreshold) {
            confirmCounts.remove(symbol);
            confirmPriceHistory.remove(symbol);
            return "GAP_LOW gap=" + String.format("%.2f%%", gapPct * 100);
        }

        Integer prevCount = confirmCounts.get(symbol);
        int count = (prevCount != null ? prevCount : 0) + 1;
        confirmCounts.put(symbol, count);

        if (count < requiredConfirms) {
            return "CONFIRMING " + count + "/" + requiredConfirms;
        }

        confirmCounts.remove(symbol);
        return "BUY";
    }

    @Test
    @DisplayName("시나리오 1: 엔피(291230) — ascending 있으면 매수 불가 재현")
    void testNpRealData_AscendingFail() {
        prevClose = 846.0;
        String sym = "291230";

        // 실제 4/10 엔피 가격 (5초 간격 시뮬레이션, 1분봉 기반 추정)
        // 09:00:00 시가 922, 09:00 내 1014까지 급등
        // 09:02 968으로 하락, 09:03 1081 반등, 09:04 1055 하락
        double[] prices = {
            922,   // 09:00:05 — gap +8.98% ✅
            1014,  // 09:00:10 — gap +19.9% ✅
            968,   // 09:00:15 — gap +14.4% ✅ BUT 968 < 1014 → NOT_ASCENDING
            1081,  // 09:00:20 — gap +27.8% ✅ (리셋 후 1회차)
            1055,  // 09:00:25 — gap +24.7% ✅ (2회차)
            1050,  // 09:00:30 — gap +24.1% ✅ (3회차) BUT 1050 < 1081 → NOT_ASCENDING
            1060,  // 09:00:35 — 리셋 후 1회차
            1065,  // 09:00:40 — 2회차
            1055,  // 09:00:45 — 3회차 BUT 1055 < 1065 → NOT_ASCENDING
        };

        List<String> results = new ArrayList<>();
        boolean bought = false;
        for (double price : prices) {
            String result = simulateOneCheckWithAscending(sym, price);
            results.add(String.format("price=%.0f → %s", price, result));
            if ("BUY".equals(result)) {
                bought = true;
                break;
            }
        }

        // 엔피는 등락이 심해서 ascending을 한 번도 충족 못함
        assertFalse(bought, "엔피 등락 패턴에서 ascending 통과 불가 재현");

        // NOT_ASCENDING이 최소 2회 이상 발생
        long notAscCount = results.stream().filter(r -> r.contains("NOT_ASCENDING")).count();
        assertTrue(notAscCount >= 2, "NOT_ASCENDING 반복 발생 확인");
    }

    @Test
    @DisplayName("시나리오 2: 영화금속(012280) 실제 4/10 데이터 — 하락 추세로 ascending 실패")
    void testYounghwaRealData_AscendingFail() {
        prevClose = 1025.0;
        String sym = "012280";

        // 실제 4/10: 1086→1104→1076→1065→1056 (시가 급등 후 하락 추세)
        double[] prices = {
            1086,  // 09:00:05 — gap +5.95% ✅
            1104,  // 09:00:10 — gap +7.71% ✅
            1076,  // 09:00:15 — gap +4.98% ✅ BUT 1076 < 1104 → NOT_ASCENDING
            1065,  // 09:00:20 — gap +3.90% ✅ (리셋 후 1회차)
            1062,  // 09:00:25 — gap +3.61% ✅ (2회차)
            1056,  // 09:00:30 — gap +3.02% ✅ (3회차) BUT 1056 < 1065 → NOT_ASCENDING
        };

        List<String> results = new ArrayList<>();
        boolean bought = false;
        for (double price : prices) {
            String result = simulateOneCheckWithAscending(sym, price);
            results.add(String.format("price=%.0f → %s", price, result));
            if ("BUY".equals(result)) {
                bought = true;
                break;
            }
        }

        assertFalse(bought, "영화금속 하락 추세에서 ascending 통과 불가 재현");
    }

    @Test
    @DisplayName("시나리오 3: 정상 상승 패턴 — ascending 통과하여 매수")
    void testNormalAscending_Buy() {
        prevClose = 1000.0;
        String sym = "TEST001";

        // 정상적으로 3연속 상승하는 패턴
        double[] prices = {1025, 1030, 1035}; // 각각 +2.5%, +3.0%, +3.5%

        boolean bought = false;
        for (double price : prices) {
            String result = simulateOneCheck(sym, price);
            if ("BUY".equals(result)) {
                bought = true;
                break;
            }
        }

        assertTrue(bought, "정상 상승 패턴은 매수 성공");
    }

    @Test
    @DisplayName("시나리오 4: gap 미달 시 confirm 리셋 확인")
    void testGapLowResetsConfirm() {
        prevClose = 1000.0;
        String sym = "TEST002";

        // 1회 gap 통과 → 2회차에 gap 미달 → 리셋
        String r1 = simulateOneCheck(sym, 1025); // +2.5% gap ✅
        assertEquals("CONFIRMING 1/3", r1);

        String r2 = simulateOneCheck(sym, 1010); // +1.0% gap ❌
        assertTrue(r2.startsWith("GAP_LOW"));

        // 리셋 후 다시 1회차부터
        String r3 = simulateOneCheck(sym, 1030);
        assertEquals("CONFIRMING 1/3", r3);
    }

    @Test
    @DisplayName("시나리오 5: 첫 캔들 급등 후 하락하는 전형적 모닝러쉬 패턴")
    void testTypicalMorningRushPattern() {
        prevClose = 846.0; // 엔피 prevClose
        String sym = "291230";

        // 전형적 패턴: 시가 급등 → 약간 하락 → 다시 급등 → 하락
        // 모닝러쉬에서 흔한 "첫봉 급등 후 조정" 패턴
        double[] prices = {
            922,   // +8.98% gap ✅ (1/3)
            935,   // +10.5% ✅ (2/3)
            928,   // +9.7% ✅ BUT 928 < 935 → NOT_ASCENDING → 리셋!
            940,   // +11.1% ✅ (1/3) 리셋 후 재시작
            945,   // +11.7% ✅ (2/3)
            950,   // +12.3% ✅ AND 950 > 945 > 940 → ASCENDING ✅ → BUY!
        };

        boolean bought = false;
        double buyPrice = 0;
        for (double price : prices) {
            String result = simulateOneCheckWithAscending(sym, price);
            if ("BUY".equals(result)) {
                bought = true;
                buyPrice = price;
                break;
            }
        }

        assertTrue(bought, "조정 후 재상승 패턴은 매수 가능 (ascending 있어도)");
        assertEquals(950, buyPrice);
    }

    @Test
    @DisplayName("시나리오 6: 극심한 등락 — ascending 있으면 매수 불가")
    void testVolatilePattern_NeverBuysWithAscending() {
        prevClose = 846.0;
        String sym = "291230";

        double[] prices = {
            922, 1014, 968, 1081, 1055, 1050, 1082, 1060, 1040, 1055,
            1040, 1050, 1030, 1045, 1035, 1050, 1040, 1030, 1045, 1035,
        };

        boolean bought = false;
        int notAscendingCount = 0;
        for (double price : prices) {
            String result = simulateOneCheckWithAscending(sym, price);
            if (result.contains("NOT_ASCENDING")) notAscendingCount++;
            if ("BUY".equals(result)) {
                bought = true;
                break;
            }
        }

        assertFalse(bought, "극심한 등락 + ascending 있으면 매수 불가");
        assertTrue(notAscendingCount >= 3, "NOT_ASCENDING 다수 발생: " + notAscendingCount);
    }

    @Test
    @DisplayName("시나리오 7: 엔피 실제 데이터 — ascending 제거 후 10초 만에 매수 성공")
    void testNpRealData_NoAscending_BuySuccess() {
        prevClose = 846.0;
        String sym = "291230";

        // ascending 제거 → confirm 3회만 (gap 2% 유지 확인)
        double[] prices = {
            922,   // 09:00:05 — gap +8.98% ✅ (1/3)
            1014,  // 09:00:10 — gap +19.9% ✅ (2/3)
            968,   // 09:00:15 — gap +14.4% ✅ (3/3) → BUY! (ascending 없으므로)
        };

        boolean bought = false;
        double buyPrice = 0;
        for (double price : prices) {
            String result = simulateOneCheck(sym, price);
            if ("BUY".equals(result)) {
                bought = true;
                buyPrice = price;
                break;
            }
        }

        assertTrue(bought, "ascending 제거 후 엔피 매수 성공");
        assertEquals(968, buyPrice);
        // 968원 매수 → TP 2% = 987원 → 09:03에 1081까지 올라가므로 +2% 익절 가능
    }

    @Test
    @DisplayName("시나리오 8: 영화금속 — ascending 제거 후 매수 + 이후 추이")
    void testYounghwa_NoAscending_BuyAndHold() {
        prevClose = 1025.0;
        String sym = "012280";

        double[] prices = {
            1086,  // gap +5.95% ✅ (1/3)
            1104,  // gap +7.71% ✅ (2/3)
            1076,  // gap +4.98% ✅ (3/3) → BUY!
        };

        boolean bought = false;
        double buyPrice = 0;
        for (double price : prices) {
            String result = simulateOneCheck(sym, price);
            if ("BUY".equals(result)) {
                bought = true;
                buyPrice = price;
                break;
            }
        }

        assertTrue(bought, "영화금속 ascending 제거 후 매수 성공");
        assertEquals(1076, buyPrice);
        // 1076원 매수 → 종가 1071 → -0.5% (거의 본전, SL 미도달)
    }
}
