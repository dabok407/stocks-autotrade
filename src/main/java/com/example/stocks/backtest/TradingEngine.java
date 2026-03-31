package com.example.stocks.backtest;

import com.example.stocks.api.BacktestResponse;
import com.example.stocks.api.BacktestTradeRow;
import com.example.stocks.config.StrategyProperties;
import com.example.stocks.config.TradeProperties;
import com.example.stocks.market.StockCandle;
import com.example.stocks.strategy.*;

import java.time.ZoneOffset;
import java.util.*;

/**
 * Core trading engine (Single Source of Truth).
 *
 * Both BacktestService and TradingBotService use this class for trade decisions.
 *
 * Key logic:
 * - Multi-symbol + multi-interval timeline merge
 * - Concurrent timestamp strategy evaluation + best signal selection
 * - TP/SL/TimeStop checks
 * - Cross-strategy ADD_BUY
 * - Strategy lock / confidence filtering
 */
public class TradingEngine {

    private final StrategyFactory strategyFactory;
    private final StrategyProperties strategyCfg;
    private final TradeProperties tradeProps;

    public TradingEngine(StrategyFactory strategyFactory,
                         StrategyProperties strategyCfg,
                         TradeProperties tradeProps) {
        this.strategyFactory = strategyFactory;
        this.strategyCfg = strategyCfg;
        this.tradeProps = tradeProps;
    }

    /**
     * Detailed simulation: returns result with trade rows.
     */
    public BacktestResponse simulate(SimulationParams params,
                                      Map<String, Map<Integer, List<StockCandle>>> candlesByMI) {
        SimState state = runLoop(params, candlesByMI, true);

        BacktestResponse res = new BacktestResponse();
        res.candleUnitMin = params.candleUnitMin;
        if (params.hasGroups && params.symbolGroupMap != null && !params.symbolGroupMap.isEmpty()) {
            SimulationParams.SymbolGroupSettings firstGs = params.symbolGroupMap.values().iterator().next();
            res.usedTpPct = firstGs.tpPct;
            res.usedSlPct = firstGs.slPct;
        } else {
            res.usedTpPct = params.tpPct;
            res.usedSlPct = params.slPct;
        }
        for (StrategyType t : params.strategies) res.strategies.add(t.name());

        int totalCandleCount = 0;
        for (Map.Entry<String, Map<Integer, List<StockCandle>>> e : candlesByMI.entrySet()) {
            res.symbols.add(e.getKey());
            for (List<StockCandle> cs : e.getValue().values()) {
                totalCandleCount += cs.size();
            }
        }
        res.candleCount = totalCandleCount;

        res.trades = state.trades;
        res.tradesCount = state.trades.size();
        res.wins = state.winCount;
        res.winRate = (state.sellCount == 0 ? 0.0 : (state.winCount * 100.0 / state.sellCount));
        res.finalCapital = state.capital;
        res.tpSellCount = state.tpSellCount;
        res.slSellCount = state.slSellCount;
        res.patternSellCount = state.patternSellCount;

        double start = params.capitalKrw;
        res.totalReturn = state.capital - start;
        res.roi = (start <= 0 ? 0.0 : (res.totalReturn / start) * 100.0);
        res.totalPnl = res.totalReturn;
        res.totalRoi = res.roi;
        res.totalTrades = res.tradesCount;

        return res;
    }

    /**
     * Lightweight simulation: returns only ROI/winRate/tradeCount (for optimization loops).
     */
    public BacktestSimulator.OptimizationMetrics simulateFast(
            SimulationParams params,
            Map<String, Map<Integer, List<StockCandle>>> candlesByMI) {
        SimState state = runLoop(params, candlesByMI, false);

        BacktestSimulator.OptimizationMetrics m = new BacktestSimulator.OptimizationMetrics();
        m.roi = (params.capitalKrw <= 0 ? 0.0 : ((state.capital - params.capitalKrw) / params.capitalKrw) * 100.0);
        m.winRate = (state.sellCount == 0 ? 0.0 : (state.winCount * 100.0 / state.sellCount));
        m.totalTrades = state.sellCount;
        m.wins = state.winCount;
        m.finalCapital = state.capital;
        m.totalPnl = state.capital - params.capitalKrw;
        m.tpSellCount = state.tpSellCount;
        m.slSellCount = state.slSellCount;
        m.patternSellCount = state.patternSellCount;
        return m;
    }

