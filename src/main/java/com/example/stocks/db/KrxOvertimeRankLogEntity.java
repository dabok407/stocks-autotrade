package com.example.stocks.db;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 시간외거래순위 일일 수집 로그.
 * 매일 18:05 KST에 KIS API(FHPST02340000) 결과 저장.
 */
@Entity
@Table(name = "krx_overtime_rank_log")
public class KrxOvertimeRankLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    @Column(name = "rank_no", nullable = false)
    private int rankNo;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "symbol_name", length = 50)
    private String symbolName;

    @Column(name = "current_price", precision = 15, scale = 2)
    private BigDecimal currentPrice;

    @Column(name = "change_pct", precision = 8, scale = 4)
    private BigDecimal changePct;

    @Column(name = "volume")
    private Long volume;

    @Column(name = "trade_amount")
    private Long tradeAmount;

    @Column(name = "raw_json", columnDefinition = "TEXT")
    private String rawJson;

    // ========== Getters & Setters ==========

    public Long getId() { return id; }

    public LocalDate getTradeDate() { return tradeDate; }
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }

    public Instant getCollectedAt() { return collectedAt; }
    public void setCollectedAt(Instant collectedAt) { this.collectedAt = collectedAt; }

    public int getRankNo() { return rankNo; }
    public void setRankNo(int rankNo) { this.rankNo = rankNo; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getSymbolName() { return symbolName; }
    public void setSymbolName(String symbolName) { this.symbolName = symbolName; }

    public BigDecimal getCurrentPrice() { return currentPrice; }
    public void setCurrentPrice(BigDecimal currentPrice) { this.currentPrice = currentPrice; }

    public BigDecimal getChangePct() { return changePct; }
    public void setChangePct(BigDecimal changePct) { this.changePct = changePct; }

    public Long getVolume() { return volume; }
    public void setVolume(Long volume) { this.volume = volume; }

    public Long getTradeAmount() { return tradeAmount; }
    public void setTradeAmount(Long tradeAmount) { this.tradeAmount = tradeAmount; }

    public String getRawJson() { return rawJson; }
    public void setRawJson(String rawJson) { this.rawJson = rawJson; }
}
