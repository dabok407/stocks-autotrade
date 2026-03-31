package com.example.stocks.backtest;

import com.example.stocks.api.BacktestResponse;
import com.example.stocks.api.BacktestTradeRow;
import com.example.stocks.config.StrategyProperties;
import com.example.stocks.config.TradeProperties;
import com.example.stocks.market.StockCandle;
import com.example.stocks.strategy.*;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Pure computation backtest simulator.
 * Runs simulations on pre-loaded candle data without API calls.
 * Thread safe: all state is created within method scope.
 */
@Component
public class BacktestSimulator {

    private final StrategyFactory strategyFactory;
    private final StrategyProperties strategyCfg;
    private final TradeProperties tradeProps;

    public BacktestSimulator(StrategyFactory strategyFactory,
                              StrategyProperties strategyCfg,
                              TradeProperties tradeProps) {
        this.strategyFactory = strategyFactory;
        this.strategyCfg = strategyCfg;
        this.tradeProps = tradeProps;
    }

    /**
     * Run simulation on pre-loaded candle data.
     *
     * @param params      simulation parameters
     * @param candlesByMI Map<symbol, Map<intervalMin, List<StockCandle>>>
     * @return backtest result
     */
    public BacktestResponse simulate(SimulationParams params,
                                      Map<String, Map<Integer, List<StockCandle>>> candlesByMI) {

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

        BacktestResponse res = new BacktestResponse();
        res.candleUnitMin = params.candleUnitMin;
        res.usedTpPct = tpPct;
        res.usedSlPct = slPct;
        for (StrategyType t : params.strategies) res.strategies.add(t.name());

        int totalCandleCount = 0;
        for (Map.Entry<String, Map<Integer, List<StockCandle>>> e : candlesByMI.entrySet()) {
            res.symbols.add(e.getKey());
            for (List<StockCandle> cs : e.getValue().values()) {
                totalCandleCount += cs.size();
            }
        }
        res.candleCount = totalCandleCount;

        int sellCount = 0, winCount = 0;
        int tpSellCount = 0, slSellCount = 0, patternSellCount = 0;

        // Per-symbol position state
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

            double close = nextCur.trade_price;
            if (close < mp.lastClose && mp.lastClose > 0) mp.downStreak++;
            else if (close >= mp.lastClose || mp.lastClose == 0) mp.downStreak = 0;
            mp.lastClose = close;

            boolean open = mp.qty > 0;

            // TP/SL check
            SignalEvaluator.Result tpSlResult = SignalEvaluator.checkTpSl(open, mp.avg, close, tpPct, slPct);
            if (tpSlResult != null) {
                double fill = close * (1.0 - tradeProps.getSlippageRate());
                double gross = mp.qty * fill;
                double fee = gross * strategyCfg.getFeeRate();
                double realized = (gross - fee) - (mp.qty * mp.avg);
                sellCount++;
                if (tpSlResult.patternType != null && tpSlResult.patternType.equals("STOP_LOSS")) slSellCount++;
                else tpSellCount++;
                if (realized > 0) winCount++;
                BacktestTradeRow row = makeRow(nextCur, nextSymbol, "SELL", tpSlResult.patternType,
                        fill, mp.qty, realized, tpSlResult.reason, mp.avg);
                row.confidence = 0;
                row.candleUnitMin = nextInterval;
                res.trades.add(row);
                mp.reset();
                capital += (gross - fee);
                continue;
            }

            // Time Stop check
            if (timeStopMinutes > 0 && open && mp.entryTsMs > 0 && nextCur.candle_date_time_utc != null) {
                long curTsMs = java.time.LocalDateTime.parse(nextCur.candle_date_time_utc)
                        .toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
                long elapsedMin = (curTsMs - mp.entryTsMs) / 60000L;
                if (elapsedMin >= timeStopMinutes) {
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
                            sellCount++;
                            if (realized > 0) winCount++;
                            String tsReason = String.format(Locale.ROOT,
                                    "TIME_STOP %dmin elapsed=%dmin entry=%s pnl=%.2f%%",
                                    timeStopMinutes, elapsedMin, mp.entryStrategy, pnlPct);
                            BacktestTradeRow tsRow = makeRow(nextCur, nextSymbol, "SELL", "TIME_STOP",
                                    fill, mp.qty, realized, tsReason, mp.avg);
                            tsRow.confidence = 0;
                            tsRow.candleUnitMin = nextInterval;
                            res.trades.add(tsRow);
                            mp.reset();
                            capital += (gross - fee);
                            continue;
                        }
                    }
                }
            }

            // Strategy evaluation
            List<StrategyType> groupStrats = stratsByInterval.get(nextInterval);
            if (groupStrats == null || groupStrats.isEmpty()) continue;

            List<StockCandle> series = mp.seriesByInterval.get(nextInterval);
            int windowEnd = Math.min(curIdx, series.size());
            if (windowEnd < 2) continue;
            List<StockCandle> window = series.subList(0, windowEnd);

            com.example.stocks.db.PositionEntity syntheticPos = null;
            if (mp.qty > 0) {
                syntheticPos = new com.example.stocks.db.PositionEntity();
                syntheticPos.setQty((int) mp.qty);
                syntheticPos.setAvgPrice(mp.avg);
                syntheticPos.setAddBuys(mp.addBuys);
                syntheticPos.setEntryStrategy(mp.entryStrategy);
            }
            StrategyContext ctx = new StrategyContext(nextSymbol, nextInterval, window, syntheticPos,
                    mp.downStreak, emaTrendFilterMap);
            SignalEvaluator.Result evalResult = SignalEvaluator.evaluateStrategies(groupStrats, strategyFactory, ctx);

            if (evalResult.isEmpty()) continue;

            String patternType = evalResult.patternType;
            String reason = evalResult.reason;

            if (evalResult.signal.action == SignalAction.BUY && !open) {
                if (minConfidence > 0 && evalResult.confidence < minConfidence) continue;
                double orderKrw = baseOrderKrw;
                if (orderKrw < tradeProps.getMinOrderKrw()) orderKrw = tradeProps.getMinOrderKrw();
                if (capital < orderKrw) {
                    if (capital >= tradeProps.getMinOrderKrw()) orderKrw = capital; else continue;
                }

                double fee = orderKrw * strategyCfg.getFeeRate();
                double fill = close * (1.0 + tradeProps.getSlippageRate());
                mp.qty = (orderKrw - fee) / fill;
                mp.avg = fill;
                mp.addBuys = 0;
                mp.entryStrategy = patternType;
                mp.entryTsMs = nextCur.candle_date_time_utc != null
                        ? java.time.LocalDateTime.parse(nextCur.candle_date_time_utc)
                            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
                        : 0;

                capital -= orderKrw;
                BacktestTradeRow buyRow = makeRow(nextCur, nextSymbol, "BUY", patternType,
                        fill, mp.qty, 0.0, reason, 0);
                buyRow.confidence = evalResult.confidence;
                buyRow.candleUnitMin = nextInterval;
                res.trades.add(buyRow);
                continue;
            }

            if (evalResult.signal.action == SignalAction.ADD_BUY && open && mp.addBuys < maxAddBuysGlobal) {
                if (strategyLockEnabled && mp.entryStrategy != null
                        && !mp.entryStrategy.isEmpty() && !mp.entryStrategy.equals(patternType)) {
                    continue;
                }
                int next = mp.addBuys + 1;
                double orderKrw = baseOrderKrw * Math.pow(tradeProps.getAddBuyMultiplier(), next);
                if (orderKrw < tradeProps.getMinOrderKrw()) orderKrw = tradeProps.getMinOrderKrw();
                if (capital < orderKrw) {
                    if (capital >= tradeProps.getMinOrderKrw()) orderKrw = capital; else continue;
                }

                double fee = orderKrw * strategyCfg.getFeeRate();
                double fill = close * (1.0 + tradeProps.getSlippageRate());
                double addQtyVal = (orderKrw - fee) / fill;
                double newQty = mp.qty + addQtyVal;
                mp.avg = (mp.avg * mp.qty + fill * addQtyVal) / newQty;
                mp.qty = newQty;
                mp.addBuys++;

                capital -= orderKrw;
                BacktestTradeRow abRow = makeRow(nextCur, nextSymbol, "ADD_BUY", patternType,
                        fill, addQtyVal, 0.0, reason, 0);
                abRow.confidence = evalResult.confidence;
                abRow.candleUnitMin = nextInterval;
                res.trades.add(abRow);
                continue;
            }

            if (evalResult.signal.action == SignalAction.SELL && open) {
                if (strategyLockEnabled && !evalResult.isTpSl && mp.entryStrategy != null
                        && !mp.entryStrategy.isEmpty() && !mp.entryStrategy.equals(patternType)) {
                    boolean sellOnly = false;
                    try { sellOnly = StrategyType.valueOf(patternType).isSellOnly(); }
                    catch (Exception ignore) {}
                    if (!sellOnly) continue;
                }
                double fill = close * (1.0 - tradeProps.getSlippageRate());
                double gross = mp.qty * fill;
                double fee = gross * strategyCfg.getFeeRate();
                double realized = (gross - fee) - (mp.qty * mp.avg);

                sellCount++;
                if (evalResult.isTpSl) {
                    if ("STOP_LOSS".equals(evalResult.patternType)) slSellCount++;
                    else tpSellCount++;
                } else { patternSellCount++; }
                if (realized > 0) winCount++;

                BacktestTradeRow sellRow = makeRow(nextCur, nextSymbol, "SELL", patternType,
                        fill, mp.qty, realized, reason, mp.avg);
                sellRow.confidence = evalResult.confidence;
                sellRow.candleUnitMin = nextInterval;
                res.trades.add(sellRow);

                mp.reset();
                capital += (gross - fee);
            }
        }

        res.tradesCount = res.trades.size();
        res.wins = winCount;
        res.winRate = (sellCount == 0 ? 0.0 : (winCount * 100.0 / sellCount));
        res.finalCapital = capital;
        res.tpSellCount = tpSellCount;
        res.slSellCount = slSellCount;
        res.patternSellCount = patternSellCount;

        double start = params.capitalKrw;
        res.totalReturn = capital - start;
        res.roi = (start <= 0 ? 0.0 : (res.totalReturn / start) * 100.0);
        res.totalPnl = res.totalReturn;
        res.totalRoi = res.roi;
        res.totalTrades = res.tradesCount;

        return res;
    }

    // ===== Lightweight simulation (for optimization) =====

    public OptimizationMetrics simulateFast(SimulationParams params,
                                             Map<String, Map<Integer, List<StockCandle>>> candlesByMI) {
        // Delegate to TradingEngine for consistency
        TradingEngine engine = new TradingEngine(strategyFactory, strategyCfg, tradeProps);
        return engine.simulateFast(params, candlesByMI);
    }

    // ===== Internal classes =====

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

    /**
     * Lightweight optimization metrics.
     */
    public static class OptimizationMetrics {
        public double roi;
        public double winRate;
        public int totalTrades;
        public int wins;
        public double finalCapital;
        public double totalPnl;
        public int tpSellCount;
        public int slSellCount;
        public int patternSellCount;
    }

    private BacktestTradeRow makeRow(StockCandle c, String symbol, String action, String type,
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