    // ===== Core simulation loop =====

    private SimState runLoop(SimulationParams params,
                             Map<String, Map<Integer, List<StockCandle>>> candlesByMI,
                             boolean detailed) {

        double capital = params.capitalKrw;
        double tpPct = params.tpPct;
        double slPct = params.slPct;
        double baseOrderKrw = params.baseOrderKrw;
        int maxAddBuysGlobal = params.maxAddBuys;
        boolean strategyLockEnabled = params.strategyLock;
        double minConfidence = params.minConfidence;
        int timeStopMinutes = params.timeStopMinutes;
        Map<String, Integer> emaTrendFilterMap = params.emaTrendFilterMap;
        Map<Integer, List<StrategyType>> stratsByInterval = params.stratsByInterval;
        boolean hasGroups = params.hasGroups;
        Map<String, SimulationParams.SymbolGroupSettings> symbolGroupMap = params.symbolGroupMap;

        SimState st = new SimState();
        st.capital = capital;
        if (detailed) st.trades = new ArrayList<BacktestTradeRow>();

        // Per-symbol position state init
        Map<String, Pos> posBySymbol = new HashMap<String, Pos>();
        for (String sym : params.symbols) {
            Pos p = new Pos();
            Map<Integer, List<StockCandle>> byInterval = candlesByMI.get(sym);
            if (byInterval != null) {
                for (Map.Entry<Integer, List<StockCandle>> e : byInterval.entrySet()) {
                    List<StockCandle> series = e.getValue();
                    if (series == null) series = new ArrayList<StockCandle>();
                    p.seriesByInterval.put(e.getKey(), series);
                    p.idxByInterval.put(e.getKey(), series.size() > 1 ? 1 : 0);
                }
            }
            posBySymbol.put(sym, p);
        }

        // Multi-symbol + multi-interval timeline merge loop
        while (true) {
            String nextSymbol = null;
            int nextInterval = 0;
            StockCandle nextCur = null;

            for (String sym : params.symbols) {
                Pos p = posBySymbol.get(sym);
                if (p == null) continue;
                for (Map.Entry<Integer, Integer> ie : p.idxByInterval.entrySet()) {
                    int intv = ie.getKey();
                    if (stratsByInterval.get(intv) == null || stratsByInterval.get(intv).isEmpty()) continue;
                    int idx = ie.getValue();
                    List<StockCandle> series = p.seriesByInterval.get(intv);
                    if (series == null || idx >= series.size()) continue;
                    if (idx < 1) continue;
                    StockCandle cur = series.get(idx);
                    if (cur == null || cur.candle_date_time_utc == null) continue;
                    if (nextCur == null || cur.candle_date_time_utc.compareTo(nextCur.candle_date_time_utc) < 0) {
                        nextSymbol = sym;
                        nextInterval = intv;
                        nextCur = cur;
                    }
                }
            }
            if (nextCur == null) break;

            Pos mp = posBySymbol.get(nextSymbol);
            int curIdx = mp.idxByInterval.get(nextInterval);
            mp.idxByInterval.put(nextInterval, curIdx + 1);

            // Timestamp grouping: collect same-time candles from other intervals
            List<int[]> sameTimeIntervals = new ArrayList<int[]>();
            sameTimeIntervals.add(new int[]{nextInterval, curIdx});

            List<int[]> toAdvance = new ArrayList<int[]>();
            for (Map.Entry<Integer, Integer> ie2 : mp.idxByInterval.entrySet()) {
                int intv2 = ie2.getKey();
                if (intv2 == nextInterval) continue;
                if (stratsByInterval.get(intv2) == null || stratsByInterval.get(intv2).isEmpty()) continue;
                int idx2 = ie2.getValue();
                List<StockCandle> series2 = mp.seriesByInterval.get(intv2);
                if (series2 == null || idx2 >= series2.size() || idx2 < 1) continue;
                StockCandle cur2 = series2.get(idx2);
                if (cur2 != null && cur2.candle_date_time_utc != null
                        && cur2.candle_date_time_utc.equals(nextCur.candle_date_time_utc)) {
                    sameTimeIntervals.add(new int[]{intv2, idx2});
                    toAdvance.add(new int[]{intv2, idx2 + 1});
                }
            }
            for (int[] adv : toAdvance) {
                mp.idxByInterval.put(adv[0], adv[1]);
            }

            double close = nextCur.trade_price;
            if (close < mp.lastClose && mp.lastClose > 0) mp.downStreak++;
            else if (close >= mp.lastClose || mp.lastClose == 0) mp.downStreak = 0;
            mp.lastClose = close;

            boolean open = mp.qty > 0;

            // Effective group settings
            SimulationParams.SymbolGroupSettings sgs = hasGroups && symbolGroupMap != null
                    ? symbolGroupMap.get(nextSymbol) : null;
            double effTpPct = (sgs != null ? sgs.tpPct : tpPct);
            double effSlPct = (sgs != null ? sgs.slPct : slPct);
            int effMaxAddBuys = (sgs != null ? sgs.maxAddBuys : maxAddBuysGlobal);
            boolean effStrategyLock = (sgs != null ? sgs.strategyLock : strategyLockEnabled);
            double effMinConfidence = (sgs != null ? sgs.minConfidence : minConfidence);
            int effTimeStopMin = (sgs != null ? sgs.timeStopMinutes : timeStopMinutes);
            double effBaseOrderKrw = (sgs != null ? sgs.baseOrderKrw : baseOrderKrw);

            // TP/SL check
            SignalEvaluator.Result tpSlResult = SignalEvaluator.checkTpSl(open, mp.avg, close, effTpPct, effSlPct);
            if (tpSlResult != null) {
                double fill = close * (1.0 - tradeProps.getSlippageRate());
                double gross = mp.qty * fill;
                double fee = gross * strategyCfg.getFeeRate();
                double realized = (gross - fee) - (mp.qty * mp.avg);
                st.sellCount++;
                if (tpSlResult.patternType != null && tpSlResult.patternType.equals("STOP_LOSS")) st.slSellCount++;
                else st.tpSellCount++;
                if (realized > 0) st.winCount++;
                if (detailed) {
                    BacktestTradeRow row = makeRow(nextCur, nextSymbol, "SELL", tpSlResult.patternType,
                            fill, mp.qty, realized, tpSlResult.reason, mp.avg);
                    row.confidence = 0;
                    row.candleUnitMin = nextInterval;
                    st.trades.add(row);
                }
                mp.reset();
                st.capital += (gross - fee);
                continue;
            }

            // Time Stop check
            if (effTimeStopMin > 0 && open && mp.entryTsMs > 0 && nextCur.candle_date_time_utc != null) {
                long curTsMs = java.time.LocalDateTime.parse(nextCur.candle_date_time_utc)
                        .toInstant(ZoneOffset.UTC).toEpochMilli();
                long elapsedMin = (curTsMs - mp.entryTsMs) / 60000L;
                if (elapsedMin >= effTimeStopMin) {
                    boolean isBuyOnlyEntry = false;
                    if (mp.entryStrategy != null && !mp.entryStrategy.isEmpty()) {
                        try { isBuyOnlyEntry = StrategyType.valueOf(mp.entryStrategy).isBuyOnly(); }
                        catch (Exception ignore) {}
                    }
                    if (isBuyOnlyEntry) {
                        double pnlPct = mp.avg > 0 ? ((close - mp.avg) / mp.avg) * 100.0 : 0;
                        if (pnlPct < 0) {
                            double fill = close * (1.0 - tradeProps.getSlippageRate());
                            double gross = mp.qty * fill;
                            double fee = gross * strategyCfg.getFeeRate();
                            double realized = (gross - fee) - (mp.qty * mp.avg);
                            st.sellCount++;
                            if (realized > 0) st.winCount++;
                            if (detailed) {
                                String tsReason = String.format(Locale.ROOT,
                                        "TIME_STOP %dmin elapsed=%dmin entry=%s pnl=%.2f%%",
                                        effTimeStopMin, elapsedMin, mp.entryStrategy, pnlPct);
                                BacktestTradeRow tsRow = makeRow(nextCur, nextSymbol, "SELL", "TIME_STOP",
                                        fill, mp.qty, realized, tsReason, mp.avg);
                                tsRow.confidence = 0;
                                tsRow.candleUnitMin = nextInterval;
                                st.trades.add(tsRow);
                            }
                            mp.reset();
                            st.capital += (gross - fee);
                            continue;
                        }
                    }
                }
            }

            // Strategy evaluation across all same-time intervals
            com.example.stocks.db.PositionEntity syntheticPos = null;
            if (mp.qty > 0) {
                syntheticPos = new com.example.stocks.db.PositionEntity();
                syntheticPos.setQty((int) mp.qty);
                syntheticPos.setAvgPrice(mp.avg);
                syntheticPos.setAddBuys(mp.addBuys);
                syntheticPos.setEntryStrategy(mp.entryStrategy);
            }

            SignalEvaluator.Result bestEval = null;
            int bestEvalInterval = nextInterval;
            int bestPriority = -1;

            for (int[] sti : sameTimeIntervals) {
                int evalInterval = sti[0];
                int evalCurIdx = sti[1];

                List<StrategyType> groupStrats = stratsByInterval.get(evalInterval);
                if (groupStrats == null || groupStrats.isEmpty()) continue;

                // Group mode: filter to this symbol's group strategies
                if (hasGroups && sgs != null && sgs.strategyNames != null) {
                    List<StrategyType> filtered = new ArrayList<StrategyType>();
                    for (StrategyType stt : groupStrats) {
                        if (sgs.strategyNames.contains(stt.name())) filtered.add(stt);
                    }
                    groupStrats = filtered;
                    if (groupStrats.isEmpty()) continue;
                }

                List<StockCandle> series = mp.seriesByInterval.get(evalInterval);
                int windowEnd = Math.min(evalCurIdx, series.size());
                if (windowEnd < 2) continue;
                List<StockCandle> window = series.subList(0, windowEnd);

                StrategyContext ctx = new StrategyContext(nextSymbol, evalInterval, window,
                        syntheticPos, mp.downStreak, emaTrendFilterMap);
                SignalEvaluator.Result evalResult = SignalEvaluator.evaluateStrategies(
                        groupStrats, strategyFactory, ctx);

                if (evalResult.isEmpty()) continue;

                int priority;
                if (evalResult.signal.action == SignalAction.SELL) priority = 3;
                else if (evalResult.signal.action == SignalAction.ADD_BUY) priority = 2;
                else priority = 1;

                if (bestEval == null
                        || priority > bestPriority
                        || (priority == bestPriority && evalResult.confidence > bestEval.confidence)) {
                    bestEval = evalResult;
                    bestPriority = priority;
                    bestEvalInterval = evalInterval;
                }
            }

            if (bestEval == null || bestEval.isEmpty()) continue;

            SignalEvaluator.Result evalResult = bestEval;
            nextInterval = bestEvalInterval;
            String patternType = evalResult.patternType;
            String reason = evalResult.reason;

            // BUY: new position
            if (evalResult.signal.action == SignalAction.BUY && !open) {
                if (params.maxConcurrentPositions > 0) {
                    int openCount = 0;
                    for (Map.Entry<String, Pos> entry : posBySymbol.entrySet()) {
                        if (entry.getValue().qty > 0) openCount++;
                    }
                    if (openCount >= params.maxConcurrentPositions) continue;
                }
                if (effMinConfidence > 0 && evalResult.confidence < effMinConfidence) continue;
                double orderKrw = effBaseOrderKrw;
                if (orderKrw < tradeProps.getMinOrderKrw()) orderKrw = tradeProps.getMinOrderKrw();
                if (st.capital < orderKrw) {
                    if (st.capital >= tradeProps.getMinOrderKrw()) orderKrw = st.capital; else continue;
                }

                double fee = orderKrw * strategyCfg.getFeeRate();
                double net = orderKrw - fee;
                double fill = close * (1.0 + tradeProps.getSlippageRate());
                // Stock qty is integer (shares), but for backtest we use fractional for accuracy
                double addQtyVal = net / fill;

                mp.qty = addQtyVal;
                mp.avg = fill;
                mp.addBuys = 0;
                mp.entryStrategy = patternType;
                mp.entryTsMs = (nextCur.candle_date_time_utc != null)
                        ? java.time.LocalDateTime.parse(nextCur.candle_date_time_utc)
                            .toInstant(ZoneOffset.UTC).toEpochMilli()
                        : 0;

                st.capital -= orderKrw;
                if (detailed) {
                    BacktestTradeRow buyRow = makeRow(nextCur, nextSymbol, "BUY", patternType,
                            fill, addQtyVal, 0.0, reason, 0);
                    buyRow.confidence = evalResult.confidence;
                    buyRow.candleUnitMin = nextInterval;
                    st.trades.add(buyRow);
                }
                continue;
            }

            // Cross-strategy ADD_BUY
            if (evalResult.signal.action == SignalAction.BUY && open && mp.addBuys < effMaxAddBuys) {
                double currentPnlPct = mp.avg > 0 ? ((close - mp.avg) / mp.avg) * 100.0 : 0;
                if (currentPnlPct >= 0) continue;

                if (effMinConfidence > 0 && evalResult.confidence < effMinConfidence) continue;

                int next = mp.addBuys + 1;
                double orderKrw = effBaseOrderKrw * Math.pow(tradeProps.getAddBuyMultiplier(), next);
                if (orderKrw < tradeProps.getMinOrderKrw()) orderKrw = tradeProps.getMinOrderKrw();
                if (st.capital < orderKrw) {
                    if (st.capital >= tradeProps.getMinOrderKrw()) orderKrw = st.capital; else continue;
                }

                double fee = orderKrw * strategyCfg.getFeeRate();
                double net = orderKrw - fee;
                double fill = close * (1.0 + tradeProps.getSlippageRate());
                double addQtyVal = net / fill;

                double newQty = mp.qty + addQtyVal;
                mp.avg = (mp.avg * mp.qty + fill * addQtyVal) / newQty;
                mp.qty = newQty;
                mp.addBuys++;

                st.capital -= orderKrw;
                if (detailed) {
                    String xStratLabel = patternType + "(X-STRAT)";
                    String xReason = reason + " | cross-strat ADD_BUY (loss=" + String.format("%.2f", currentPnlPct) + "%)";
                    BacktestTradeRow abRow = makeRow(nextCur, nextSymbol, "ADD_BUY", xStratLabel,
                            fill, addQtyVal, 0.0, xReason, 0);
                    abRow.confidence = evalResult.confidence;
                    abRow.candleUnitMin = nextInterval;
                    st.trades.add(abRow);
                }
                continue;
            }

            // ADD_BUY: same strategy
            if (evalResult.signal.action == SignalAction.ADD_BUY && open && mp.addBuys < effMaxAddBuys) {
                if (effStrategyLock && mp.entryStrategy != null
                        && !mp.entryStrategy.isEmpty() && !mp.entryStrategy.equals(patternType)) {
                    continue;
                }
                int next = mp.addBuys + 1;
                double orderKrw = effBaseOrderKrw * Math.pow(tradeProps.getAddBuyMultiplier(), next);
                if (orderKrw < tradeProps.getMinOrderKrw()) orderKrw = tradeProps.getMinOrderKrw();
                if (st.capital < orderKrw) {
                    if (st.capital >= tradeProps.getMinOrderKrw()) orderKrw = st.capital; else continue;
                }

                double fee = orderKrw * strategyCfg.getFeeRate();
                double net = orderKrw - fee;
                double fill = close * (1.0 + tradeProps.getSlippageRate());
                double addQtyVal = net / fill;

                double newQty = mp.qty + addQtyVal;
                mp.avg = (mp.avg * mp.qty + fill * addQtyVal) / newQty;
                mp.qty = newQty;
                mp.addBuys++;

                st.capital -= orderKrw;
                if (detailed) {
                    BacktestTradeRow abRow = makeRow(nextCur, nextSymbol, "ADD_BUY", patternType,
                            fill, addQtyVal, 0.0, reason, 0);
                    abRow.confidence = evalResult.confidence;
                    abRow.candleUnitMin = nextInterval;
                    st.trades.add(abRow);
                }
                continue;
            }

            // SELL: close position
            if (evalResult.signal.action == SignalAction.SELL && open) {
                if (effStrategyLock && !evalResult.isTpSl && mp.entryStrategy != null
                        && !mp.entryStrategy.isEmpty() && !mp.entryStrategy.equals(patternType)) {
                    boolean sellOnlyStrategy = false;
                    try { sellOnlyStrategy = StrategyType.valueOf(patternType).isSellOnly(); }
                    catch (Exception ignore) {}
                    if (!sellOnlyStrategy) continue;
                }
                double fill = close * (1.0 - tradeProps.getSlippageRate());
                double gross = mp.qty * fill;
                double fee = gross * strategyCfg.getFeeRate();
                double realized = (gross - fee) - (mp.qty * mp.avg);

                st.sellCount++;
                if (evalResult.isTpSl) {
                    if ("STOP_LOSS".equals(evalResult.patternType)) st.slSellCount++;
                    else st.tpSellCount++;
                } else {
                    st.patternSellCount++;
                }
                if (realized > 0) st.winCount++;

                if (detailed) {
                    BacktestTradeRow sellRow = makeRow(nextCur, nextSymbol, "SELL", patternType,
                            fill, mp.qty, realized, reason, mp.avg);
                    sellRow.confidence = evalResult.confidence;
                    sellRow.candleUnitMin = nextInterval;
                    st.trades.add(sellRow);
                }

                mp.reset();
                st.capital += (gross - fee);
            }
        }

        // Mark-to-market unrealized positions
        for (Map.Entry<String, Pos> pe : posBySymbol.entrySet()) {
            Pos mp = pe.getValue();
            if (mp.qty > 0 && mp.lastClose > 0) {
                double fill = mp.lastClose * (1.0 - tradeProps.getSlippageRate());
                double gross = mp.qty * fill;
                double fee = gross * strategyCfg.getFeeRate();
                st.capital += (gross - fee);
            }
        }

        return st;
    }

