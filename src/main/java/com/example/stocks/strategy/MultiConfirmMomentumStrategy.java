package com.example.stocks.strategy;

import com.example.stocks.market.StockCandle;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * 다중 확인 고확신 모멘텀 전략 (자급자족)
 *
 * ═══════════════════════════════════════════════════════════
 *  다중 지표 합류(Confluence) + 신뢰도 점수 기반 진입
 *  3개 필수 조건 + 5개 보너스 → 고확신 시그널만 생성
 * ═══════════════════════════════════════════════════════════
 *
 * ■ 필수 조건 (3개):
 *   ① 추세 정렬: EMA8 > EMA21 > EMA50
 *   ② RSI 구간: 45~78
 *   ③ MACD 히스토그램 양수
 *
 * ■ 권장: 30~60분봉, Min Confidence 7.0~8.0
 */
public class MultiConfirmMomentumStrategy implements TradingStrategy {

    // ===== 추세 EMA =====
    private static final int EMA_FAST = 8;
    private static final int EMA_MID = 21;
    private static final int EMA_SLOW = 50;

    // ===== RSI =====
    private static final int RSI_PERIOD = 14;
    private static final double RSI_ENTRY_LOW = 45.0;
    private static final double RSI_ENTRY_HIGH = 75.0;  // 주식: 과매수 임계값 낮춤 (78→75)

    // ===== MACD =====
    private static final int MACD_FAST = 12;
    private static final int MACD_SLOW = 26;
    private static final int MACD_SIGNAL = 9;
    private static final int HIST_BEARISH_BARS = 3;

    // ===== ATR =====
    private static final int ATR_PERIOD = 14;
    private static final double MIN_ATR_PCT = 0.002;
    private static final double SL_ATR_MULT = 2.0;
    private static final double TP_ATR_MULT = 3.5;
    private static final double TRAIL_ATR_MULT = 1.5;

    // ===== 거래량 =====
    private static final int VOLUME_AVG_PERIOD = 20;

    // ===== ADX =====
    private static final int ADX_PERIOD = 14;

    // ===== Bollinger =====
    private static final int BB_PERIOD = 20;
    private static final double BB_STD = 2.0;

    // ===== 안전 =====
    private static final int MIN_CANDLES = 60;

    @Override
    public StrategyType type() {
        return StrategyType.MULTI_CONFIRM_MOMENTUM;
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

        // ① 필수: 추세 정렬
        double ema8 = Indicators.ema(candles, EMA_FAST);
        double ema21 = Indicators.ema(candles, EMA_MID);
        double ema50 = Indicators.ema(candles, EMA_SLOW);
        if (Double.isNaN(ema8) || Double.isNaN(ema21) || Double.isNaN(ema50)) return Signal.none();
        if (!(ema8 > ema21 && ema21 > ema50)) return Signal.none();

        // ② 필수: RSI 45~78
        double rsi = Indicators.rsi(candles, RSI_PERIOD);
        if (rsi < RSI_ENTRY_LOW || rsi > RSI_ENTRY_HIGH) return Signal.none();

        // ③ 필수: MACD 히스토그램 양수
        double[] recentHist = Indicators.macdHistogramRecent(
                candles, MACD_FAST, MACD_SLOW, MACD_SIGNAL, 3);
        if (recentHist.length < 3) return Signal.none();
        double histCur = recentHist[2];
        double histPrev = recentHist[1];
        if (histCur <= 0) return Signal.none();

        // ATR 안전 체크
        double atr = Indicators.atr(candles, ATR_PERIOD);
        if (Double.isNaN(atr) || atr / close < MIN_ATR_PCT) return Signal.none();

        // ───── 신뢰도 점수 산출 ─────
        double score = 5.0;

        // A. EMA 정렬 마진
        double emaGapPct = (ema8 - ema21) / ema21 * 100.0;
        if (emaGapPct >= 2.0) score += 1.0;
        else if (emaGapPct >= 1.0) score += 0.6;
        else if (emaGapPct >= 0.3) score += 0.3;

        // B. RSI 최적 구간
        if (rsi >= 55 && rsi <= 68) score += 0.7;
        else if (rsi >= 50 && rsi <= 72) score += 0.3;

        // C. MACD 히스토그램 가속
        if (histCur > histPrev) {
            double accel = histPrev != 0 ? (histCur - histPrev) / Math.abs(histPrev) : 0;
            if (accel > 0.5) score += 0.8;
            else if (accel > 0.2) score += 0.5;
            else score += 0.2;
        }

        // D. 거래량 급등
        double avgVol = Indicators.smaVolume(candles, VOLUME_AVG_PERIOD);
        double curVol = last.candle_acc_trade_volume;
        double volRatio = avgVol > 0 ? curVol / avgVol : 1.0;
        if (volRatio >= 2.5) score += 1.0;
        else if (volRatio >= 1.5) score += 0.6;
        else if (volRatio >= 1.0) score += 0.2;

        // E. 캔들 강세
        boolean bullish = CandlePatterns.isBullish(last);
        double range = CandlePatterns.range(last);
        double bodyRatio = range > 0 ? CandlePatterns.body(last) / range : 0;
        if (bullish && bodyRatio >= 0.70) score += 0.8;
        else if (bullish && bodyRatio >= 0.50) score += 0.4;
        else if (bullish) score += 0.1;

        // F. ADX 추세 강도
        double adx = Indicators.adx(candles, ADX_PERIOD);
        if (adx >= 30) score += 0.7;
        else if (adx >= 22) score += 0.3;

        // G. 볼린저 밴드
        double[] bb = Indicators.bollinger(candles, BB_PERIOD, BB_STD);
        if (bb[2] > bb[0]) {
            double bbPos = (close - bb[1]) / (bb[2] - bb[1]);
            if (bbPos >= 0.5 && bbPos <= 1.0) score += 0.5;
            else if (bbPos >= 0.2) score += 0.2;
        }

        score = Math.min(10.0, score);

        if (score < 6.5) return Signal.none();

        String reason = String.format(Locale.ROOT,
                "MCM_BUY ema8=%.1f>21=%.1f>50=%.1f rsi=%.1f hist=%.4f vol=%.1fx body=%.0f%% adx=%.0f",
                ema8, ema21, ema50, rsi, histCur, volRatio, bodyRatio * 100, adx);
        return Signal.of(SignalAction.BUY, type(), reason, score);
    }

