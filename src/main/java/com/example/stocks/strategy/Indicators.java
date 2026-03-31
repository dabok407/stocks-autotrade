package com.example.stocks.strategy;

import com.example.stocks.market.StockCandle;

import java.util.List;

/** 공통 기술적 지표 계산 */
public final class Indicators {
    private Indicators() {}

    // ======================== EMA ========================
    public static double ema(List<StockCandle> candles, int period) {
        if (candles == null || candles.isEmpty()) return Double.NaN;
        double k = 2.0 / (period + 1.0);
        double ema = candles.get(0).trade_price;
        for (int i = 1; i < candles.size(); i++) {
            double price = candles.get(i).trade_price;
            if (Double.isNaN(price) || Double.isInfinite(price)) continue;
            ema = price * k + ema * (1.0 - k);
        }
        return (Double.isNaN(ema) || Double.isInfinite(ema)) ? Double.NaN : ema;
    }

    /** 지정 구간(subList)의 EMA. 전체 리스트에서 마지막 N개를 넘겨도 되지만, 정확도를 위해 가능하면 전체를 넘길 것. */
    public static double ema(List<StockCandle> candles, int period, int tailCount) {
        if (candles == null || candles.size() < tailCount) return Double.NaN;
        List<StockCandle> sub = candles.subList(Math.max(0, candles.size() - tailCount), candles.size());
        return ema(sub, period);
    }

    // ======================== SMA ========================
    public static double sma(List<StockCandle> candles, int period) {
        if (candles == null || candles.size() < period) return Double.NaN;
        double sum = 0;
        for (int i = candles.size() - period; i < candles.size(); i++) {
            sum += candles.get(i).trade_price;
        }
        return sum / period;
    }

    public static double smaVolume(List<StockCandle> candles, int period) {
        if (candles == null || candles.isEmpty()) return Double.NaN;
        int n = Math.min(period, candles.size());
        double sum = 0.0;
        for (int i = candles.size() - n; i < candles.size(); i++) {
            sum += candles.get(i).candle_acc_trade_volume;
        }
        return sum / n;
    }