    // ===== Internal classes =====

    private static class SimState {
        double capital;
        int sellCount;
        int winCount;
        int tpSellCount;
        int slSellCount;
        int patternSellCount;
        List<BacktestTradeRow> trades;
    }

    private static class Pos {
        Map<Integer, List<StockCandle>> seriesByInterval = new HashMap<Integer, List<StockCandle>>();
        Map<Integer, Integer> idxByInterval = new HashMap<Integer, Integer>();
        double qty = 0.0;
        double avg = 0.0;
        int addBuys = 0;
        int downStreak = 0;
        String entryStrategy = null;
        long entryTsMs = 0;
        double lastClose = 0.0;

        void reset() {
            qty = 0.0; avg = 0.0; addBuys = 0; downStreak = 0;
            entryStrategy = null; entryTsMs = 0;
        }
    }

    private static BacktestTradeRow makeRow(StockCandle c, String symbol, String action, String type,
                                             double price, double qty, double pnl, String note, double avgBuyPrice) {
        BacktestTradeRow r = new BacktestTradeRow();
        r.ts = (c.candle_date_time_utc == null ? null : c.candle_date_time_utc.toString());
        r.symbol = symbol;
        r.action = action;
        r.orderType = type;
        r.price = price;
        r.qty = qty;
        r.pnlKrw = pnl;
        r.note = note;
        r.avgBuyPrice = avgBuyPrice;
        if (avgBuyPrice > 0 && price > 0) {
            r.roiPercent = ((price - avgBuyPrice) / avgBuyPrice) * 100.0;
        }
        return r;
    }
}
