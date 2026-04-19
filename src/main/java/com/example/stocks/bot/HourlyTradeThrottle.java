package com.example.stocks.bot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 동일 종목의 시간당 매수 횟수 제한 + 짧은 간격 쿨타임.
 * 같은 종목을 1시간 내 최대 N회 + 추가로 N분 내 1회만 매수 가능.
 *
 * 두 조건을 모두 만족해야 매수 가능:
 *  - 1시간 내 최대 maxTradesPerHour 회
 *  - cooldown_min 내 최대 1회 (기본 20분)
 *
 * 코인봇 HourlyTradeThrottle(upbit-autotrade-java8) 포팅 (2026-04-18).
 */
public class HourlyTradeThrottle {

    private final int maxTradesPerHour;
    private final long cooldownMs;
    private final ConcurrentHashMap<String, Deque<Long>> tradeHistory =
            new ConcurrentHashMap<String, Deque<Long>>();

    public HourlyTradeThrottle(int maxTradesPerHour) {
        this(maxTradesPerHour, 20);
    }

    public HourlyTradeThrottle(int maxTradesPerHour, int cooldownMin) {
        this.maxTradesPerHour = maxTradesPerHour;
        this.cooldownMs = cooldownMin * 60_000L;
    }

    public boolean canBuy(String symbol) {
        Deque<Long> history = tradeHistory.get(symbol);
        if (history == null) return true;

        long now = System.currentTimeMillis();
        long hourCutoff = now - 3600_000L;
        long cooldownCutoff = now - cooldownMs;

        synchronized (history) {
            while (!history.isEmpty() && history.peekFirst() < hourCutoff) {
                history.pollFirst();
            }
            if (history.size() >= maxTradesPerHour) return false;
            if (!history.isEmpty() && history.peekLast() > cooldownCutoff) return false;
            return true;
        }
    }

    public void recordBuy(String symbol) {
        Deque<Long> history = tradeHistory.computeIfAbsent(symbol,
                k -> new ArrayDeque<Long>());
        synchronized (history) {
            history.addLast(System.currentTimeMillis());
        }
    }

    public void recordBuyAt(String symbol, long epochMs) {
        Deque<Long> history = tradeHistory.computeIfAbsent(symbol,
                k -> new ArrayDeque<Long>());
        synchronized (history) {
            history.addLast(epochMs);
        }
    }

    public void removeLastBuy(String symbol) {
        Deque<Long> history = tradeHistory.get(symbol);
        if (history == null) return;
        synchronized (history) {
            if (!history.isEmpty()) history.pollLast();
        }
    }

    public long remainingWaitMs(String symbol) {
        Deque<Long> history = tradeHistory.get(symbol);
        if (history == null) return 0;

        long now = System.currentTimeMillis();
        long hourCutoff = now - 3600_000L;
        long cooldownCutoff = now - cooldownMs;

        synchronized (history) {
            while (!history.isEmpty() && history.peekFirst() < hourCutoff) {
                history.pollFirst();
            }
            long hourWait = 0;
            long cooldownWait = 0;
            if (history.size() >= maxTradesPerHour) {
                hourWait = history.peekFirst() + 3600_000L - now;
            }
            if (!history.isEmpty() && history.peekLast() > cooldownCutoff) {
                cooldownWait = history.peekLast() + cooldownMs - now;
            }
            return Math.max(hourWait, cooldownWait);
        }
    }
}