    // ======================== RSI ========================
    /**
     * Wilder RSI (정식 구현). candles는 오래된->최신 순서.
     */
    public static double rsi(List<StockCandle> candles, int period) {
        if (candles == null || candles.size() < period + 1) return 50.0;

        int start = 0;

        // Step 1: 첫 period 구간의 초기 avgGain/avgLoss (SMA)
        double gainSum = 0, lossSum = 0;
        for (int i = start + 1; i <= start + period; i++) {
            double diff = candles.get(i).trade_price - candles.get(i - 1).trade_price;
            if (diff >= 0) gainSum += diff;
            else lossSum -= diff;
        }
        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;

        // Step 2: Wilder 스무딩 연속 적용 (나머지 봉들)
        for (int i = start + period + 1; i < candles.size(); i++) {
            double diff = candles.get(i).trade_price - candles.get(i - 1).trade_price;
            double gain = diff >= 0 ? diff : 0;
            double loss = diff < 0 ? -diff : 0;
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
        }

        // Step 3: RS -> RSI
        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    // ======================== ATR ========================
    /** Average True Range (단순 평균). candles는 오래된->최신 순서. */
    public static double atr(List<StockCandle> candles, int period) {
        if (candles == null || candles.size() < period + 1) return 0.0;
        int start = Math.max(1, candles.size() - period);
        double sum = 0;
        for (int i = start; i < candles.size(); i++) {
            StockCandle c = candles.get(i);
            StockCandle p = candles.get(i - 1);
            double tr = Math.max(c.high_price - c.low_price,
                    Math.max(Math.abs(c.high_price - p.trade_price),
                             Math.abs(c.low_price - p.trade_price)));
            sum += tr;
        }
        return sum / period;
    }

    // ======================== ADX ========================
    /**
     * ADX (Wilder smoothing).
     * 최소 period*2+1 개의 캔들이 필요.
     */
    public static double adx(List<StockCandle> candles, int period) {
        if (candles == null || candles.size() < period * 2 + 1) return 0.0;
        int n = candles.size();

        // 1. TR, +DM, -DM 계산
        double[] trArr = new double[n];
        double[] pdm = new double[n];
        double[] ndm = new double[n];
        for (int i = 1; i < n; i++) {
            StockCandle c = candles.get(i);
            StockCandle p = candles.get(i - 1);
            trArr[i] = Math.max(c.high_price - c.low_price,
                    Math.max(Math.abs(c.high_price - p.trade_price),
                             Math.abs(c.low_price - p.trade_price)));
            double up = c.high_price - p.high_price;
            double dn = p.low_price - c.low_price;
            pdm[i] = (up > dn && up > 0) ? up : 0;
            ndm[i] = (dn > up && dn > 0) ? dn : 0;
        }

        // 2. Wilder smoothing
        int startIdx = n - period * 2;
        if (startIdx < 1) startIdx = 1;

        double smoothTR = 0, smoothPDM = 0, smoothNDM = 0;
        for (int i = startIdx; i < startIdx + period; i++) {
            smoothTR += trArr[i];
            smoothPDM += pdm[i];
            smoothNDM += ndm[i];
        }

        // DX 시리즈를 구해서 평균
        double dxSum = 0;
        int dxCount = 0;
        for (int i = startIdx + period; i < n; i++) {
            smoothTR = smoothTR - (smoothTR / period) + trArr[i];
            smoothPDM = smoothPDM - (smoothPDM / period) + pdm[i];
            smoothNDM = smoothNDM - (smoothNDM / period) + ndm[i];

            if (smoothTR == 0) continue;
            double pdi = 100.0 * smoothPDM / smoothTR;
            double ndi = 100.0 * smoothNDM / smoothTR;
            double diSum = pdi + ndi;
            if (diSum == 0) continue;
            double dx = 100.0 * Math.abs(pdi - ndi) / diSum;
            dxSum += dx;
            dxCount++;
        }
        return dxCount == 0 ? 0 : dxSum / dxCount;
    }

    // ======================== Bollinger Bands ========================
    /** @return [lower, middle, upper] */
    public static double[] bollinger(List<StockCandle> candles, int period, double stdMult) {
        if (candles == null || candles.size() < period) return new double[]{0, 0, 0};
        int start = candles.size() - period;

        double sum = 0;
        for (int i = start; i < candles.size(); i++) sum += candles.get(i).trade_price;
        double mid = sum / period;

        double varSum = 0;
        for (int i = start; i < candles.size(); i++) {
            double d = candles.get(i).trade_price - mid;
            varSum += d * d;
        }
        double std = Math.sqrt(varSum / period);

        return new double[]{mid - stdMult * std, mid, mid + stdMult * std};
    }

    // ======================== 피크 가격 (진입 이후 최고가) ========================
    public static double peakHighSinceEntry(List<StockCandle> candles, double avgPrice) {
        if (candles == null || candles.isEmpty()) return avgPrice;
        double threshold = avgPrice * 0.01;
        int entryIdx = -1;

        for (int i = candles.size() - 1; i >= 0; i--) {
            StockCandle c = candles.get(i);
            if (c.low_price <= avgPrice + threshold && c.high_price >= avgPrice - threshold) {
                entryIdx = i;
                break;
            }
        }

        if (entryIdx < 0) {
            entryIdx = Math.max(0, candles.size() - 50);
        }

        double peak = avgPrice;
        for (int i = entryIdx; i < candles.size(); i++) {
            if (candles.get(i).high_price > peak) peak = candles.get(i).high_price;
        }
        return peak;
    }

    // ======================== MACD ========================
    /**
     * MACD 계산.
     * @return [macdLine, signalLine, histogram]
     */
    public static double[] macd(List<StockCandle> candles, int fastP, int slowP, int signalP) {
        if (candles == null || candles.size() < slowP + signalP) return new double[]{0, 0, 0};
        int n = candles.size();

        double kF = 2.0 / (fastP + 1.0);
        double kS = 2.0 / (slowP + 1.0);
        double kSig = 2.0 / (signalP + 1.0);

        double emaF = candles.get(0).trade_price;
        double emaS = candles.get(0).trade_price;
        double sig = 0;

        for (int i = 1; i < n; i++) {
            double p = candles.get(i).trade_price;
            emaF = p * kF + emaF * (1.0 - kF);
            emaS = p * kS + emaS * (1.0 - kS);
            double macdVal = emaF - emaS;
            if (i == 1) sig = macdVal;
            else sig = macdVal * kSig + sig * (1.0 - kSig);
        }

        double macdLine = emaF - emaS;
        return new double[]{macdLine, sig, macdLine - sig};
    }

    /** 표준 MACD (12, 26, 9) */
    public static double[] macd(List<StockCandle> candles) {
        return macd(candles, 12, 26, 9);
    }

    /**
     * 최근 N봉의 MACD 히스토그램 값 배열. [oldest ... newest]
     */
    public static double[] macdHistogramRecent(List<StockCandle> candles, int fastP, int slowP, int signalP, int count) {
        if (candles == null || candles.size() < slowP + signalP + count) return new double[0];
        int n = candles.size();

        double kF = 2.0 / (fastP + 1.0);
        double kS = 2.0 / (slowP + 1.0);
        double kSig = 2.0 / (signalP + 1.0);

        double emaF = candles.get(0).trade_price;
        double emaS = candles.get(0).trade_price;
        double sig = 0;

        double[] hist = new double[count];
        int writeStart = n - count;

        for (int i = 1; i < n; i++) {
            double p = candles.get(i).trade_price;
            emaF = p * kF + emaF * (1.0 - kF);
            emaS = p * kS + emaS * (1.0 - kS);
            double macdVal = emaF - emaS;
            if (i == 1) sig = macdVal;
            else sig = macdVal * kSig + sig * (1.0 - kSig);

            if (i >= writeStart) {
                hist[i - writeStart] = macdVal - sig;
            }
        }
        return hist;
    }

    // ======================== 시장 상태 감지 ========================
    /**
     * 시장 상태(Regime) 판단: BULL(1), RANGE(0), BEAR(-1).
     */
    public static int marketRegime(List<StockCandle> candles) {
        if (candles == null || candles.size() < 60) return 0;
        double ema20 = ema(candles, 20);
        double ema50 = ema(candles, 50);
        double adxVal = adx(candles, 14);
        if (Double.isNaN(ema20) || Double.isNaN(ema50) || adxVal <= 0) return 0;

        if (adxVal <= 20) return 0;
        return ema20 > ema50 ? 1 : -1;
    }

    // ======================== 최근 N봉 최저가 ========================
    /** 최근 lookback 봉(현재 봉 제외)의 최저 low */
    public static double recentLow(List<StockCandle> candles, int lookback) {
        if (candles == null || candles.size() < lookback + 1) return Double.MAX_VALUE;
        double low = Double.MAX_VALUE;
        for (int i = candles.size() - 1 - lookback; i < candles.size() - 1; i++) {
            if (candles.get(i).low_price < low) low = candles.get(i).low_price;
        }
        return low;
    }

    // ======================== 최근 N봉 최고가 ========================
    /** 최근 lookback 봉(현재 봉 제외)의 최고 high */
    public static double recentHigh(List<StockCandle> candles, int lookback) {
        if (candles == null || candles.size() < lookback + 1) return Double.MIN_VALUE;
        double high = Double.MIN_VALUE;
        for (int i = candles.size() - 1 - lookback; i < candles.size() - 1; i++) {
            if (i >= 0 && candles.get(i).high_price > high) high = candles.get(i).high_price;
        }
        return high;
    }

    // ======================== VWAP Bands ========================
    /**
     * VWAP with standard deviation bands.
     *
     * @param candles  candle list (oldest -> newest)
     * @param stdMult  standard deviation multiplier for bands
     * @return double[3] {lower, vwap, upper}
     */
    public static double[] vwapBands(List<StockCandle> candles, double stdMult) {
        if (candles == null || candles.isEmpty()) return new double[]{0, 0, 0};

        double sumPV = 0.0;
        double sumV = 0.0;

        // First pass: calculate VWAP
        for (int i = 0; i < candles.size(); i++) {
            StockCandle c = candles.get(i);
            double typicalPrice = (c.high_price + c.low_price + c.trade_price) / 3.0;
            double volume = c.candle_acc_trade_volume;
            sumPV += typicalPrice * volume;
            sumV += volume;
        }

        if (sumV <= 0) {
            double last = candles.get(candles.size() - 1).trade_price;
            return new double[]{last, last, last};
        }

        double vwapValue = sumPV / sumV;

        // Second pass: calculate volume-weighted variance of typical price from VWAP
        double sumVarPV = 0.0;
        for (int i = 0; i < candles.size(); i++) {
            StockCandle c = candles.get(i);
            double typicalPrice = (c.high_price + c.low_price + c.trade_price) / 3.0;
            double diff = typicalPrice - vwapValue;
            sumVarPV += diff * diff * c.candle_acc_trade_volume;
        }

        double std = Math.sqrt(sumVarPV / sumV);

        return new double[]{vwapValue - stdMult * std, vwapValue, vwapValue + stdMult * std};
    }

    // ======================== VWAP ========================
    /**
     * Volume Weighted Average Price (VWAP).
     * 전체 캔들 구간의 거래량 가중 평균 가격.
     * 일반적으로 장중 누적 VWAP에 사용 (일봉 단위 리셋).
     *
     * VWAP = Sum(TypicalPrice * Volume) / Sum(Volume)
     * TypicalPrice = (High + Low + Close) / 3
     *
     * @param candles 오래된 -> 최신 순 캔들 리스트
     * @return VWAP 값. 거래량이 0이면 마지막 종가 반환.
     */
    public static double vwap(List<StockCandle> candles) {
        if (candles == null || candles.isEmpty()) return Double.NaN;

        double sumPV = 0.0;
        double sumV = 0.0;

        for (int i = 0; i < candles.size(); i++) {
            StockCandle c = candles.get(i);
            double typicalPrice = (c.high_price + c.low_price + c.trade_price) / 3.0;
            double volume = c.candle_acc_trade_volume;
            sumPV += typicalPrice * volume;
            sumV += volume;
        }

        if (sumV <= 0) {
            return candles.get(candles.size() - 1).trade_price;
        }
        return sumPV / sumV;
    }
}
