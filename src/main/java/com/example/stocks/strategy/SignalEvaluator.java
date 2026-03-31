package com.example.stocks.strategy;

import java.util.List;
import java.util.Locale;

/**
 * 공통 신호 평가 로직.
 *
 * BacktestService와 TradingBotService가 동일한 판단 로직을 사용하도록
 * STEP 1(TP/SL)과 STEP 2(전략 평가)를 한 곳에서 관리합니다.
 *
 * "판단"만 담당하고, "실행"(매도 처리, DB 저장, 주문)은 각 서비스가 처리합니다.
 */
public final class SignalEvaluator {

    private SignalEvaluator() {}

    /**
     * 평가 결과.
     */
    public static class Result {
        public final Signal signal;
        public final StrategyType strategyType;
        public final String patternType;
        public final String reason;
        public final boolean isTpSl;
        public final double confidence;

        public Result(Signal signal, StrategyType strategyType, String patternType, String reason, boolean isTpSl) {
            this.signal = signal;
            this.strategyType = strategyType;
            this.patternType = patternType;
            this.reason = reason;
            this.isTpSl = isTpSl;
            this.confidence = signal != null ? signal.confidence : 0;
        }

        public boolean isEmpty() { return signal == null || signal.action == SignalAction.NONE; }
        public boolean isSell() { return signal != null && signal.action == SignalAction.SELL; }
        public boolean isBuy() { return signal != null && signal.action == SignalAction.BUY; }
        public boolean isAddBuy() { return signal != null && signal.action == SignalAction.ADD_BUY; }
    }

    /** 아무 신호 없음 */
    private static final Result EMPTY = new Result(null, null, null, null, false);

    /**
     * STEP 1: TP/SL 체크.
     */
    public static Result checkTpSl(boolean open, double avgPrice, double close, double tpPct, double slPct) {
        if (!open || avgPrice <= 0) return null;

        double pnlPct = ((close - avgPrice) / avgPrice) * 100.0;

        if (slPct > 0 && pnlPct <= -slPct) {
            String reason = String.format(Locale.ROOT, "STOP_LOSS %.2f%% (pnl=%.2f%%)", slPct, pnlPct);
            Signal sig = Signal.of(SignalAction.SELL, null, reason);
            return new Result(sig, null, "STOP_LOSS", reason, true);
        }

        if (tpPct > 0 && pnlPct >= tpPct) {
            String reason = String.format(Locale.ROOT, "TAKE_PROFIT %.2f%% (pnl=%.2f%%)", tpPct, pnlPct);
            Signal sig = Signal.of(SignalAction.SELL, null, reason);
            return new Result(sig, null, "TAKE_PROFIT", reason, true);
        }

        return null;
    }

    /**
     * STEP 2: 전략 평가.
     */
    public static Result evaluateStrategies(List<StrategyType> stypes, StrategyFactory strategyFactory, StrategyContext ctx) {
        Signal chosen = null;
        StrategyType chosenType = null;

        for (StrategyType t : stypes) {
            if (!strategyFactory.isRegistered(t)) continue;
            TradingStrategy s = strategyFactory.get(t);
            Signal sig = s.evaluate(ctx);
            if (sig == null || sig.action == SignalAction.NONE) continue;

            if (chosen == null) {
                chosen = sig;
                chosenType = t;
            } else {
                int pNew = priority(sig.action);
                int pCur = priority(chosen.action);
                if (pNew > pCur || (pNew == pCur && sig.confidence > chosen.confidence)) {
                    chosen = sig;
                    chosenType = t;
                }
            }

            if (chosen.action == SignalAction.SELL) break;
        }

        if (chosen == null || chosen.action == SignalAction.NONE) return EMPTY;

        String patternType = (chosen.type == null
                ? (chosenType == null ? "UNKNOWN" : chosenType.name())
                : chosen.type.name());
        String reason = (chosenType == null ? "" : ("[" + chosenType.name() + "] "))
                + (chosen.reason == null ? "" : chosen.reason)
                + String.format(Locale.ROOT, " [score=%.1f]", chosen.confidence);

        return new Result(chosen, chosenType, patternType, reason, false);
    }

    /**
     * STEP 1 + STEP 2를 한번에 수행합니다.
     */
    public static Result evaluate(boolean open, double avgPrice, double close,
                                  double tpPct, double slPct,
                                  List<StrategyType> stypes, StrategyFactory strategyFactory,
                                  StrategyContext ctx) {
        Result tpSlResult = checkTpSl(open, avgPrice, close, tpPct, slPct);
        if (tpSlResult != null) return tpSlResult;

        return evaluateStrategies(stypes, strategyFactory, ctx);
    }

    /** 액션 우선순위: SELL(3) > ADD_BUY(2) > BUY(1) */
    private static int priority(SignalAction a) {
        if (a == null) return 0;
        switch (a) {
            case SELL: return 3;
            case ADD_BUY: return 2;
            case BUY: return 1;
            default: return 0;
        }
    }
}
