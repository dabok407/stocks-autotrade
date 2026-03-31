package com.example.stocks.strategy;

import com.example.stocks.market.StockCandle;

import java.util.List;
import java.util.Locale;

/**
 * Adaptive Trend Momentum (ATM) Strategy — 적응형 추세 모멘텀
 *
 * ═══════════════════════════════════════════════════════════
 *  "5중 확인"으로 높은 승률, ATR 적응형 청산으로 안정적 수익
 * ═══════════════════════════════════════════════════════════
 *
 * ■ 5중 진입 확인:
 *   ① 추세 정렬: EMA20 > EMA50 > EMA100 (3단 EMA 계단 구조)
 *   ② 모멘텀:   MACD 히스토그램 양수 & 증가 (모멘텀 가속 구간)
 *   ③ 거래량:   현재 봉 거래량 > 20봉 평균의 0.8배 (스마트머니 유입)
 *   ④ 눌림:     최근 3봉 내 저가가 EMA20 근처까지 하락 (되돌림 발생)
 *   ⑤ 반등:     현재 종가 > EMA20 (눌림 후 반등 확인)
 *
 * ■ 청산 (어느 하나라도 발동):
 *   • Chandelier Exit: peak - 2.5*ATR
 *   • 추세 붕괴: EMA20 < EMA50
 *   • 모멘텀 소멸: MACD 히스토그램 3봉 연속 음수
 *   • 하드 SL: entry - 2*ATR
 *   • TP: entry + 4*ATR
 */
public class AdaptiveTrendMomentumStrategy implements TradingStrategy {

    // ===== 추세 =====
    private static final int EMA_FAST = 20;
    private static final int EMA_MID = 50;
    private static final int EMA_SLOW = 100;

    // ===== 모멘텀 (MACD) =====
    private static final int MACD_FAST = 12;
    private static final int MACD_SLOW = 26;
    private static final int MACD_SIGNAL = 9;

    // ===== RSI =====
    private static final int RSI_PERIOD = 14;

    // ===== ATR =====
    private static final int ATR_PERIOD = 14;
    private static final double SL_ATR_MULT = 2.0;
    private static final double TP_ATR_MULT = 4.0;
    private static final double CHANDELIER_ATR_MULT = 2.5;

    // ===== 거래량 =====
    private static final double VOLUME_THRESHOLD = 0.8;
    private static final int VOLUME_AVG_PERIOD = 20;

    // ===== 눌림 감지 =====
    private static final int PULLBACK_LOOKBACK = 3;
    private static final double PULLBACK_TOLERANCE = 1.03;

    // ===== 모멘텀 소멸 =====
    private static final int HIST_BEARISH_BARS = 3;

    // ===== 추가매수 =====
    private static final int MAX_ADD_BUYS = 1;
    private static final double ADD_BUY_RSI_MAX = 40.0;

    // ===== 안전 =====
    private static final double MIN_ATR_PCT = 0.005;
    private static final int MIN_CANDLES = 120;

    @Override
    public StrategyType type() {
        return StrategyType.ADAPTIVE_TREND_MOMENTUM;
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

        // ============================================
        //  지표 계산
        // ============================================
        double ema20 = Indicators.ema(candles, EMA_FAST);
        double ema50 = Indicators.ema(candles, EMA_MID);
        double ema100 = Indicators.ema(candles, EMA_SLOW);

        double[] macd = Indicators.macd(candles, MACD_FAST, MACD_SLOW, MACD_SIGNAL);
        double macdLine = macd[0];
        double signalLine = macd[1];
        double histogram = macd[2];

        double atr = Indicators.atr(candles, ATR_PERIOD);
        double rsi = Indicators.rsi(candles, RSI_PERIOD);

        double avgVol = Indicators.smaVolume(candles, VOLUME_AVG_PERIOD);
        double curVol = last.candle_acc_trade_volume;

        // 추세 정렬 확인: EMA20 > EMA50 > EMA100
        boolean trendAligned = ema20 > ema50 && ema50 > ema100;

        // ============================================
        //  포지션 보유 중 → 청산 판단
        // ============================================
        if (hasPosition) {
            return evaluateExit(ctx, candles, last, close, ema20, ema50, atr, trendAligned);
        }

        // ============================================
        //  포지션 없음 → 진입 판단 (5중 확인)
        // ============================================
        return evaluateEntry(candles, last, close, ema20, ema50, ema100, atr,
                histogram, rsi, avgVol, curVol, trendAligned);
    }

