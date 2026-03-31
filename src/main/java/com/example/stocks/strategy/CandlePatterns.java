package com.example.stocks.strategy;

import com.example.stocks.market.StockCandle;

/**
 * 캔들 패턴 판별 유틸.
 */
public final class CandlePatterns {

    private CandlePatterns() {}

    public static boolean isBullish(StockCandle c) { return c.trade_price > c.opening_price; }
    public static boolean isBearish(StockCandle c) { return c.trade_price < c.opening_price; }

    public static double body(StockCandle c) { return Math.abs(c.trade_price - c.opening_price); }
    public static double range(StockCandle c) { return (c.high_price - c.low_price); }
    public static double upperWick(StockCandle c) {
        double top = Math.max(c.opening_price, c.trade_price);
        return Math.max(0.0, c.high_price - top);
    }
    public static double lowerWick(StockCandle c) {
        double bot = Math.min(c.opening_price, c.trade_price);
        return Math.max(0.0, bot - c.low_price);
    }

    /**
     * 장대(모멘텀) 캔들: 몸통이 range의 대부분을 차지.
     */
    public static boolean isMomentum(StockCandle c) {
        double r = range(c);
        if (r <= 0) return false;
        return body(c) / r >= 0.80;
    }

    /**
     * 장대(모멘텀) 캔들 -- ATR 기반 최소 크기 검증 포함.
     */
    public static boolean isMomentum(StockCandle c, double atr, double minAtrMult) {
        double r = range(c);
        if (r <= 0 || atr <= 0) return false;
        double b = body(c);
        return (b / r >= 0.80) && (b >= atr * minAtrMult);
    }

    /**
     * 모멘텀 캔들의 ATR 대비 강도.
     */
    public static double momentumStrength(StockCandle c, double atr) {
        if (atr <= 0) return 0;
        return body(c) / atr;
    }

    /**
     * 핀바(강세): 작은 몸통 + 긴 아래꼬리 + 위꼬리 짧음
     */
    public static boolean isBullishPinbar(StockCandle c) {
        double r = range(c);
        if (r <= 0) return false;
        double b = body(c);
        double lw = lowerWick(c);
        double uw = upperWick(c);
        return (b / r <= 0.35) && (lw / r >= 0.55) && (uw / r <= 0.20);
    }

    /**
     * 장악형(상승): 2번째 양봉 몸통이 1번째 음봉 몸통을 완전히 덮음.
     */
    public static boolean isBullishEngulfing(StockCandle firstBear, StockCandle secondBull) {
        if (!isBearish(firstBear) || !isBullish(secondBull)) return false;

        double r1 = range(firstBear);
        double r2 = range(secondBull);
        if (r1 <= 0 || body(firstBear) / r1 < 0.30) return false;
        if (r2 <= 0 || body(secondBull) / r2 < 0.50) return false;

        double firstBodyHigh = Math.max(firstBear.opening_price, firstBear.trade_price);
        double firstBodyLow  = Math.min(firstBear.opening_price, firstBear.trade_price);
        double secondBodyHigh = Math.max(secondBull.opening_price, secondBull.trade_price);
        double secondBodyLow  = Math.min(secondBull.opening_price, secondBull.trade_price);

        return (secondBodyLow < firstBodyLow) && (secondBodyHigh > firstBodyHigh);
    }

    public static boolean isBearishEngulfing(StockCandle firstBull, StockCandle secondBear) {
        if (!isBullish(firstBull) || !isBearish(secondBear)) return false;

        double r1 = range(firstBull);
        double r2 = range(secondBear);
        if (r1 <= 0 || body(firstBull) / r1 < 0.30) return false;
        if (r2 <= 0 || body(secondBear) / r2 < 0.50) return false;

        double firstBodyHigh = Math.max(firstBull.opening_price, firstBull.trade_price);
        double firstBodyLow  = Math.min(firstBull.opening_price, firstBull.trade_price);
        double secondBodyHigh = Math.max(secondBear.opening_price, secondBear.trade_price);
        double secondBodyLow  = Math.min(secondBear.opening_price, secondBear.trade_price);

        return (secondBodyHigh > firstBodyHigh) && (secondBodyLow < firstBodyLow);
    }

    /**
     * 인사이드바: 두번째 캔들의 고가/저가가 첫번째(마더바) 범위 안에 존재
     */
    public static boolean isInsideBar(StockCandle mother, StockCandle inside) {
        return inside.high_price <= mother.high_price && inside.low_price >= mother.low_price;
    }

    /**
     * 모닝스타:
     * 1) 큰 음봉 -> 2) 작은 몸통(도지/짧은 몸통) -> 3) 큰 양봉
     */
    public static boolean isMorningStar(StockCandle c1, StockCandle c2, StockCandle c3) {
        if (!isBearish(c1) || !isBullish(c3)) return false;
        if (!isSmallBody(c2)) return false;

        double r1 = range(c1);
        double r3 = range(c3);
        if (r1 <= 0 || body(c1) / r1 < 0.40) return false;
        if (r3 <= 0 || body(c3) / r3 < 0.40) return false;

        double c1High = Math.max(c1.opening_price, c1.trade_price);
        return c3.trade_price >= c1High;
    }

    public static boolean isEveningStar(StockCandle c1, StockCandle c2, StockCandle c3) {
        if (!isBullish(c1) || !isBearish(c3)) return false;
        if (!isSmallBody(c2)) return false;

        double r1 = range(c1);
        double r3 = range(c3);
        if (r1 <= 0 || body(c1) / r1 < 0.40) return false;
        if (r3 <= 0 || body(c3) / r3 < 0.40) return false;

        double mid = c1.opening_price + (body(c1) * 0.5);
        return c3.trade_price <= mid;
    }

    public static boolean isSmallBody(StockCandle c) {
        double r = range(c);
        if (r <= 0) return false;
        return body(c) / r <= 0.25;
    }

    /**
     * 3연속 양봉(적삼병)
     */
    public static boolean isThreeWhiteSoldiers(StockCandle a, StockCandle b, StockCandle c) {
        if (!isBullish(a) || !isBullish(b) || !isBullish(c)) return false;
        if (a.trade_price >= b.trade_price) return false;
        if (b.trade_price >= c.trade_price) return false;

        double ra = range(a), rb = range(b), rc = range(c);
        if (ra <= 0 || rb <= 0 || rc <= 0) return false;

        if (body(a) / ra < 0.50 || body(b) / rb < 0.50 || body(c) / rc < 0.50) return false;

        return upperWick(a)/ra <= 0.25 && upperWick(b)/rb <= 0.25 && upperWick(c)/rc <= 0.25;
    }

    public static boolean isThreeBlackCrows(StockCandle a, StockCandle b, StockCandle c) {
        if (!isBearish(a) || !isBearish(b) || !isBearish(c)) return false;
        if (a.trade_price <= b.trade_price) return false;
        if (b.trade_price <= c.trade_price) return false;

        double ra = range(a), rb = range(b), rc = range(c);
        if (ra <= 0 || rb <= 0 || rc <= 0) return false;

        if (body(a) / ra < 0.50 || body(b) / rb < 0.50 || body(c) / rc < 0.50) return false;

        return lowerWick(a)/ra <= 0.25 && lowerWick(b)/rb <= 0.25 && lowerWick(c)/rc <= 0.25;
    }
}
