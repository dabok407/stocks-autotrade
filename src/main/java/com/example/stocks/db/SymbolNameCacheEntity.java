package com.example.stocks.db;

import javax.persistence.*;

/**
 * 심볼 → 한글 종목명 영속 캐시.
 * KIS inquire-price의 hts_kor_isnm 등에서 조회한 결과를 저장.
 */
@Entity
@Table(name = "symbol_name_cache")
public class SymbolNameCacheEntity {

    @Id
    @Column(name = "symbol", length = 20)
    private String symbol;

    @Column(name = "market_type", nullable = false, length = 10)
    private String marketType;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getMarketType() { return marketType; }
    public void setMarketType(String marketType) { this.marketType = marketType; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
