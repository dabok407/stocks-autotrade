package com.example.stocks.db;

import javax.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "order_log")
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "identifier", nullable = false, unique = true, length = 64)
    private String identifier;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "market_type", nullable = false, length = 10)
    private String marketType = "KRX";

    @Column(name = "side", nullable = false, length = 10)
    private String side;

    @Column(name = "ord_type", length = 20)
    private String ordType;

    @Column(name = "price", precision = 28, scale = 8)
    private BigDecimal price;

    @Column(name = "qty")
    private Integer qty;

    @Column(name = "uuid", length = 64)
    private String uuid;

    @Column(name = "state", length = 20)
    private String state;

    @Column(name = "executed_volume")
    private Integer executedVolume;

    @Column(name = "avg_price", precision = 28, scale = 8)
    private BigDecimal avgPrice;

    @Column(name = "ts_epoch_ms", nullable = false)
    private long tsEpochMs;

    // --- Getters & Setters ---

    public Long getId() { return id; }

    public String getIdentifier() { return identifier; }
    public void setIdentifier(String identifier) { this.identifier = identifier; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getMarketType() { return marketType; }
    public void setMarketType(String marketType) { this.marketType = marketType; }

    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }

    public String getOrdType() { return ordType; }
    public void setOrdType(String ordType) { this.ordType = ordType; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public Integer getQty() { return qty; }
    public void setQty(Integer qty) { this.qty = qty; }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public Integer getExecutedVolume() { return executedVolume; }
    public void setExecutedVolume(Integer executedVolume) { this.executedVolume = executedVolume; }

    public BigDecimal getAvgPrice() { return avgPrice; }
    public void setAvgPrice(BigDecimal avgPrice) { this.avgPrice = avgPrice; }

    public long getTsEpochMs() { return tsEpochMs; }
    public void setTsEpochMs(long tsEpochMs) { this.tsEpochMs = tsEpochMs; }
}
