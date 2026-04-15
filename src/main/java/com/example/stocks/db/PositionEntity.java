package com.example.stocks.db;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "position")
public class PositionEntity {

    @Id
    @Column(name = "symbol", length = 20)
    private String symbol;

    @Column(name = "market_type", nullable = false)
    private String marketType = "KRX";

    @Column(name = "qty", nullable = false)
    private int qty = 0;

    @Column(name = "avg_price", nullable = false, precision = 28, scale = 8)
    private BigDecimal avgPrice = BigDecimal.ZERO;

    @Column(name = "add_buys", nullable = false)
    private int addBuys = 0;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "entry_strategy", length = 100)
    private String entryStrategy;

    @Column(name = "scanner_source", length = 30)
    private String scannerSource = "MAIN";

    // V34: Split-Exit 분할 익절 지원
    @Column(name = "split_phase")
    private int splitPhase = 0;   // 0=미분할, 1=1차매도완료(잔량보유)

    @Column(name = "split_original_qty")
    private int splitOriginalQty = 0;  // 분할 전 원래 수량

    // --- Getters & Setters ---

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getMarketType() { return marketType; }
    public void setMarketType(String marketType) { this.marketType = marketType; }

    public int getQty() { return qty; }
    public void setQty(int qty) { this.qty = qty; }

    public BigDecimal getAvgPrice() { return avgPrice; }
    public void setAvgPrice(BigDecimal avgPrice) { this.avgPrice = avgPrice == null ? BigDecimal.ZERO : avgPrice; }
    public void setAvgPrice(double avgPrice) { this.avgPrice = BigDecimal.valueOf(avgPrice); }

    public int getAddBuys() { return addBuys; }
    public void setAddBuys(int addBuys) { this.addBuys = addBuys; }

    public Instant getOpenedAt() { return openedAt; }
    public void setOpenedAt(Instant openedAt) { this.openedAt = openedAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getEntryStrategy() { return entryStrategy; }
    public void setEntryStrategy(String entryStrategy) { this.entryStrategy = entryStrategy; }

    public String getScannerSource() { return scannerSource != null ? scannerSource : "MAIN"; }
    public void setScannerSource(String scannerSource) { this.scannerSource = scannerSource != null ? scannerSource : "MAIN"; }

    public int getSplitPhase() { return splitPhase; }
    public void setSplitPhase(int splitPhase) { this.splitPhase = splitPhase; }

    public int getSplitOriginalQty() { return splitOriginalQty; }
    public void setSplitOriginalQty(int splitOriginalQty) { this.splitOriginalQty = splitOriginalQty; }
}
