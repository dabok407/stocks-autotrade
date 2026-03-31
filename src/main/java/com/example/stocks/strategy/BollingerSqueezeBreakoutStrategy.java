package com.example.stocks.strategy;

import com.example.stocks.market.StockCandle;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * 볼린저 밴드 스퀴즈 돌파 전략 (Bollinger Squeeze Breakout)
 *
 * ═══════════════════════════════════════════════════════════
 *  밴드 수축(에너지 축적) → 확장(폭발) + 거래량 동반 장대 양봉 매수
 *  10일 이동평균선 이탈 시 청산
 * ═══════════════════════════════════════════════════════════
 */
public class BollingerSqueezeBreakoutStrategy implements TradingStrategy {

    // ===== 볼린저 밴드 =====
    private static final int BB_PERIOD = 20;
    private static final double BB_STD_MULT = 2.0;

    // ===== 스퀴즈 감지 =====
    private static final int SQUEEZE_LOOKBACK = 15;
    private static final double SQUEEZE_THRESHOLD = 0.75;

    // ===== 청산: 10 SMA =====
    private static final int EXIT_SMA_PERIOD = 10;

    // ===== 거래량 =====
    private static final int VOLUME_AVG_PERIOD = 20;
    private static final double VOLUME_THRESHOLD = 0.8;

    // ===== ATR 안전망 =====
    private static final int ATR_PERIOD = 14;
    private static final double HARD_SL_ATR_MULT = 3.0;
    private static final double HARD_TP_ATR_MULT = 6.0;

    // ===== 안전 =====
    private static final int MIN_CANDLES = 40;
    private static final double MIN_ATR_PCT = 0.002;  // 주식: 수수료 감안 최소 ATR 상향 (0.001→0.002)

    @Override
    public StrategyType type() {
        return StrategyType.BOLLINGER_SQUEEZE_BREAKOUT;
    }

    @Override
    public Signal evaluate(StrategyContext ctx) {
        List<StockCandle> candles = ctx.candles;
        if (candles == null || candles.size() < MIN_CANDLES) return Signal.none();

        int n = candles.size();
        StockCandle last = candles.get(n - 1);
        double close = last.trade_price;

        boolean hasPosition = ctx.position != null
                && ctx.position.getQty() > 0;

        if (hasPosition) {
            return evaluateExit(ctx, candles, last, close);
        }

        return evaluateEntry(candles, last, close);
    }

    private Signal evaluateEntry(List<StockCandle> candles, StockCandle last, double close) {
        int n = candles.size();

        // ── STEP 1: 볼린저 밴드 계산 ──
        double[] bb = Indicators.bollinger(candles, BB_PERIOD, BB_STD_MULT);
        double bbLower = bb[0], bbMiddle = bb[1], bbUpper = bb[2];
        if (bbMiddle <= 0) return Signal.none();

        double bbWidth = (bbUpper - bbLower) / bbMiddle;

        // ── STEP 2: 스퀴즈 → 확장 감지 ──
        double widthSum = 0;
        int widthCount = 0;
        double minWidth = Double.MAX_VALUE;

        for (int i = Math.max(BB_PERIOD, n - 1 - SQUEEZE_LOOKBACK); i < n - 1; i++) {
            double sum = 0, sumSq = 0;
            for (int j = i - BB_PERIOD + 1; j <= i; j++) {
                double p = candles.get(j).trade_price;
                sum += p;
                sumSq += p * p;
            }
            double mean = sum / BB_PERIOD;
            if (mean <= 0) continue;
            double variance = sumSq / BB_PERIOD - mean * mean;
            double std = Math.sqrt(Math.max(0, variance));
            double w = (BB_STD_MULT * 2 * std) / mean;
            widthSum += w;
            widthCount++;
            if (w < minWidth) minWidth = w;
        }
        if (widthCount < 3) return Signal.none();
        double avgWidth = widthSum / widthCount;

        boolean hadSqueeze = minWidth < avgWidth * SQUEEZE_THRESHOLD;
        if (!hadSqueeze) return Signal.none();

        boolean expanding = bbWidth > minWidth * 1.2;
        if (!expanding) return Signal.none();

        // ── STEP 3: 장대 양봉이 상단 밴드 돌파 ──
        if (!CandlePatterns.isBullish(last)) return Signal.none();
        if (close <= bbUpper) return Signal.none();

        double bodyRatio = CandlePatterns.body(last) / CandlePatterns.range(last);
        if (bodyRatio < 0.40) return Signal.none();

        // ── STEP 4: 거래량 확인 ──
        double avgVol = Indicators.smaVolume(candles, VOLUME_AVG_PERIOD);
        double curVol = last.candle_acc_trade_volume;
        if (avgVol > 0 && curVol < avgVol * VOLUME_THRESHOLD) return Signal.none();

        // ── STEP 5: ATR 안전 체크 ──
        double atr = Indicators.atr(candles, ATR_PERIOD);
        if (atr / close < MIN_ATR_PCT) return Signal.none();

        // ── Confidence 산출 ──
        double score = 5.0;

        double breakoutPct = (close - bbUpper) / bbUpper * 100;
        if (breakoutPct >= 1.0) score += 1.5;
        else if (breakoutPct >= 0.5) score += 1.0;
        else score += 0.3;

        double volRatio = avgVol > 0 ? curVol / avgVol : 1.0;
        if (volRatio >= 2.5) score += 1.5;
        else if (volRatio >= 1.8) score += 1.0;
        else score += 0.3;

        if (bodyRatio >= 0.75) score += 1.0;
        else if (bodyRatio >= 0.65) score += 0.5;

        double squeezeDepth = 1.0 - (minWidth / avgWidth);
        if (squeezeDepth >= 0.5) score += 1.0;
        else if (squeezeDepth >= 0.3) score += 0.5;

        String reason = String.format(Locale.ROOT,
                "BB_SQ_BUY bbUpper=%.2f close=%.2f breakout=%.2f%% squeeze_depth=%.1f%% vol=%.1fx body=%.0f%%",
                bbUpper, close, breakoutPct, squeezeDepth * 100, volRatio, bodyRatio * 100);
        return Signal.of(SignalAction.BUY, type(), reason, Math.min(10.0, score));
    }

