package com.example.stocks.backtest;

import com.example.stocks.api.BacktestRequest;
import com.example.stocks.api.BacktestResponse;
import com.example.stocks.config.StrategyProperties;
import com.example.stocks.config.TradeProperties;
import com.example.stocks.market.CandleCacheService;
import com.example.stocks.market.CandleService;
import com.example.stocks.market.StockCandle;
import com.example.stocks.strategy.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.time.*;
import java.time.format.DateTimeFormatter;

/**
 * On-demand backtest service (no persistence/cache).
 *
 * - Single-symbol/single-position (spot long only) simulation
 * - Multi-strategy: evaluates all strategies per position, executes 1 action
 *   Priority: SELL > ADD_BUY > BUY
 */
@Service
public class BacktestService {

    private static final Logger log = LoggerFactory.getLogger(BacktestService.class);

    private final CandleService candleService;
    private final CandleCacheService candleCacheService;
    private final StrategyFactory strategyFactory;
    private final StrategyProperties strategyCfg;
    private final TradeProperties tradeProps;

    public BacktestService(
            CandleService candleService,
            CandleCacheService candleCacheService,
            StrategyFactory strategyFactory,
            StrategyProperties strategyCfg,
            TradeProperties tradeProps
    ) {
        this.candleService = candleService;
        this.candleCacheService = candleCacheService;
        this.strategyFactory = strategyFactory;
        this.strategyCfg = strategyCfg;
        this.tradeProps = tradeProps;
    }

    public BacktestResponse run(BacktestRequest req) {
        int unit = req.candleUnitMin;
        if (unit <= 0 && req.interval != null) {
            unit = parseIntervalToMin(req.interval);
        }
        if (unit <= 0) unit = 5;

        // symbols
        List<String> symbols = new ArrayList<String>();
        if (req.symbols != null) {
            for (String s : req.symbols) {
                if (s == null) continue;
                String ss = s.trim();
                if (!ss.isEmpty()) symbols.add(ss);
            }
        }
        if (symbols.isEmpty() && req.symbol != null && !req.symbol.trim().isEmpty()) symbols.add(req.symbol.trim());
        if (symbols.isEmpty()) symbols.add("005930");

        int days = parseDays(req.period);

        // Strategy parsing
        List<StrategyType> stypes = parseStrategies(req);
        if (stypes.isEmpty()) {
            stypes.add(StrategyType.ADAPTIVE_TREND_MOMENTUM);
        }

        double capital = req.capitalKrw;
        double tpPct = (req.takeProfitPct != null ? req.takeProfitPct.doubleValue() : 4.0);
        double slPct = (req.stopLossPct != null ? req.stopLossPct.doubleValue() : 2.0);
        double baseOrderKrw = resolveBaseOrderKrw(capital, req.orderSizingMode, req.orderSizingValue, slPct);
        if (tpPct < 0) tpPct = 0;
        if (slPct < 0) slPct = 0;

        int maxAddBuysGlobal = (req.maxAddBuysGlobal == null ? 2 : Math.max(0, req.maxAddBuysGlobal.intValue()));
        boolean strategyLockEnabled = Boolean.TRUE.equals(req.strategyLock);
        double minConfidence = (req.minConfidence != null ? req.minConfidence.doubleValue() : 0);
        int timeStopMinutes = (req.timeStopMinutes != null ? Math.max(0, req.timeStopMinutes.intValue()) : 0);
        String intervalsCsv = (req.strategyIntervalsCsv != null ? req.strategyIntervalsCsv : "");
        String emaFilterCsv = (req.emaFilterCsv != null ? req.emaFilterCsv : "");

        // EMA trend filter map
        Map<String, Integer> emaTrendFilterMap = new HashMap<String, Integer>();
        for (StrategyType st : stypes) {
            emaTrendFilterMap.put(st.name(), parseEmaPeriod(emaFilterCsv, st));
        }

        // Strategy-by-interval grouping
        Map<Integer, List<StrategyType>> stratsByInterval = new LinkedHashMap<Integer, List<StrategyType>>();
        for (StrategyType st : stypes) {
            int effInterval = parseEffectiveInterval(intervalsCsv, st, unit);
            List<StrategyType> group = stratsByInterval.get(effInterval);
            if (group == null) { group = new ArrayList<StrategyType>(); stratsByInterval.put(effInterval, group); }
            if (!group.contains(st)) group.add(st);
        }

        Set<Integer> allIntervals = new LinkedHashSet<Integer>(stratsByInterval.keySet());

        BacktestResponse res = new BacktestResponse();
        res.candleUnitMin = unit;
        res.periodDays = days;
        res.usedTpPct = tpPct;
        res.usedSlPct = slPct;
        for (StrategyType t : stypes) res.strategies.add(t.name());

        // Candle loading from cache
        final Map<String, Map<Integer, List<StockCandle>>> candlesByMI =
                new ConcurrentHashMap<String, Map<Integer, List<StockCandle>>>();

        for (String sym : symbols) {
            Map<Integer, List<StockCandle>> byInterval = new HashMap<Integer, List<StockCandle>>();
            for (int intv : allIntervals) {
                List<StockCandle> cs = null;
                if (candleCacheService.hasCachedData(sym, intv)) {
                    cs = candleCacheService.getCached(sym, intv);
                    if (cs != null) {
                        log.debug("DB cache used: {} {}min -> {} candles", sym, intv, cs.size());
                    }
                }
                if (cs == null) cs = new ArrayList<StockCandle>();
                byInterval.put(intv, cs);
            }
            candlesByMI.put(sym, byInterval);
        }

        int totalCandleCount = 0;
        for (String sym : symbols) {
            Map<Integer, List<StockCandle>> byInterval = candlesByMI.get(sym);
            if (byInterval != null) {
                for (List<StockCandle> cs : byInterval.values()) {
                    totalCandleCount += cs.size();
                }
            }
            res.symbols.add(sym);
        }
        res.candleCount = totalCandleCount;

        // Delegate to TradingEngine
        SimulationParams params = new SimulationParams();
        params.symbols = symbols;
        params.strategies = stypes;
        params.capitalKrw = capital;
        params.tpPct = tpPct;
        params.slPct = slPct;
        params.baseOrderKrw = baseOrderKrw;
        params.maxAddBuys = maxAddBuysGlobal;
        params.strategyLock = strategyLockEnabled;
        params.minConfidence = minConfidence;
        params.timeStopMinutes = timeStopMinutes;
        params.candleUnitMin = unit;
        params.emaTrendFilterMap = emaTrendFilterMap;
        params.stratsByInterval = stratsByInterval;

        TradingEngine engine = new TradingEngine(strategyFactory, strategyCfg, tradeProps);
        BacktestResponse coreRes = engine.simulate(params, candlesByMI);

        // Merge results
        res.trades = coreRes.trades;
        res.tradesCount = coreRes.tradesCount;
        res.wins = coreRes.wins;
        res.winRate = coreRes.winRate;
        res.finalCapital = coreRes.finalCapital;
        res.tpSellCount = coreRes.tpSellCount;
        res.slSellCount = coreRes.slSellCount;
        res.patternSellCount = coreRes.patternSellCount;
        res.totalReturn = coreRes.totalReturn;
        res.roi = coreRes.roi;
        res.totalPnl = coreRes.totalPnl;
        res.totalRoi = coreRes.totalRoi;
        res.totalTrades = coreRes.totalTrades;

        return res;
    }