    private Signal evaluateExit(StrategyContext ctx, List<StockCandle> candles,
                                StockCandle last, double close) {
        if (ctx.position.getAvgPrice() == null) return Signal.none();
        double avgPrice = ctx.position.getAvgPrice().doubleValue();
        if (avgPrice <= 0) return Signal.none();

        double atr = Indicators.atr(candles, ATR_PERIOD);
        if (Double.isNaN(atr) || atr <= 0) return Signal.none();

        double pnlPct = ((close - avgPrice) / avgPrice) * 100.0;

        // 1. Hard SL
        double hardSL = avgPrice - SL_ATR_MULT * atr;
        if (last.low_price <= hardSL) {
            String reason = String.format(Locale.ROOT,
                    "MCM_HARD_SL avg=%.2f sl=%.2f atr=%.4f pnl=%.2f%%",
                    avgPrice, hardSL, atr, pnlPct);
            return Signal.of(SignalAction.SELL, type(), reason);
        }

        // 2. Hard TP
        double hardTP = avgPrice + TP_ATR_MULT * atr;
        if (last.high_price >= hardTP) {
            double tpPnl = ((hardTP - avgPrice) / avgPrice) * 100.0;
            String reason = String.format(Locale.ROOT,
                    "MCM_TP avg=%.2f tp=%.2f atr=%.4f pnl=%.2f%%",
                    avgPrice, hardTP, atr, tpPnl);
            return Signal.of(SignalAction.SELL, type(), reason);
        }

        // 3. 추세 붕괴: EMA8 < EMA21 (수익 중이면 2연속 확인)
        double ema8 = Indicators.ema(candles, EMA_FAST);
        double ema21 = Indicators.ema(candles, EMA_MID);
        if (!Double.isNaN(ema8) && !Double.isNaN(ema21) && ema8 < ema21) {
            if (pnlPct <= 0.3 || pnlPct < -2.0) {
                // 손실 또는 미미한 수익 → 즉시 청산
                String reason = String.format(Locale.ROOT,
                        "MCM_TREND_BREAK ema8=%.2f < ema21=%.2f pnl=%.2f%%",
                        ema8, ema21, pnlPct);
                return Signal.of(SignalAction.SELL, type(), reason);
            }
            // 수익 중 → 직전 캔들에서도 EMA8 < EMA21인지 2연속 확인
            int prevTail = candles.size() - 1;
            double prevEma8 = Indicators.ema(candles, EMA_FAST, prevTail);
            double prevEma21 = Indicators.ema(candles, EMA_MID, prevTail);
            if (!Double.isNaN(prevEma8) && !Double.isNaN(prevEma21) && prevEma8 < prevEma21) {
                String reason = String.format(Locale.ROOT,
                        "MCM_TREND_BREAK_2X ema8=%.2f < ema21=%.2f prev8=%.2f prev21=%.2f pnl=%.2f%%",
                        ema8, ema21, prevEma8, prevEma21, pnlPct);
                return Signal.of(SignalAction.SELL, type(), reason);
            }
        }

        // 4. 트레일링 스탑
        if (close > avgPrice) {
            double peakHigh = Indicators.peakHighSinceEntry(candles, avgPrice);
            double trailStop = peakHigh - TRAIL_ATR_MULT * atr;
            if (trailStop > avgPrice && close <= trailStop) {
                String reason = String.format(Locale.ROOT,
                        "MCM_TRAIL avg=%.2f peak=%.2f trail=%.2f close=%.2f pnl=%.2f%%",
                        avgPrice, peakHigh, trailStop, close, pnlPct);
                return Signal.of(SignalAction.SELL, type(), reason);
            }
        }

        // 5. 모멘텀 소멸
        if (pnlPct > 0) {
            double[] recentHist = Indicators.macdHistogramRecent(
                    candles, MACD_FAST, MACD_SLOW, MACD_SIGNAL, HIST_BEARISH_BARS);
            if (recentHist.length >= HIST_BEARISH_BARS) {
                boolean allNeg = true;
                for (double h : recentHist) {
                    if (h >= 0) { allNeg = false; break; }
                }
                if (allNeg) {
                    String reason = String.format(Locale.ROOT,
                            "MCM_MOMENTUM_LOSS hist %d봉 음수 pnl=%.2f%%",
                            HIST_BEARISH_BARS, pnlPct);
                    return Signal.of(SignalAction.SELL, type(), reason);
                }
            }
        }

        return Signal.none();
    }
}
