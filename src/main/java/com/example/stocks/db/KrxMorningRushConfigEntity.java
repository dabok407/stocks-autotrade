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
    private int topN = 15;

    @Column(name = "max_positions", nullable = false)
    private int maxPositions = 3;

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
    private BigDecimal tpPct = BigDecimal.valueOf(3.0);

    @Column(name = "sl_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal slPct = BigDecimal.valueOf(2.0);

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

    // 시간외 거래량 최소 기준 (2026-04-11 추가)
    // 시간외 등락률 +10%여도 거래량 1~50주면 "가짜 상한가" → 09:00 모멘텀 없음
    @Column(name = "min_overtime_volume")
    private long minOvertimeVolume = 10000;

    // ── V33: TP_TRAIL + 티어드 SL (코인봇 구조 동일) ──
    @Column(name = "tp_trail_activate_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal tpTrailActivatePct = BigDecimal.valueOf(2.1);

    @Column(name = "tp_trail_drop_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal tpTrailDropPct = BigDecimal.valueOf(1.5);

    @Column(name = "grace_period_sec", nullable = false)
    private int gracePeriodSec = 30;

    @Column(name = "wide_sl_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal wideSlPct = BigDecimal.valueOf(2.0);

    @Column(name = "wide_period_min", nullable = false)
    private int widePeriodMin = 10;

    // ── V34: Split-Exit 분할 익절 (코인봇 동일 구조) ──
    @Column(name = "split_exit_enabled", nullable = false)
    private boolean splitExitEnabled = true;

    @Column(name = "split_tp_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal splitTpPct = BigDecimal.valueOf(1.6);

    @Column(name = "split_ratio", nullable = false, precision = 5, scale = 2)
    private BigDecimal splitRatio = BigDecimal.valueOf(0.40);

    @Column(name = "trail_drop_after_split", nullable = false, precision = 5, scale = 2)
    private BigDecimal trailDropAfterSplit = BigDecimal.valueOf(1.5);

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

    public long getMinOvertimeVolume() { return minOvertimeVolume; }
    public void setMinOvertimeVolume(long v) { this.minOvertimeVolume = Math.max(0, v); }

    public BigDecimal getTpTrailActivatePct() { return tpTrailActivatePct; }
    public void setTpTrailActivatePct(BigDecimal v) { this.tpTrailActivatePct = v != null ? v : BigDecimal.valueOf(3.0); }

    public BigDecimal getTpTrailDropPct() { return tpTrailDropPct; }
    public void setTpTrailDropPct(BigDecimal v) { this.tpTrailDropPct = v != null ? v : BigDecimal.valueOf(1.5); }

    public int getGracePeriodSec() { return gracePeriodSec; }
    public void setGracePeriodSec(int v) { this.gracePeriodSec = Math.max(0, v); }

    public BigDecimal getWideSlPct() { return wideSlPct; }
    public void setWideSlPct(BigDecimal v) { this.wideSlPct = v != null ? v : BigDecimal.valueOf(3.0); }

    public int getWidePeriodMin() { return widePeriodMin; }
    public void setWidePeriodMin(int v) { this.widePeriodMin = Math.max(1, v); }

    public boolean isSplitExitEnabled() { return splitExitEnabled; }
    public void setSplitExitEnabled(boolean v) { this.splitExitEnabled = v; }

    public BigDecimal getSplitTpPct() { return splitTpPct; }
    public void setSplitTpPct(BigDecimal v) { this.splitTpPct = v != null ? v : BigDecimal.valueOf(1.6); }

    public BigDecimal getSplitRatio() { return splitRatio; }
    public void setSplitRatio(BigDecimal v) { this.splitRatio = v != null ? v : BigDecimal.valueOf(0.40); }

    public BigDecimal getTrailDropAfterSplit() { return trailDropAfterSplit; }
    public void setTrailDropAfterSplit(BigDecimal v) { this.trailDropAfterSplit = v != null ? v : BigDecimal.valueOf(1.5); }

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
