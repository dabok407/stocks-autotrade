package com.example.stocks.exchange;

import com.example.stocks.market.MarketType;
import com.example.stocks.market.StockCandle;

import java.util.List;
import java.util.Map;

/**
 * 거래소 API 추상화 인터페이스.
 * 한국투자증권, 키움증권 등 증권사 교체 시 이 인터페이스만 구현하면 됨.
 */
public interface ExchangeAdapter {

    /**
     * 증권사 이름 ("KIS", "KIWOOM" 등).
     */
    String getName();

    // =====================================================================
    // 인증
    // =====================================================================

    /**
     * API 자격증명이 설정되어 있는지 확인.
     */
    boolean isConfigured();

    /**
     * 인증 토큰 갱신 (필요 시 호출).
     */
    void refreshAuth();

    // =====================================================================
    // 시세
    // =====================================================================

    /**
     * 분봉 캔들 조회.
     *
     * @param symbol     종목 심볼 (KRX: "005930", US: "AAPL")
     * @param marketType 시장 유형
     * @param unitMin    분봉 단위 (1, 3, 5, 10, 15, 30, 60)
     * @param count      조회 개수
     * @return 오래된 -> 최신 순 정렬된 캔들 리스트
     */
    List<StockCandle> getMinuteCandles(String symbol, MarketType marketType, int unitMin, int count);

    /**
     * 현재가 조회.
     * OHLC 모두 현재가로 설정된 StockCandle을 반환한다.
     *
     * @param symbol     종목 심볼
     * @param marketType 시장 유형
     * @return 현재가 정보가 담긴 StockCandle
     */
    StockCandle getCurrentPrice(String symbol, MarketType marketType);

    /**
     * 일봉 캔들 조회.
     *
     * @param symbol     종목 심볼
     * @param marketType 시장 유형
     * @param count      조회 개수
     * @return 오래된 -> 최신 순 정렬된 캔들 리스트
     */
    List<StockCandle> getDayCandles(String symbol, MarketType marketType, int count);

    /**
     * 마켓 종목 카탈로그 조회.
     *
     * @param marketType 시장 유형
     * @return symbol -> name 매핑
     */
    Map<String, String> getMarketCatalog(MarketType marketType);

    /**
     * 거래대금 상위 종목 조회.
     *
     * @param topN       조회할 상위 종목 수
     * @param marketType 시장 유형
     * @return 거래대금 순으로 정렬된 종목 심볼 리스트
     */
    List<String> getTopSymbolsByVolume(int topN, MarketType marketType);

    /**
     * 거래대금 상위 종목 조회 (심볼+종목명 포함).
     *
     * @param topN       조회할 상위 종목 수
     * @param marketType 시장 유형
     * @return 거래대금 순 정렬된 {symbol, name} 맵 리스트
     */
    List<Map<String, String>> getTopSymbolsWithName(int topN, MarketType marketType);

    // =====================================================================
    // 주문
    // =====================================================================

    /**
     * 매수 주문 (지정가).
     *
     * @param symbol     종목 심볼
     * @param marketType 시장 유형
     * @param qty        주문 수량
     * @param price      주문 가격
     * @return 주문 결과
     */
    OrderResult placeBuyOrder(String symbol, MarketType marketType, int qty, double price);

    /**
     * 매수 주문 (주문구분 지정 — P0-Fix#2).
     *
     * @param ordType "00"=지정가, "01"=시장가
     */
    default OrderResult placeBuyOrder(String symbol, MarketType marketType, int qty, double price, String ordType) {
        return placeBuyOrder(symbol, marketType, qty, price);
    }

    /**
     * 매도 주문 (지정가).
     *
     * @param symbol     종목 심볼
     * @param marketType 시장 유형
     * @param qty        매도 수량
     * @param price      매도 가격
     * @return 주문 결과
     */
    OrderResult placeSellOrder(String symbol, MarketType marketType, int qty, double price);

    /**
     * 매도 주문 (주문구분 지정 — P0-Fix#2).
     *
     * SL/SL_WIDE/TIME_STOP/SESSION_END 같이 시간이 중요한 청산은 ordType="01"(시장가) 사용.
     * TP_TRAIL/SPLIT_1ST 같은 익절은 ordType="00"(지정가) 유지.
     *
     * @param ordType "00"=지정가, "01"=시장가
     */
    default OrderResult placeSellOrder(String symbol, MarketType marketType, int qty, double price, String ordType) {
        return placeSellOrder(symbol, marketType, qty, price);
    }

    /**
     * 주문 상태 조회.
     *
     * @param orderNo 주문번호
     * @return 주문 결과
     */
    OrderResult getOrderStatus(String orderNo);

    // =====================================================================
    // 잔고
    // =====================================================================

    /**
     * 계좌 보유 종목 조회.
     *
     * @param marketType 시장 유형
     * @return 보유 종목 리스트
     */
    List<AccountPosition> getBalance(MarketType marketType);
}
