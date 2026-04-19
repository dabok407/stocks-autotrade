package com.example.stocks.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V39 B3: KrxSharedTradeThrottle 단위 테스트.
 *
 * 시나리오:
 *  T01 — 신규 종목은 즉시 매수 가능
 *  T02 — tryClaim 후 20분 내 재진입 차단
 *  T03 — 1시간 2회 초과 차단
 *  T04 — releaseClaim 후 재진입 허용
 *  T05 — 서로 다른 종목은 독립적으로 throttle 처리
 *  T06 — 동시 tryClaim 2건 중 1건만 성공 (synchronized race 차단)
 */
class KrxSharedTradeThrottleTest {

    @Test
    void T01_신규_종목은_즉시_매수_가능() {
        KrxSharedTradeThrottle t = new KrxSharedTradeThrottle();
        assertTrue(t.canBuy("005930"));
    }

    @Test
    void T02_tryClaim_후_20분_내_재진입_차단() {
        KrxSharedTradeThrottle t = new KrxSharedTradeThrottle();
        assertTrue(t.tryClaim("005930"));
        assertFalse(t.canBuy("005930"));
        assertFalse(t.tryClaim("005930"));
        long wait = t.remainingWaitMs("005930");
        assertTrue(wait > 0 && wait <= 20 * 60 * 1000L,
                "쿨다운 20분 이내 남아있어야 함. actual=" + wait);
    }

    @Test
    void T03_1시간_2회_초과_차단() {
        KrxSharedTradeThrottle t = new KrxSharedTradeThrottle();
        // 인위적으로 recordBuyAt으로 과거 시각 기록 (쿨다운 통과하면서 시간당 제한은 남음)
        long now = System.currentTimeMillis();
        t.getDelegate().recordBuyAt("005930", now - 25 * 60 * 1000L); // 25분 전
        t.getDelegate().recordBuyAt("005930", now - 22 * 60 * 1000L); // 22분 전
        // 이미 1시간 내 2건 → 차단
        assertFalse(t.canBuy("005930"));
        assertFalse(t.tryClaim("005930"));
    }

    @Test
    void T04_releaseClaim_후_재진입_허용() {
        KrxSharedTradeThrottle t = new KrxSharedTradeThrottle();
        assertTrue(t.tryClaim("005930"));
        assertFalse(t.canBuy("005930"));
        t.releaseClaim("005930");
        assertTrue(t.canBuy("005930"), "releaseClaim 후 즉시 재매수 가능");
    }

    @Test
    void T05_서로_다른_종목은_독립_처리() {
        KrxSharedTradeThrottle t = new KrxSharedTradeThrottle();
        assertTrue(t.tryClaim("005930"));
        assertTrue(t.tryClaim("000660"), "다른 종목은 별개 throttle");
        assertFalse(t.canBuy("005930"));
        assertFalse(t.canBuy("000660"));
    }

    @Test
    void T06_동시_tryClaim_1건만_성공() throws InterruptedException {
        KrxSharedTradeThrottle t = new KrxSharedTradeThrottle();
        final int threadCount = 20;
        final java.util.concurrent.atomic.AtomicInteger successCount =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.CountDownLatch startLatch =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch doneLatch =
                new java.util.concurrent.CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    if (t.tryClaim("010170")) successCount.incrementAndGet();
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }
        startLatch.countDown();
        doneLatch.await();
        assertEquals(1, successCount.get(),
                "동시 20개 thread 중 1건만 claim 성공해야 함 (synchronized race 차단)");
    }
}