    private Signal evaluateExit(StrategyContext ctx, List<StockCandle> candles,
                                StockCandle last, double close) {
        double avgPrice = ctx.position.getAvgPrice().doubleValue();
        if (avgPrice <= 0) return Signal.none();

        double atr = Indicators.atr(candles, ATR_PERIOD);

        // 1. 하드 SL
        double hardSL = avgPrice - HARD_SL_ATR_MULT * atr;
        if (last.low_price <= hardSL) {
            double pnl = ((close - avgPrice) / avgPrice) * 100.0;
            String reason = String.format(Locale.ROOT,
                    "BB_SQ_HARD_STOP avg=%.2f sl=%.2f pnl=%.2f%%", avgPrice, hardSL, pnl);
            return Signal.of(SignalAction.SELL, type(), reason);
        }

        // 2. 하드 TP
        double hardTP = avgPrice + HARD_TP_ATR_MULT * atr;
        if (last.high_price >= hardTP) {
            String reason = String.format(Locale.ROOT,
                    "BB_SQ_TP avg=%.2f tp=%.2f pnl=%.2f%%",
                    avgPrice, hardTP, ((hardTP - avgPrice) / avgPrice) * 100.0);
            return Signal.of(SignalAction.SELL, type(), reason);
        }

        // 3. 핵심 청산: 10 SMA 이탈 (이익 구간에서만)
        double sma10 = Indicators.sma(candles, EXIT_SMA_PERIOD);
        double pnlPct = ((close - avgPrice) / avgPrice) * 100.0;

        if (!Double.isNaN(sma10) && close < sma10 && pnlPct > 0.3) {
            int belowCount = 0;
            for (int i = candles.size() - 3; i < candles.size(); i++) {
                if (i >= 0 && candles.get(i).trade_price < sma10) {
                    belowCount++;
                }
            }
            if (belowCount >= 3) {
                String reason = String.format(Locale.ROOT,
                        "BB_SQ_10SMA_EXIT close=%.2f < sma10=%.2f pnl=%.2f%% (3candle confirm)",
                        close, sma10, pnlPct);
                return Signal.of(SignalAction.SELL, type(), reason);
            }
        }

        // 4. 손실 중 10 SMA 이탈
        if (!Double.isNaN(sma10) && close < sma10 && pnlPct < -1.5) {
            String reason = String.format(Locale.ROOT,
                    "BB_SQ_10SMA_LOSS close=%.2f < sma10=%.2f pnl=%.2f%%",
                    close, sma10, pnlPct);
            return Signal.of(SignalAction.SELL, type(), reason);
        }

        return Signal.none();
    }
}