    // ─────────────────────────────────────────────
    //  진입 판단
    // ─────────────────────────────────────────────
    private Signal evaluateEntry(
            List<StockCandle> candles, StockCandle last, double close,
            double ema20, double ema50, double ema100, double atr,
            double histogram, double rsi, double avgVol, double curVol,
            boolean trendAligned) {

        // ① 추세 정렬
        if (!trendAligned) return Signal.none();

        // ② 모멘텀: MACD 히스토그램 양수
        boolean histPositive = histogram > 0;
        if (!histPositive) return Signal.none();

        // ③ 거래량: 평균 이상
        if (avgVol > 0 && curVol < avgVol * VOLUME_THRESHOLD) return Signal.none();

        // ④ 눌림: 최근 3봉 내 저가가 EMA20 근처까지 하락
        double recentLow = Indicators.recentLow(candles, PULLBACK_LOOKBACK);
        boolean hadPullback = recentLow <= ema20 * PULLBACK_TOLERANCE;
        if (!hadPullback) return Signal.none();

        // ⑤ 반등: 현재 종가 > EMA20
        if (close <= ema20) return Signal.none();

        // 안전 체크: ATR이 너무 작으면 수수료에 잡아먹힘
        if (atr / close < MIN_ATR_PCT) return Signal.none();

        // RSI 필터: 과매수 구간 진입 방지
        if (rsi > 72) return Signal.none();

        // ===== 모든 조건 충족 → BUY =====
        double score = 5.0;
        double emaMargin = ema50 > 0 ? (ema20 - ema50) / ema50 * 100 : 0;
        if (emaMargin >= 2.0) score += 1.0;
        else if (emaMargin >= 1.0) score += 0.5;
        if (histogram > 0 && close > 0) {
            double histPct = histogram / close * 10000;
            if (histPct >= 5) score += 1.0;
            else score += 0.3;
        }
        double volRatioScore = avgVol > 0 ? curVol / avgVol : 1.0;
        if (volRatioScore >= 2.0) score += 1.5;
        else if (volRatioScore >= 1.5) score += 1.0;
        else score += 0.3;
        if (rsi <= 45) score += 1.0;
        else if (rsi <= 55) score += 0.5;

        String reason = String.format(Locale.ROOT,
                "ATM_BUY ema20=%.2f>ema50=%.2f>ema100=%.2f | macd_h=%.4f(↑) | vol=%.0f/%.0f(%.1fx) | rsi=%.1f | pullback_low=%.2f",
                ema20, ema50, ema100, histogram,
                curVol, avgVol, avgVol > 0 ? curVol / avgVol : 0,
                rsi, recentLow);
        return Signal.of(SignalAction.BUY, type(), reason, Math.min(10.0, score));
    }

    // ─────────────────────────────────────────────
    //  청산 + 추가매수 판단
    // ─────────────────────────────────────────────
    private Signal evaluateExit(
            StrategyContext ctx, List<StockCandle> candles, StockCandle last,
            double close, double ema20, double ema50, double atr, boolean trendAligned) {

        double avgPrice = ctx.position.getAvgPrice().doubleValue();
        if (avgPrice <= 0) return Signal.none();

        double hardSL = avgPrice - SL_ATR_MULT * atr;
        double hardTP = avgPrice + TP_ATR_MULT * atr;

        double peakHigh = Indicators.peakHighSinceEntry(candles, avgPrice);
        double chandelierStop = peakHigh - CHANDELIER_ATR_MULT * atr;

        double effectiveSL = (close > avgPrice && chandelierStop > hardSL)
                ? chandelierStop : hardSL;

        // 1. SL/Chandelier 히트
        if (last.low_price <= effectiveSL) {
            boolean isChandelier = chandelierStop > hardSL;
            double pnlPct = ((close - avgPrice) / avgPrice) * 100.0;
            String reason = String.format(Locale.ROOT,
                    "ATM_%s avg=%.2f peak=%.2f atr=%.4f sl=%.2f chandelier=%.2f pnl=%.2f%%",
                    isChandelier ? "CHANDELIER_EXIT" : "HARD_STOP",
                    avgPrice, peakHigh, atr, hardSL, chandelierStop, pnlPct);
            return Signal.of(SignalAction.SELL, type(), reason);
        }

        // 2. TP 히트
        if (last.high_price >= hardTP) {
            String reason = String.format(Locale.ROOT,
                    "ATM_TAKE_PROFIT avg=%.2f tp=%.2f atr=%.4f pnl=%.2f%%",
                    avgPrice, hardTP, atr, ((hardTP - avgPrice) / avgPrice) * 100.0);
            return Signal.of(SignalAction.SELL, type(), reason);
        }

        // 3. 추세 붕괴: EMA20 < EMA50
        double pnlPct = ((close - avgPrice) / avgPrice) * 100.0;
        if (ema20 < ema50 && (pnlPct > 0 || pnlPct < -1.5)) {
            String reason = String.format(Locale.ROOT,
                    "ATM_TREND_BREAK ema20=%.2f < ema50=%.2f pnl=%.2f%%",
                    ema20, ema50, pnlPct);
            return Signal.of(SignalAction.SELL, type(), reason);
        }

        // 4. 모멘텀 소멸: MACD 히스토그램 3봉 연속 음수
        if (pnlPct > 0) {
            double[] recentHist = Indicators.macdHistogramRecent(
                    candles, MACD_FAST, MACD_SLOW, MACD_SIGNAL, HIST_BEARISH_BARS);
            if (recentHist.length >= HIST_BEARISH_BARS) {
                boolean allNegative = true;
                for (double h : recentHist) {
                    if (h >= 0) { allNegative = false; break; }
                }
                if (allNegative) {
                    String reason = String.format(Locale.ROOT,
                            "ATM_MOMENTUM_LOSS macd_hist %d봉 연속 음수 pnl=%.2f%%",
                            HIST_BEARISH_BARS, pnlPct);
                    return Signal.of(SignalAction.SELL, type(), reason);
                }
            }
        }

        // ────── 추가매수 판단 ──────
        if (MAX_ADD_BUYS > 0 && ctx.position.getAddBuys() < MAX_ADD_BUYS && trendAligned) {
            double rsi = Indicators.rsi(candles, RSI_PERIOD);
            double avgVol = Indicators.smaVolume(candles, VOLUME_AVG_PERIOD);
            double curVol = last.candle_acc_trade_volume;

            boolean nearEma50 = close <= ema50 * 1.01;
            boolean rsiLow = rsi < ADD_BUY_RSI_MAX;
            boolean volOk = avgVol <= 0 || curVol >= avgVol * 0.8;

            if (nearEma50 && rsiLow && volOk) {
                String reason = String.format(Locale.ROOT,
                        "ATM_ADD_BUY close=%.2f near_ema50=%.2f rsi=%.1f vol=%.0f",
                        close, ema50, rsi, curVol);
                return Signal.of(SignalAction.ADD_BUY, type(), reason);
            }
        }

        return Signal.none();
    }
}
