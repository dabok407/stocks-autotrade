package com.example.stocks.market;

import java.time.ZoneId;

public enum MarketType {

    KRX("KRW", "Asia/Seoul", "KRX (한국거래소)"),
    NYSE("USD", "America/New_York", "NYSE (뉴욕증권거래소)"),
    NASDAQ("USD", "America/New_York", "NASDAQ (나스닥)");

    private final String currency;
    private final String timezoneId;
    private final String displayName;

    MarketType(String currency, String timezoneId, String displayName) {
        this.currency = currency;
        this.timezoneId = timezoneId;
        this.displayName = displayName;
    }

    public String currency() {
        return currency;
    }

    public ZoneId timezone() {
        return ZoneId.of(timezoneId);
    }

    public String displayName() {
        return displayName;
    }
}