    private double resolveBaseOrderKrw(double capitalKrw, String mode, double value, double slPct) {
        String m = (mode == null ? "" : mode.trim().toUpperCase(Locale.ROOT));
        double base;
        if ("PCT".equals(m) || "PERCENT".equals(m) || "PERCENTAGE".equals(m)) {
            base = capitalKrw * (value / 100.0);
        } else if ("ATR_RISK".equals(m)) {
            double targetRiskPct = value > 0 ? value : 1.0;
            double effectiveSlPct = slPct > 0 ? slPct : 2.0;
            base = capitalKrw * (targetRiskPct / effectiveSlPct);
        } else if (value > 0) {
            base = value;
        } else {
            base = tradeProps.getGlobalBaseOrderKrw();
        }
        if (base <= 0) base = tradeProps.getGlobalBaseOrderKrw();
        if (base < tradeProps.getMinOrderKrw()) base = tradeProps.getMinOrderKrw();
        return base;
    }

    private int parseIntervalToMin(String interval) {
        if (interval == null) return 5;
        String s = interval.trim();
        if (s.equalsIgnoreCase("1d")) return 1440;
        if (s.toLowerCase(Locale.ROOT).endsWith("m")) {
            try { return Integer.parseInt(s.substring(0, s.length() - 1)); } catch (Exception ignore) { return 5; }
        }
        try { return Integer.parseInt(s); } catch (Exception ignore) {}
        return 5;
    }

    private int parseDays(String period) {
        if (period == null) return 7;
        String p = period.trim().toLowerCase();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*d").matcher(p);
        if (m.find()) {
            try { return Math.max(1, Integer.parseInt(m.group(1))); } catch (Exception ignore) {}
        }
        try { return Math.max(1, Integer.parseInt(p)); } catch (Exception ignore) {}
        return 7;
    }

    private List<StrategyType> parseStrategies(BacktestRequest req) {
        List<StrategyType> out = new ArrayList<StrategyType>();
        if (req.strategies != null && !req.strategies.isEmpty()) {
            for (String s : req.strategies) {
                if (s == null) continue;
                try { out.add(StrategyType.valueOf(s.trim())); } catch (Exception ignore) {}
            }
        } else if (req.strategyType != null && !req.strategyType.trim().isEmpty()) {
            try { out.add(StrategyType.valueOf(req.strategyType.trim())); } catch (Exception ignore) {}
        }
        return out;
    }

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

    private int parseEffectiveInterval(String intervalsCsv, StrategyType st, int defaultUnit) {
        if (intervalsCsv != null && !intervalsCsv.isEmpty()) {
            for (String pair : intervalsCsv.split(",")) {
                String[] kv = pair.trim().split(":");
                if (kv.length == 2 && kv[0].trim().equals(st.name())) {
                    try { return toValidUnit(Integer.parseInt(kv[1].trim())); } catch (Exception ignore) {}
                }
            }
        }
        return toValidUnit(defaultUnit);
    }

    private int parseEmaPeriod(String emaFilterCsv, StrategyType st) {
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
