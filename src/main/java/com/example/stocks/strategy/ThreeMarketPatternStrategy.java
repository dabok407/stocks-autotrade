package com.example.stocks.strategy;

import com.example.stocks.market.StockCandle;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * 쓰리 마켓 패턴 (Three Market Pattern) — 이중 가짜돌파 → 신고가 돌파 매수
 *
 * ═══════════════════════════════════════════════════════════
 *  횡보 구간에서 상/하 가짜돌파가 모두 발생한 뒤 신고가 돌파 시 매수
 * ═══════════════════════════════════════════════════════════
 */
public class ThreeMarketPatternStrategy implements TradingStrategy {

    // ===== 박스권 감지 =====
    private static final int RANGE_LOOKBACK = 30;
    private static final double RANGE_MAX_PCT = 0.10;   // 주식: 일일 변동성 낮음 (0.18→0.10)
    private static final double RANGE_MIN_PCT = 0.008;

    // ===== 가짜돌파 감지 =====
    private static final double FALSE_BREAKOUT_MIN = 0.001;
    private static final double FALSE_BREAKOUT_MAX = 0.035; // 주식: 가짜돌파 범위 축소 (0.06→0.035)
    private static final int FALSE_BO_SCAN_WINDOW = 30;

    // ===== 신고가 돌파 =====
    private static final double NEW_HIGH_MIN_PCT = 0.001;

    // ===== ATR 청산 =====
    private static final int ATR_PERIOD = 14;
    private static final double SL_ATR_MULT = 2.0;
    private static final double TP_ATR_MULT = 3.5;
    private static final double TRAIL_ATR_MULT = 2.5;

    // ===== 거래량 =====
    private static final int VOLUME_AVG_PERIOD = 20;
    private static final double VOLUME_THRESHOLD = 0.5;

    // ===== 안전 =====
    private static final int MIN_CANDLES = 50;

    @Override
    public StrategyType type() {
        return StrategyType.THREE_MARKET_PATTERN;
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

        double atr = Indicators.atr(candles, ATR_PERIOD);

        if (hasPosition) {
            return evaluateExit(ctx, candles, last, close, atr);
        }

        return evaluateEntry(candles, last, close, atr);
    }

    private Signal evaluateEntry(List<StockCandle> candles, StockCandle last, double close, double atr) {
        int n = candles.size();

        // ── STEP 1: 박스권(횡보) 감지 ──
        int rangeEnd = n - 2;
        int rangeStart = Math.max(0, rangeEnd - RANGE_LOOKBACK);

        double rangeHigh = Double.MIN_VALUE;
        double rangeLow = Double.MAX_VALUE;
        for (int i = rangeStart; i <= rangeEnd; i++) {
            if (candles.get(i).high_price > rangeHigh) rangeHigh = candles.get(i).high_price;
            if (candles.get(i).low_price < rangeLow) rangeLow = candles.get(i).low_price;
        }

        double rangePct = (rangeHigh - rangeLow) / rangeLow;
        if (rangePct > RANGE_MAX_PCT || rangePct < RANGE_MIN_PCT) return Signal.none();

        // ── STEP 2: 이중 가짜돌파 감지 ──
        boolean falseBreakoutUp = false;
        boolean falseBreakoutDown = false;
        int scanStart = Math.max(rangeStart, rangeEnd - FALSE_BO_SCAN_WINDOW);

        double innerHigh = percentileHigh(candles, rangeStart, rangeEnd, 0.90);
        double innerLow = percentileLow(candles, rangeStart, rangeEnd, 0.10);

        for (int i = scanStart; i <= rangeEnd; i++) {
            StockCandle c = candles.get(i);

            if (c.high_price > innerHigh * (1 + FALSE_BREAKOUT_MIN)
                    && c.high_price < innerHigh * (1 + FALSE_BREAKOUT_MAX)
                    && c.trade_price < innerHigh) {
                falseBreakoutUp = true;
            }

            if (c.low_price < innerLow * (1 - FALSE_BREAKOUT_MIN)
                    && c.low_price > innerLow * (1 - FALSE_BREAKOUT_MAX)
                    && c.trade_price > innerLow) {
                falseBreakoutDown = true;
            }
        }

        if (!falseBreakoutUp && !falseBreakoutDown) return Signal.none();

        // ── STEP 3: 신고가 돌파 확인 ──
        if (close <= rangeHigh * (1 + NEW_HIGH_MIN_PCT)) return Signal.none();

        // 현재 봉이 양봉인지 확인
        if (!CandlePatterns.isBullish(last)) return Signal.none();

        // ── STEP 4: 거래량 확인 ──
        double avgVol = Indicators.smaVolume(candles, VOLUME_AVG_PERIOD);
        double curVol = last.candle_acc_trade_volume;
        if (avgVol > 0 && curVol < avgVol * VOLUME_THRESHOLD) return Signal.none();

        // ── STEP 5: Confidence 산출 ──
        double score = 4.0;
        if (falseBreakoutUp && falseBreakoutDown) score += 2.0;

        double breakoutPct = (close - rangeHigh) / rangeHigh * 100;
        if (breakoutPct >= 1.5) score += 1.5;
        else if (breakoutPct >= 0.8) score += 1.0;
        else score += 0.3;

        double volRatio = avgVol > 0 ? curVol / avgVol : 1.0;
        if (volRatio >= 2.0) score += 1.5;
        else if (volRatio >= 1.5) score += 1.0;
        else score += 0.3;

        double bodyRatio = CandlePatterns.body(last) / CandlePatterns.range(last);
        if (bodyRatio >= 0.7) score += 1.0;
        else if (bodyRatio >= 0.5) score += 0.5;

        String reason = String.format(Locale.ROOT,
                "3MKT_BUY rangeHigh=%.2f rangeLow=%.2f false_BO_up=%b false_BO_down=%b close=%.2f breakout=%.2f%% vol=%.1fx",
                rangeHigh, rangeLow, falseBreakoutUp, falseBreakoutDown, close, breakoutPct, volRatio);
        return Signal.of(SignalAction.BUY, type(), reason, Math.min(10.0, score));
    }

