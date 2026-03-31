package com.example.stocks.market;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class StockCandle {
    /** 종목 심볼 (예: "005930", "AAPL") */
    public String symbol;
    /** 시장 유형 ("KRX", "NYSE", "NASDAQ") */
    public String marketType;

    // UpbitCandle 호환 필드명 (전략 레이어 재사용)
    public String candle_date_time_utc;
    public double opening_price;
    public double high_price;
    public double low_price;
    public double trade_price;
    public double candle_acc_trade_volume;
}
