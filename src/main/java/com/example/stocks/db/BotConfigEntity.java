package com.example.stocks.db;

import javax.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "bot_config")
public class BotConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mode", nullable = false)
    private String mode = "PAPER";

    @Column(name = "market_type", nullable = false)
    private String marketType = "KRX";

    @Column(name = "candle_unit_min", nullable = false)
    private int candleUnitMin = 5;

    @Column(name = "strategy_type", nullable = false)
    private String strategyType = "REGIME_PULLBACK";

    @Column(name = "strategy_types_csv", length = 1024)
    private String strategyTypesCsv = "";

    @Column(name = "capital_krw", nullable = false, precision = 20, scale = 2)
    private BigDecimal capitalKrw = BigDecimal.valueOf(500000);

    @Column(name = "take_profit_pct", nullable = false, precision = 10, scale = 4)
    private BigDecimal takeProfitPct = BigDecimal.valueOf(3.0);

    @Column(name = "stop_loss_pct", nullable = false, precision = 10, scale = 4)
    private BigDecimal stopLossPct = BigDecimal.valueOf(2.0);

    @Column(name = "trailing_stop_pct", nullable = false, precision = 10, scale = 4)
    private BigDecimal trailingStopPct = BigDecimal.ZERO;

    @Column(name = "max_add_buys_global", nullable = false)
    private int maxAddBuysGlobal = 2;

    @Column(name = "strategy_lock", nullable = false)
    private boolean strategyLock = false;

    @Column(name = "min_confidence", nullable = false)
    private double minConfidence = 0;

    @Column(name = "time_stop_minutes", nullable = false)
    private int timeStopMinutes = 0;

    @Column(name = "max_drawdown_pct", nullable = false, precision = 10, scale = 4)
    private BigDecimal maxDrawdownPct = BigDecimal.ZERO;

    @Column(name = "daily_loss_limit_pct", nullable = false, precision = 10, scale = 4)
    private BigDecimal dailyLossLimitPct = BigDecimal.ZERO;

    @Column(name = "strategy_intervals_csv", length = 2048)
    private String strategyIntervalsCsv = "";

    @Column(name = "ema_filter_csv", length = 2048)
    private String emaFilterCsv = "";

    @Column(name = "us_mode")
    private String usMode = "PAPER";

    @Column(name = "us_capital_krw", precision = 20, scale = 2)
    private BigDecimal usCapitalKrw = BigDecimal.valueOf(500000);

    @Column(name = "auto_start_enabled", nullable = false)
    private boolean autoStartEnabled = false;

    // --- Getters & Setters ---

    public Long getId() { return id; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getMarketType() { return marketType; }
    public void setMarketType(String marketType) { this.marketType = marketType; }

    public int getCandleUnitMin() { return candleUnitMin; }
    public void setCandleUnitMin(int candleUnitMin) { this.candleUnitMin = candleUnitMin; }

    public String getStrategyType() { return strategyType; }
    public void setStrategyType(String strategyType) { this.strategyType = strategyType; }

    public String getStrategyTypesCsv() { return strategyTypesCsv; }
    public void setStrategyTypesCsv(String strategyTypesCsv) { this.strategyTypesCsv = strategyTypesCsv; }

    public BigDecimal getCapitalKrw() { return capitalKrw; }
    public void setCapitalKrw(BigDecimal capitalKrw) { this.capitalKrw = capitalKrw == null ? BigDecimal.ZERO : capitalKrw; }

    public BigDecimal getTakeProfitPct() { return takeProfitPct; }
    public void setTakeProfitPct(BigDecimal takeProfitPct) { this.takeProfitPct = takeProfitPct == null ? BigDecimal.ZERO : takeProfitPct; }

    public BigDecimal getStopLossPct() { return stopLossPct; }
    public void setStopLossPct(BigDecimal stopLossPct) { this.stopLossPct = stopLossPct == null ? BigDecimal.ZERO : stopLossPct; }

    public BigDecimal getTrailingStopPct() { return trailingStopPct; }
    public void setTrailingStopPct(BigDecimal v) { this.trailingStopPct = v == null ? BigDecimal.ZERO : v; }

    public int getMaxAddBuysGlobal() { return maxAddBuysGlobal; }
    public void setMaxAddBuysGlobal(int maxAddBuysGlobal) { this.maxAddBuysGlobal = Math.max(0, maxAddBuysGlobal); }

    public boolean isStrategyLock() { return strategyLock; }
    public void setStrategyLock(boolean strategyLock) { this.strategyLock = strategyLock; }

    public double getMinConfidence() { return minConfidence; }
    public void setMinConfidence(double minConfidence) { this.minConfidence = Math.max(0, Math.min(10, minConfidence)); }

    public int getTimeStopMinutes() { return timeStopMinutes; }
    public void setTimeStopMinutes(int timeStopMinutes) { this.timeStopMinutes = Math.max(0, timeStopMinutes); }

    public BigDecimal getMaxDrawdownPct() { return maxDrawdownPct; }
    public void setMaxDrawdownPct(BigDecimal v) { this.maxDrawdownPct = v == null ? BigDecimal.ZERO : v; }

    public BigDecimal getDailyLossLimitPct() { return dailyLossLimitPct; }
    public void setDailyLossLimitPct(BigDecimal v) { this.dailyLossLimitPct = v == null ? BigDecimal.ZERO : v; }

    public String getStrategyIntervalsCsv() { return strategyIntervalsCsv; }
    public void setStrategyIntervalsCsv(String csv) { this.strategyIntervalsCsv = csv == null ? "" : csv; }

    public String getEmaFilterCsv() { return emaFilterCsv; }
    public void setEmaFilterCsv(String csv) { this.emaFilterCsv = csv == null ? "" : csv; }

    public String getUsMode() { return usMode; }
    public void setUsMode(String usMode) { this.usMode = usMode == null ? "PAPER" : usMode; }

    public BigDecimal getUsCapitalKrw() { return usCapitalKrw; }
    public void setUsCapitalKrw(BigDecimal v) { this.usCapitalKrw = v == null ? BigDecimal.ZERO : v; }

    public boolean isAutoStartEnabled() { return autoStartEnabled; }
    public void setAutoStartEnabled(boolean autoStartEnabled) { this.autoStartEnabled = autoStartEnabled; }

    // -- Interval / EMA helpers (for backtest/strategy group) --

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
     * Returns effective interval for the given strategy.
     * Uses strategyIntervalsCsv override if present, otherwise candleUnitMin.
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
     * Uses emaFilterCsv override if present, otherwise default 50.
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
}
