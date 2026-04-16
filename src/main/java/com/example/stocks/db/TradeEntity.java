package com.example.stocks.db;

import javax.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "trade_log")
public class TradeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ts_epoch_ms", nullable = false)
    private long tsEpochMs;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "market_type", nullable = false, length = 10)
    private String marketType = "KRX";

    @Column(name = "action", nullable = false, length = 20)
    private String action;

    @Column(name = "price", nullable = false, precision = 28, scale = 12)
    private BigDecimal price;

    @Column(name = "qty", nullable = false)
    private int qty;

    @Column(name = "pnl_krw", nullable = false, precision = 20, scale = 2)
    private BigDecimal pnlKrw = BigDecimal.ZERO;

    @Column(name = "roi_percent", nullable = false, precision = 16, scale = 6)
    private BigDecimal roiPercent = BigDecimal.ZERO;

    @Column(name = "mode", nullable = false, length = 10)
    private String mode;

    @Column(name = "note", length = 512)
    private String note;

    @Column(name = "pattern_type", length = 64)
    private String patternType;

    @Column(name = "pattern_reason", length = 512)
    private String patternReason;

    @Column(name = "avg_buy_price", precision = 28, scale = 12)
    private BigDecimal avgBuyPrice;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "candle_unit_min")
    private Integer candleUnitMin;

    @Column(name = "currency", length = 3)
    private String currency = "KRW";

    @Column(name = "scanner_source", length = 30)
    private String scannerSource = "MAIN";

    @Column(name = "settlement_date", length = 10)
    private String settlementDate;

    /** DB 비영속. API 응답 직전 enrich 용 (stock_config.displayName or rank_log.symbolName). */
    @Transient
    private String symbolName;

    // --- Getters & Setters ---

    public Long getId() { return id; }

    public long getTsEpochMs() { return tsEpochMs; }
    public void setTsEpochMs(long tsEpochMs) { this.tsEpochMs = tsEpochMs; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getMarketType() { return marketType; }
    public void setMarketType(String marketType) { this.marketType = marketType; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public void setPrice(double price) { this.price = BigDecimal.valueOf(price); }

    public int getQty() { return qty; }
    public void setQty(int qty) { this.qty = qty; }

    public BigDecimal getPnlKrw() { return pnlKrw; }
    public void setPnlKrw(BigDecimal pnlKrw) { this.pnlKrw = pnlKrw; }
    public void setPnlKrw(double pnlKrw) { this.pnlKrw = BigDecimal.valueOf(pnlKrw); }

    public BigDecimal getRoiPercent() { return roiPercent; }
    public void setRoiPercent(BigDecimal roiPercent) { this.roiPercent = roiPercent; }
    public void setRoiPercent(double roiPercent) { this.roiPercent = BigDecimal.valueOf(roiPercent); }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public String getPatternType() { return patternType; }
    public void setPatternType(String patternType) { this.patternType = patternType; }

    public String getPatternReason() { return patternReason; }
    public void setPatternReason(String patternReason) { this.patternReason = patternReason; }

    public BigDecimal getAvgBuyPrice() { return avgBuyPrice; }
    public void setAvgBuyPrice(BigDecimal avgBuyPrice) { this.avgBuyPrice = avgBuyPrice; }
    public void setAvgBuyPrice(double avgBuyPrice) { this.avgBuyPrice = BigDecimal.valueOf(avgBuyPrice); }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public Integer getCandleUnitMin() { return candleUnitMin; }
    public void setCandleUnitMin(Integer candleUnitMin) { this.candleUnitMin = candleUnitMin; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getScannerSource() { return scannerSource != null ? scannerSource : "MAIN"; }
    public void setScannerSource(String scannerSource) { this.scannerSource = scannerSource != null ? scannerSource : "MAIN"; }

    public String getSettlementDate() { return settlementDate; }
    public void setSettlementDate(String settlementDate) { this.settlementDate = settlementDate; }

    public String getSymbolName() { return symbolName; }
    public void setSymbolName(String symbolName) { this.symbolName = symbolName; }
}
