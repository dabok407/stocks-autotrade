package com.example.stocks.db;

import javax.persistence.*;
import java.math.BigDecimal;

/**
 * NYSE AllDay Momentum Scanner config (single row, id=1).
 * Entry: 11:30-15:00 ET, Session end: 15:45 ET
 */
@Entity
@Table(name = "nyse_allday_config")
public class NyseAlldayConfigEntity {

    @Id
    @Column(name = "id")
    private int id = 1;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    @Column(name = "mode", nullable = false, length = 10)
    private String mode = "PAPER";

    @Column(name = "top_n", nullable = false)
    private int topN = 20;

    @Column(name = "max_positions", nullable = false)
    private int maxPositions = 2;

    @Column(name = "order_sizing_mode", nullable = false, length = 10)
    private String orderSizingMode = "PCT";

    @Column(name = "order_sizing_value", nullable = false, precision = 10, scale = 2)
    private BigDecimal orderSizingValue = BigDecimal.valueOf(20);

    @Column(name = "candle_unit_min", nullable = false)
    private int candleUnitMin = 5;

    // -- Timing (US ET) --
    @Column(name = "entry_start_hour", nullable = false)
    private int entryStartHour = 11;

    @Column(name = "entry_start_min", nullable = false)
    private int entryStartMin = 30;

    @Column(name = "entry_end_hour", nullable = false)
    private int entryEndHour = 15;

    @Column(name = "entry_end_min", nullable = false)
    private int entryEndMin = 0;

    @Column(name = "session_end_hour", nullable = false)
    private int sessionEndHour = 15;

    @Column(name = "session_end_min", nullable = false)
    private int sessionEndMin = 45;

    // -- Risk parameters --
    @Column(name = "sl_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal slPct = BigDecimal.valueOf(1.5);

    @Column(name = "trail_atr_mult", nullable = false, precision = 5, scale = 2)
    private BigDecimal trailAtrMult = BigDecimal.valueOf(0.8);

    @Column(name = "min_confidence", nullable = false, precision = 5, scale = 2)
    private BigDecimal minConfidence = BigDecimal.valueOf(9.4);

    @Column(name = "time_stop_candles", nullable = false)
    private int timeStopCandles = 12;

    @Column(name = "time_stop_min_pnl", nullable = false, precision = 5, scale = 2)
    private BigDecimal timeStopMinPnl = BigDecimal.valueOf(0.3);

    // -- Filters --
    @Column(name = "spy_filter_enabled", nullable = false)
    private boolean spyFilterEnabled = true;

    @Column(name = "spy_ema_period", nullable = false)
    private int spyEmaPeriod = 20;

    @Column(name = "volume_surge_mult", nullable = false, precision = 5, scale = 2)
    private BigDecimal volumeSurgeMult = BigDecimal.valueOf(3.0);

    @Column(name = "min_body_ratio", nullable = false, precision = 5, scale = 2)
    private BigDecimal minBodyRatio = BigDecimal.valueOf(0.60);

    @Column(name = "exclude_symbols", length = 1000)
    private String excludeSymbols = "";

    @Column(name = "min_price_usd", nullable = false)
    private int minPriceUsd = 5;

    // -- Quick TP --
    @Column(name = "quick_tp_enabled", nullable = false)
    private boolean quickTpEnabled = true;

    @Column(name = "quick_tp_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal quickTpPct = BigDecimal.valueOf(0.70);

    @Column(name = "quick_tp_interval_sec", nullable = false)
    private int quickTpIntervalSec = 5;

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
    public void setOrderSizingValue(BigDecimal orderSizingValue) { this.orderSizingValue = orderSizingValue != null ? orderSizingValue : BigDecimal.valueOf(20); }

    public int getCandleUnitMin() { return candleUnitMin; }
    public void setCandleUnitMin(int candleUnitMin) { this.candleUnitMin = candleUnitMin > 0 ? candleUnitMin : 5; }

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

    public BigDecimal getSlPct() { return slPct; }
    public void setSlPct(BigDecimal v) { this.slPct = v != null ? v : BigDecimal.valueOf(1.5); }

    public BigDecimal getTrailAtrMult() { return trailAtrMult; }
    public void setTrailAtrMult(BigDecimal v) { this.trailAtrMult = v != null ? v : BigDecimal.valueOf(0.8); }

    public BigDecimal getMinConfidence() { return minConfidence; }
    public void setMinConfidence(BigDecimal v) { this.minConfidence = v != null ? v : BigDecimal.valueOf(9.4); }

    public int getTimeStopCandles() { return timeStopCandles; }
    public void setTimeStopCandles(int v) { this.timeStopCandles = Math.max(1, v); }

    public BigDecimal getTimeStopMinPnl() { return timeStopMinPnl; }
    public void setTimeStopMinPnl(BigDecimal v) { this.timeStopMinPnl = v != null ? v : BigDecimal.valueOf(0.3); }

    public boolean isSpyFilterEnabled() { return spyFilterEnabled; }
    public void setSpyFilterEnabled(boolean spyFilterEnabled) { this.spyFilterEnabled = spyFilterEnabled; }

    public int getSpyEmaPeriod() { return spyEmaPeriod; }
    public void setSpyEmaPeriod(int spyEmaPeriod) { this.spyEmaPeriod = Math.max(5, spyEmaPeriod); }

    public BigDecimal getVolumeSurgeMult() { return volumeSurgeMult; }
    public void setVolumeSurgeMult(BigDecimal v) { this.volumeSurgeMult = v != null ? v : BigDecimal.valueOf(3.0); }

    public BigDecimal getMinBodyRatio() { return minBodyRatio; }
    public void setMinBodyRatio(BigDecimal v) { this.minBodyRatio = v != null ? v : BigDecimal.valueOf(0.60); }

    public int getMinPriceUsd() { return minPriceUsd; }
    public void setMinPriceUsd(int v) { this.minPriceUsd = Math.max(0, v); }

    public boolean isQuickTpEnabled() { return quickTpEnabled; }
    public void setQuickTpEnabled(boolean quickTpEnabled) { this.quickTpEnabled = quickTpEnabled; }

    public double getQuickTpPct() { return quickTpPct != null ? quickTpPct.doubleValue() : 0.70; }
    public BigDecimal getQuickTpPctBD() { return quickTpPct != null ? quickTpPct : BigDecimal.valueOf(0.70); }
    public void setQuickTpPct(BigDecimal v) { this.quickTpPct = v != null ? v : BigDecimal.valueOf(0.70); }

    public int getQuickTpIntervalSec() { return quickTpIntervalSec; }
    public void setQuickTpIntervalSec(int v) { this.quickTpIntervalSec = Math.max(3, Math.min(60, v)); }

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