    private Signal evaluateExit(StrategyContext ctx, List<StockCandle> candles,
                                StockCandle last, double close, double atr) {
        double avgPrice = ctx.position.getAvgPrice().doubleValue();
        if (avgPrice <= 0) return Signal.none();

        double hardSL = avgPrice - SL_ATR_MULT * atr;
        double hardTP = avgPrice + TP_ATR_MULT * atr;

        double peakHigh = Indicators.peakHighSinceEntry(candles, avgPrice);
        double trailStop = peakHigh - TRAIL_ATR_MULT * atr;

        double effectiveSL = (close > avgPrice && trailStop > hardSL) ? trailStop : hardSL;

        // 1. SL 히트
        if (last.low_price <= effectiveSL) {
            double pnl = ((close - avgPrice) / avgPrice) * 100.0;
            boolean isTrail = trailStop > hardSL;
            String reason = String.format(Locale.ROOT,
                    "3MKT_%s avg=%.2f peak=%.2f atr=%.4f sl=%.2f pnl=%.2f%%",
                    isTrail ? "TRAIL_STOP" : "HARD_STOP", avgPrice, peakHigh, atr, effectiveSL, pnl);
            return Signal.of(SignalAction.SELL, type(), reason);
        }

        // 2. TP 히트
        if (last.high_price >= hardTP) {
            String reason = String.format(Locale.ROOT,
                    "3MKT_TP avg=%.2f tp=%.2f pnl=%.2f%%",
                    avgPrice, hardTP, ((hardTP - avgPrice) / avgPrice) * 100.0);
            return Signal.of(SignalAction.SELL, type(), reason);
        }

        return Signal.none();
    }

    /** 캔들 고가의 percentile */
    private double percentileHigh(List<StockCandle> candles, int from, int to, double pct) {
        int count = to - from + 1;
        double[] highs = new double[count];
        for (int i = 0; i < count; i++) highs[i] = candles.get(from + i).high_price;
        java.util.Arrays.sort(highs);
        int idx = Math.min((int) (count * pct), count - 1);
        return highs[idx];
    }

    /** 캔들 저가의 percentile */
    private double percentileLow(List<StockCandle> candles, int from, int to, double pct) {
        int count = to - from + 1;
        double[] lows = new double[count];
        for (int i = 0; i < count; i++) lows[i] = candles.get(from + i).low_price;
        java.util.Arrays.sort(lows);
        int idx = Math.max((int) (count * pct), 0);
        return lows[idx];
    }
}
