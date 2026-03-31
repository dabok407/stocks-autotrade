package com.example.stocks.db;

import javax.persistence.*;
import java.math.BigDecimal;

/**
 * KRX opening range breakout scanner config (single row, id=1).
 * KRX market hours: 09:00-15:30 KST
 * Range: 09:00-09:15, Entry: 09:15-10:30, Session end: 15:15
 */
@Entity
@Table(name = "krx_scanner_config")
public class KrxScannerConfigEntity {

    @Id
    @Column(name = "id")
    private int id = 1;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    @Column(name = "mode", nullable = false, length = 10)
    private String mode = "PAPER";

    @Column(name = "top_n", nullable = false)
    private int topN = 15;

    @Column(name = "max_positions", nullable = false)
    private int maxPositions = 3;

    @Column(name = "order_sizing_mode", nullable = false, length = 10)
    private String orderSizingMode = "PCT";

    @Column(name = "order_sizing_value", nullable = false, precision = 10, scale = 2)
    private BigDecimal orderSizingValue = BigDecimal.valueOf(30);

    @Column(name = "candle_unit_min", nullable = false)
    private int candleUnitMin = 5;

    // -- Timing (KRX market hours) --
    @Column(name = "range_start_hour", nullable = false)
    private int rangeStartHour = 9;

    @Column(name = "range_start_min", nullable = false)
    private int rangeStartMin = 0;

    @Column(name = "range_end_hour", nullable = false)
    private int rangeEndHour = 9;

    @Column(name = "range_end_min", nullable = false)
    private int rangeEndMin = 15;

    @Column(name = "entry_start_hour", nullable = false)
    private int entryStartHour = 9;

    @Column(name = "entry_start_min", nullable = false)
    private int entryStartMin = 15;

    @Column(name = "entry_end_hour", nullable = false)
    private int entryEndHour = 10;

    @Column(name = "entry_end_min", nullable = false)
    private int entryEndMin = 30;

    @Column(name = "session_end_hour", nullable = false)
    private int sessionEndHour = 15;

    @Column(name = "session_end_min", nullable = false)
    private int sessionEndMin = 15;

    // -- Risk parameters --
    @Column(name = "tp_atr_mult", nullable = false, precision = 5, scale = 2)
    private BigDecimal tpAtrMult = BigDecimal.valueOf(1.5);

