package com.example.stocks.api;

import java.util.List;
import java.util.Map;

public class BacktestRequest {
    // legacy single
    public String strategyType;

    // v2 multi (StrategyType enum names)
    public List<String> strategies;

    public String period; // 1d,7d,30d
    public int candleUnitMin;
    public String interval; // "5m" etc (optional)
    public String symbol;
    // v3 multi symbols (optional). If provided, 'symbol' is ignored.
    public List<String> symbols;

    // v3 date range (optional): "YYYY-MM-DD" (KST)
    public String fromDate;
    public String toDate;
    public double capitalKrw;

    // order sizing (optional)
    public String orderSizingMode;  // FIXED/PCT
    public double orderSizingValue; // FIXED: KRW, PCT: percent

    // risk limit (global)
    public Integer maxAddBuysGlobal;
    public Double takeProfitPct;
    public Double stopLossPct;
    public Boolean strategyLock;
    public Double minConfidence;
    public Integer timeStopMinutes;
    public String strategyIntervalsCsv;
    public String emaFilterCsv;

    /**
     * Per-symbol strategy assignment (optional).
     * key = symbol (e.g., "005930"), value = strategy names for that symbol
     */
    public Map<String, List<String>> symbolStrategies;

    /**
     * Strategy group list (optional).
     * When provided, each group has independent symbol+strategy+risk settings.
     */
    public List<StrategyGroupDto> groups;

    public static class StrategyGroupDto {
        public String groupName;
        public List<String> strategies;
        public List<String> symbols;
        public int candleUnitMin = 60;
        public String orderSizingMode = "PCT";
        public double orderSizingValue = 90;
        public Double takeProfitPct;
        public Double stopLossPct;
        public Integer maxAddBuys;
        public Boolean strategyLock;
        public Double minConfidence;
        public Integer timeStopMinutes;
        public String strategyIntervalsCsv;
        public String emaFilterCsv;
    }
}
