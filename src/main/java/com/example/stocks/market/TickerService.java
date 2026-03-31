package com.example.stocks.market;

import com.example.stocks.exchange.ExchangeAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 실시간 현재가 조회 서비스.
 * ExchangeAdapter를 통해 증권사에 독립적으로 현재가를 조회한다.
 */
@Service
public class TickerService {

    private final ExchangeAdapter exchange;

    @Autowired
    public TickerService(ExchangeAdapter exchange) {
        this.exchange = exchange;
    }

    /**
     * 종목의 현재가를 StockCandle 형태로 반환한다.
     * candle_date_time_utc는 현재 시각(UTC), OHLC는 모두 현재가로 설정.
     *
     * @param symbol    종목 심볼 (예: "005930", "AAPL")
     * @param marketType 시장 유형
     * @return 현재가 정보가 담긴 StockCandle
     */
    public StockCandle getCurrentPrice(String symbol, MarketType marketType) {
        return exchange.getCurrentPrice(symbol, marketType);
    }
}
