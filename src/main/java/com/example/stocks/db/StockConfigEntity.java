package com.example.stocks.db;

import javax.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "stock_config")
public class StockConfigEntity {

    @Id
    @Column(name = "symbol", length = 20)
    private String symbol;

    @Column(name = "market_type", nullable = false)
    private String marketType = "KRX";

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "base_order_krw", nullable = false, precision = 20, scale = 2)
    private BigDecimal baseOrderKrw = BigDecimal.valueOf(100000);

    // --- Getters & Setters ---

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getMarketType() { return marketType; }
    public void setMarketType(String marketType) { this.marketType = marketType; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public BigDecimal getBaseOrderKrw() { return baseOrderKrw; }
    public void setBaseOrderKrw(BigDecimal baseOrderKrw) { this.baseOrderKrw = baseOrderKrw == null ? BigDecimal.ZERO : baseOrderKrw; }
}
