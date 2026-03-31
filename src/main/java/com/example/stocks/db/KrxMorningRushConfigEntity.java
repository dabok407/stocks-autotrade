package com.example.stocks.db;

import javax.persistence.*;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/**
 * KRX Morning Rush Scanner config (single row, id=1).
 * KRX gap-up spike scanner: entry at 09:01 KST (30sec delay),
 * gap > 2.5%, volume 3x, 2 consecutive confirms.
 * Session end: 10:00 KST.
 */
@Entity
@Table(name = "krx_morning_rush_config")
public class KrxMorningRushConfigEntity {

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
    private BigDecimal orderSizingValue = BigDecimal.valueOf(5);

    @Column(name = "gap_threshold_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal gapThresholdPct = BigDecimal.valueOf(2.5);

    @Column(name = "volume_mult", nullable = false, precision = 5, scale = 2)
    private BigDecimal volumeMult = BigDecimal.valueOf(3.0);

    @Column(name = "confirm_count", nullable = false)
    private int confirmCount = 2;

    @Column(name = "check_interval_sec", nullable = false)
    private int checkIntervalSec = 5;

    @Column(name = "tp_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal tpPct = BigDecimal.valueOf(1.5);

    @Column(name = "sl_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal slPct = BigDecimal.valueOf(1.5);

    @Column(name = "entry_delay_sec", nullable = false)
    private int entryDelaySec = 30;

    @Column(name = "session_end_hour", nullable = false)
    private int sessionEndHour = 10;

    @Column(name = "session_end_min", nullable = false)
    private int sessionEndMin = 0;

    @Column(name = "time_stop_min", nullable = false)
    private int timeStopMin = 30;

    @Column(name = "min_trade_amount", nullable = false)
    private long minTradeAmount = 1000000000L;

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
    public void setMaxPositions(int maxPositions) { this.maxPositions = Math.max(1, Math.min(10, maxPositions)); }

    public String getOrderSizingMode() { return orderSizingMode; }
    public void setOrderSizingMode(String orderSizingMode) { this.orderSizingMode = orderSizingMode != null ? orderSizingMode.toUpperCase() : "PCT"; }

    public BigDecimal getOrderSizingValue() { return orderSizingValue; }
    public void setOrderSizingValue(BigDecimal orderSizingValue) { this.orderSizingValue = orderSizingValue != null ? orderSizingValue : BigDecimal.valueOf(5); }

    public BigDecimal getGapThresholdPct() { return gapThresholdPct; }
    public void setGapThresholdPct(BigDecimal v) { this.gapThresholdPct = v != null ? v : BigDecimal.valueOf(2.5); }

    public BigDecimal getVolumeMult() { return volumeMult; }
    public void setVolumeMult(BigDecimal v) { this.volumeMult = v != null ? v : BigDecimal.valueOf(3.0); }

    public int getConfirmCount() { return confirmCount; }
    public void setConfirmCount(int confirmCount) { this.confirmCount = Math.max(1, Math.min(10, confirmCount)); }

    public int getCheckIntervalSec() { return checkIntervalSec; }
    public void setCheckIntervalSec(int v) { this.checkIntervalSec = Math.max(1, Math.min(60, v)); }

    public BigDecimal getTpPct() { return tpPct; }
    public void setTpPct(BigDecimal v) { this.tpPct = v != null ? v : BigDecimal.valueOf(1.5); }

    public BigDecimal getSlPct() { return slPct; }
    public void setSlPct(BigDecimal v) { this.slPct = v != null ? v : BigDecimal.valueOf(1.5); }

    public int getEntryDelaySec() { return entryDelaySec; }
    public void setEntryDelaySec(int v) { this.entryDelaySec = Math.max(0, Math.min(300, v)); }

    public int getSessionEndHour() { return sessionEndHour; }
    public void setSessionEndHour(int v) { this.sessionEndHour = v; }

    public int getSessionEndMin() { return sessionEndMin; }
    public void setSessionEndMin(int v) { this.sessionEndMin = v; }

    public int getTimeStopMin() { return timeStopMin; }
    public void setTimeStopMin(int v) { this.timeStopMin = Math.max(1, Math.min(120, v)); }

    public long getMinTradeAmount() { return minTradeAmount; }
    public void setMinTradeAmount(long v) { this.minTradeAmount = Math.max(0, v); }

    public String getExcludeSymbols() { return excludeSymbols != null ? excludeSymbols : ""; }
    public void setExcludeSymbols(String v) { this.excludeSymbols = v != null ? v.trim() : ""; }

    public int getMinPriceKrw() { return minPriceKrw; }
    public void setMinPriceKrw(int v) { this.minPriceKrw = Math.max(0, v); }

    /** Excluded symbols as Set (CSV parsed) */
    public Set<String> getExcludeSymbolsSet() {
        Set<String> set = new HashSet<String>();
        if (excludeSymbols == null || excludeSymbols.trim().isEmpty()) return set;
        for (String m : excludeSymbols.split(",")) {
            String trimmed = m.trim();
            if (!trimmed.isEmpty()) set.add(trimmed);
        }
        return set;
    }
}