    @Column(name = "sl_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal slPct = BigDecimal.valueOf(2.0);

    @Column(name = "trail_atr_mult", nullable = false, precision = 5, scale = 2)
    private BigDecimal trailAtrMult = BigDecimal.valueOf(0.6);

    // -- Filters --
    @Column(name = "kospi_filter_enabled", nullable = false)
    private boolean kospiFilterEnabled = true;

    @Column(name = "kospi_ema_period", nullable = false)
    private int kospiEmaPeriod = 20;

    @Column(name = "volume_mult", nullable = false, precision = 5, scale = 2)
    private BigDecimal volumeMult = BigDecimal.valueOf(1.5);

    @Column(name = "min_body_ratio", nullable = false, precision = 5, scale = 2)
    private BigDecimal minBodyRatio = BigDecimal.valueOf(0.45);

    @Column(name = "exclude_symbols", length = 1000)
    private String excludeSymbols = "";

    @Column(name = "min_price_krw", nullable = false)
    private int minPriceKrw = 1000;

    // ========== Getters & Setters ==========

    public int getId() { return id; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode != null ? mode.toUpperCase() : "PAPER"; }

    public int getTopN() { return topN; }
    public void setTopN(int topN) { this.topN = Math.max(1, Math.min(50, topN)); }

    public int getMaxPositions() { return maxPositions; }
    public void setMaxPositions(int maxPositions) { this.maxPositions = Math.max(1, Math.min(15, maxPositions)); }

    public String getOrderSizingMode() { return orderSizingMode; }
    public void setOrderSizingMode(String orderSizingMode) { this.orderSizingMode = orderSizingMode != null ? orderSizingMode.toUpperCase() : "PCT"; }

    public BigDecimal getOrderSizingValue() { return orderSizingValue; }
    public void setOrderSizingValue(BigDecimal orderSizingValue) { this.orderSizingValue = orderSizingValue != null ? orderSizingValue : BigDecimal.valueOf(30); }

    public int getCandleUnitMin() { return candleUnitMin; }
    public void setCandleUnitMin(int candleUnitMin) { this.candleUnitMin = candleUnitMin > 0 ? candleUnitMin : 5; }

    public int getRangeStartHour() { return rangeStartHour; }
    public void setRangeStartHour(int v) { this.rangeStartHour = v; }

    public int getRangeStartMin() { return rangeStartMin; }
    public void setRangeStartMin(int v) { this.rangeStartMin = v; }

    public int getRangeEndHour() { return rangeEndHour; }
    public void setRangeEndHour(int v) { this.rangeEndHour = v; }

    public int getRangeEndMin() { return rangeEndMin; }
    public void setRangeEndMin(int v) { this.rangeEndMin = v; }

    public int getEntryStartHour() { return entryStartHour; }
    public void setEntryStartHour(int v) { this.entryStartHour = v; }

    public int getEntryStartMin() { return entryStartMin; }
    public void setEntryStartMin(int v) { this.entryStartMin = v; }

    public int getEntryEndHour() { return entryEndHour; }
    public void setEntryEndHour(int v) { this.entryEndHour = v; }

    public int getEntryEndMin() { return entryEndMin; }
    public void setEntryEndMin(int v) { this.entryEndMin = v; }

    public int getSessionEndHour() { return sessionEndHour; }
    public void setSessionEndHour(int v) { this.sessionEndHour = v; }

    public int getSessionEndMin() { return sessionEndMin; }
    public void setSessionEndMin(int v) { this.sessionEndMin = v; }

    public BigDecimal getTpAtrMult() { return tpAtrMult; }
    public void setTpAtrMult(BigDecimal v) { this.tpAtrMult = v != null ? v : BigDecimal.valueOf(1.5); }

    public BigDecimal getSlPct() { return slPct; }
    public void setSlPct(BigDecimal v) { this.slPct = v != null ? v : BigDecimal.valueOf(2.0); }

    public BigDecimal getTrailAtrMult() { return trailAtrMult; }
    public void setTrailAtrMult(BigDecimal v) { this.trailAtrMult = v != null ? v : BigDecimal.valueOf(0.6); }

    public boolean isKospiFilterEnabled() { return kospiFilterEnabled; }
    public void setKospiFilterEnabled(boolean kospiFilterEnabled) { this.kospiFilterEnabled = kospiFilterEnabled; }

    public int getKospiEmaPeriod() { return kospiEmaPeriod; }
    public void setKospiEmaPeriod(int kospiEmaPeriod) { this.kospiEmaPeriod = Math.max(5, kospiEmaPeriod); }

    public BigDecimal getVolumeMult() { return volumeMult; }
    public void setVolumeMult(BigDecimal v) { this.volumeMult = v != null ? v : BigDecimal.valueOf(1.5); }

    public BigDecimal getMinBodyRatio() { return minBodyRatio; }
    public void setMinBodyRatio(BigDecimal v) { this.minBodyRatio = v != null ? v : BigDecimal.valueOf(0.45); }

    public int getMinPriceKrw() { return minPriceKrw; }
    public void setMinPriceKrw(int v) { this.minPriceKrw = Math.max(0, v); }

    public String getExcludeSymbols() { return excludeSymbols != null ? excludeSymbols : ""; }
    public void setExcludeSymbols(String v) { this.excludeSymbols = v != null ? v.trim() : ""; }

    /** Excluded symbols as Set (CSV parsed) */
    public java.util.Set<String> getExcludeSymbolsSet() {
        java.util.Set<String> set = new java.util.HashSet<String>();
        if (excludeSymbols == null || excludeSymbols.trim().isEmpty()) return set;
        for (String m : excludeSymbols.split(",")) {
            String trimmed = m.trim();
            if (!trimmed.isEmpty()) set.add(trimmed);
        }
        return set;
    }
}
