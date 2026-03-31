package com.example.stocks.backtest;

import com.example.stocks.market.StockCandle;
import com.example.stocks.strategy.StrategyType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Backtest simulation parameters.
 * Pure data holder passed to BacktestSimulator / TradingEngine.
 */
public class SimulationParams {

    public List<String> symbols;
    public List<StrategyType> strategies;
    public double capitalKrw;
    public double tpPct;
    public double slPct;
    public double baseOrderKrw;
    public int maxAddBuys;
    public boolean strategyLock;
    public double minConfidence;
    public int timeStopMinutes;
    public int candleUnitMin;
    public String orderSizingMode;
    public double orderSizingValue;

    /** Per-strategy EMA trend filter period: key=StrategyType.name(), value=EMA period (0=disabled) */
    public Map<String, Integer> emaTrendFilterMap;

    /** Strategies grouped by interval: key=intervalMin, value=strategies for that interval */
    public Map<Integer, List<StrategyType>> stratsByInterval;

    /** Index direction filter (e.g., KOSPI/SPY EMA filter for scanner backtests) */
    public boolean indexFilterEnabled;
    public int indexEmaPeriod;
    public List<StockCandle> indexCandles;

    /** Maximum concurrent open positions across all symbols (0 = unlimited) */
    public int maxConcurrentPositions;

    /** Quick TP: intra-candle TP/SL simulation using high/low prices */
    public boolean quickTpEnabled;
    public double quickTpPct;
    public double quickSlPct;

    /** Group mode flag and per-symbol group settings */
    public boolean hasGroups;
    public Map<String, SymbolGroupSettings> symbolGroupMap;

    /**
     * Per-group symbol settings.
     */
    public static class SymbolGroupSettings {
        public double tpPct;
        public double slPct;
        public int maxAddBuys;
        public boolean strategyLock;
        public double minConfidence;
        public int timeStopMinutes;
        public int candleUnitMin;
        public String orderSizingMode;
        public double orderSizingValue;
        public double baseOrderKrw;
        public String intervalsCsv;
        public String emaFilterCsv;
        public List<StrategyType> strategies;
        public Set<String> strategyNames;
    }
}
