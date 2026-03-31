package com.example.stocks.trade;

import com.example.stocks.market.MarketType;

/**
 * 호가 단위(tick size) 유틸리티.
 * 주문가를 해당 시장의 호가 단위에 맞게 반올림한다.
 */
public final class TickSizeUtil {

    private TickSizeUtil() {}

    /**
     * 주어진 가격을 시장별 호가 단위에 맞게 반올림한다.
     *
     * @param price      원래 가격
     * @param marketType 시장 유형
     * @return 호가 단위에 맞게 반올림된 가격
     */
    public static double roundToTickSize(double price, MarketType marketType) {
        double tickSize = getTickSize(price, marketType);
        if (tickSize <= 0) return price;
        return Math.round(price / tickSize) * tickSize;
    }

    /**
     * 가격과 시장에 따른 호가 단위를 반환한다.
     */
    public static double getTickSize(double price, MarketType marketType) {
        switch (marketType) {
            case KRX:
                return getKrxTickSize(price);
            case NYSE:
            case NASDAQ:
                return 0.01;
            default:
                return 0.01;
        }
    }

    /**
     * KRX(한국거래소) 호가 단위표 (2023년 기준).
     * <pre>
     *   가격 구간            호가 단위
     *   ~2,000원 미만        1원
     *   2,000~5,000원 미만   5원
     *   5,000~20,000원 미만  10원
     *   20,000~50,000원 미만 50원
     *   50,000~200,000원 미만 100원
     *   200,000~500,000원 미만 500원
     *   500,000원 이상       1,000원
     * </pre>
     */
    private static double getKrxTickSize(double price) {
        if (price < 2000) return 1;
        if (price < 5000) return 5;
        if (price < 20000) return 10;
        if (price < 50000) return 50;
        if (price < 200000) return 100;
        if (price < 500000) return 500;
        return 1000;
    }
}
