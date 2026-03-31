package com.example.stocks.db;

import javax.persistence.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Strategy group: independent strategy/risk settings per symbol set.
 *
 * Each group owns a unique symbol set (symbols are mutually exclusive between groups).
 * When empty, falls back to bot_config defaults.
 */
@Entity
@Table(name = "strategy_group")
public class StrategyGroupEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_name", nullable = false, length = 100)
    private String groupName = "Group 1";

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "symbols_csv", nullable = false, length = 2048)
    private String symbolsCsv = "";

    @Column(name = "strategy_types_csv", nullable = false, length = 1024)
    private String strategyTypesCsv = "";

    @Column(name = "candle_unit_min", nullable = false)
    private int candleUnitMin = 60;

    @Column(name = "order_sizing_mode", nullable = false, length = 20)
    private String orderSizingMode = "PCT";

    @Column(name = "order_sizing_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal orderSizingValue = BigDecimal.valueOf(90);

    @Column(name = "take_profit_pct", nullable = false, precision = 10, scale = 4)
    private BigDecimal takeProfitPct = BigDecimal.valueOf(3.0);

    @Column(name = "stop_loss_pct", nullable = false, precision = 10, scale = 4)
    private BigDecimal stopLossPct = BigDecimal.valueOf(2.0);

    @Column(name = "max_add_buys", nullable = false)
    private int maxAddBuys = 2;

    @Column(name = "strategy_lock", nullable = false, columnDefinition = "TINYINT(1)")
    @org.hibernate.annotations.Type(type = "org.hibernate.type.NumericBooleanType")
    private Boolean strategyLock = false;

    @Column(name = "min_confidence", nullable = false)
    private double minConfidence = 0;

    @Column(name = "time_stop_minutes", nullable = false)
    private int timeStopMinutes = 0;

    @Column(name = "strategy_intervals_csv", nullable = false, length = 2048)
    private String strategyIntervalsCsv = "";

    @Column(name = "ema_filter_csv", nullable = false, length = 2048)
    private String emaFilterCsv = "";

    @Column(name = "selected_preset", length = 20)
    private String selectedPreset;

    // -- Helper methods --

    public List<String> getSymbolsList() {
        if (symbolsCsv == null || symbolsCsv.trim().isEmpty()) return new ArrayList<String>();
        List<String> result = new ArrayList<String>();
        for (String s : symbolsCsv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) result.add(t);
        }
        return result;
    }

    public List<String> getStrategyTypesList() {
        if (strategyTypesCsv == null || strategyTypesCsv.trim().isEmpty()) return new ArrayList<String>();
        List<String> result = new ArrayList<String>();
        for (String s : strategyTypesCsv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) result.add(t);
        }
        return result;
    }

    // -- Interval / EMA helpers --

    private static final int[] VALID_UNITS = {1, 3, 5, 10, 15, 30, 60, 240};

    private static int toValidUnit(int v) {
        for (int u : VALID_UNITS) { if (u == v) return v; }
        int best = VALID_UNITS[0]; int bestD = Math.abs(v - best);
        for (int i = 1; i < VALID_UNITS.length; i++) {
            int d = Math.abs(v - VALID_UNITS[i]);
            if (d < bestD) { bestD = d; best = VALID_UNITS[i]; }
        }
        return best;
    }

    /**
     * Returns effective interval for the given strategy in this group.
     */
    public int getEffectiveInterval(com.example.stocks.strategy.StrategyType st) {
        if (strategyIntervalsCsv != null && !strategyIntervalsCsv.isEmpty()) {
            for (String pair : strategyIntervalsCsv.split(",")) {
                String[] kv = pair.trim().split(":");
                if (kv.length == 2 && kv[0].trim().equals(st.name())) {
                    try { return toValidUnit(Integer.parseInt(kv[1].trim())); } catch (Exception ignore) {}
                }
            }
        }
        return toValidUnit(candleUnitMin);
    }

    /**
     * Returns effective EMA trend filter period for the given strategy.
     */
    public int getEffectiveEmaPeriod(com.example.stocks.strategy.StrategyType st) {
        if (emaFilterCsv != null && !emaFilterCsv.isEmpty()) {
            for (String pair : emaFilterCsv.split(",")) {
                String[] kv = pair.trim().split(":");
                if (kv.length == 2 && kv[0].trim().equals(st.name())) {
                    try { return Math.max(0, Integer.parseInt(kv[1].trim())); } catch (Exception ignore) {}
                }
            }
        }
        return 50;
    }

    // -- Getters / Setters --

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public String getSymbolsCsv() { return symbolsCsv; }
    public void setSymbolsCsv(String symbolsCsv) { this.symbolsCsv = symbolsCsv; }

    public String getStrategyTypesCsv() { return strategyTypesCsv; }
    public void setStrategyTypesCsv(String strategyTypesCsv) { this.strategyTypesCsv = strategyTypesCsv; }

    public int getCandleUnitMin() { return candleUnitMin; }
    public void setCandleUnitMin(int candleUnitMin) { this.candleUnitMin = candleUnitMin; }

    public String getOrderSizingMode() { return orderSizingMode; }
    public void setOrderSizingMode(String orderSizingMode) { this.orderSizingMode = orderSizingMode; }

    public BigDecimal getOrderSizingValue() { return orderSizingValue; }
    public void setOrderSizingValue(BigDecimal orderSizingValue) { this.orderSizingValue = orderSizingValue; }

    public BigDecimal getTakeProfitPct() { return takeProfitPct; }
    public void setTakeProfitPct(BigDecimal takeProfitPct) { this.takeProfitPct = takeProfitPct; }

    public BigDecimal getStopLossPct() { return stopLossPct; }
    public void setStopLossPct(BigDecimal stopLossPct) { this.stopLossPct = stopLossPct; }

    public int getMaxAddBuys() { return maxAddBuys; }
    public void setMaxAddBuys(int maxAddBuys) { this.maxAddBuys = maxAddBuys; }

    public Boolean getStrategyLock() { return strategyLock; }
    public void setStrategyLock(Boolean strategyLock) { this.strategyLock = strategyLock; }

    public double getMinConfidence() { return minConfidence; }
    public void setMinConfidence(double minConfidence) { this.minConfidence = minConfidence; }

    public int getTimeStopMinutes() { return timeStopMinutes; }
    public void setTimeStopMinutes(int timeStopMinutes) { this.timeStopMinutes = timeStopMinutes; }

    public String getStrategyIntervalsCsv() { return strategyIntervalsCsv; }
    public void setStrategyIntervalsCsv(String strategyIntervalsCsv) { this.strategyIntervalsCsv = strategyIntervalsCsv; }

    public String getEmaFilterCsv() { return emaFilterCsv; }
    public void setEmaFilterCsv(String emaFilterCsv) { this.emaFilterCsv = emaFilterCsv; }

    public String getSelectedPreset() { return selectedPreset; }
    public void setSelectedPreset(String selectedPreset) { this.selectedPreset = selectedPreset; }
}
