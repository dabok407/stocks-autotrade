package com.example.stocks.backtest;

import com.example.stocks.config.StrategyProperties;
import com.example.stocks.config.TradeProperties;
import com.example.stocks.db.OptimizationResultEntity;
import com.example.stocks.db.OptimizationResultRepository;
import com.example.stocks.db.PositionEntity;
import com.example.stocks.market.CandleCacheService;
import com.example.stocks.market.StockCandle;
import com.example.stocks.strategy.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Batch optimization service: signal pre-computation (Signal Tape) + dense array replay.
 * Phase 1: per-strategy optimization across all symbol/interval/EMA/risk combinations.
 */
@Service
public class BatchOptimizationService {

    private static final Logger log = LoggerFactory.getLogger(BatchOptimizationService.class);

    // Parameter grid (adapted for stocks)
    private static final String[] SYMBOLS = {"005930", "035420", "000660", "051910", "005380"};
    private static final int[] INTERVALS = {5, 15, 30, 60, 240};
    private static final double[] TP_VALUES = {0.5, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0, 15.0};
    private static final double[] SL_VALUES = {0.3, 0.5, 1.0, 2.0, 3.0, 4.0, 5.0, 8.0, 10.0};
    private static final int[] MAX_ADD_BUYS = {0, 1, 2, 3};
    private static final double[] MIN_CONFIDENCES = {0, 5, 6, 7, 8};
    private static final boolean[] STRATEGY_LOCKS = {false, true};
    private static final int[] TIME_STOPS = {0, 240, 480, 720, 1440, 2880};
    private static final int[] EMA_PERIODS = {0, 20, 50, 75, 100};

    private static final double DEFAULT_CAPITAL = 1000000.0;
    private static final int RISK_COMBOS = TP_VALUES.length * SL_VALUES.length
            * MAX_ADD_BUYS.length * MIN_CONFIDENCES.length * STRATEGY_LOCKS.length * TIME_STOPS.length;

    private static final int MAX_WINDOW = 200;
    private static final int HAS_POS_EVAL_INTERVAL = 3;

    private final StrategyFactory strategyFactory;
    private final CandleCacheService candleCacheService;
    private final StrategyProperties strategyCfg;
    private final TradeProperties tradeProps;
    private final OptimizationResultRepository resultRepo;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong totalCombinations = new AtomicLong(0);
    private final AtomicLong completedCombinations = new AtomicLong(0);
    private volatile String currentRunId = "";
    private volatile String statusMessage = "";

    private final ConcurrentSkipListSet<ResultEntry> topResults =
            new ConcurrentSkipListSet<ResultEntry>(new Comparator<ResultEntry>() {
                @Override
                public int compare(ResultEntry a, ResultEntry b) {
                    int c = Double.compare(b.roi, a.roi);
                    if (c != 0) return c;
                    return Long.compare(a.id, b.id);
                }
            });
    private static final int TOP_N = 500;
    private final AtomicLong resultIdSeq = new AtomicLong(0);

    public BatchOptimizationService(StrategyFactory strategyFactory,
                                     CandleCacheService candleCacheService,
                                     StrategyProperties strategyCfg,
                                     TradeProperties tradeProps,
                                     OptimizationResultRepository resultRepo) {
        this.strategyFactory = strategyFactory;
        this.candleCacheService = candleCacheService;
        this.strategyCfg = strategyCfg;
        this.tradeProps = tradeProps;
        this.resultRepo = resultRepo;
    }

    public boolean isRunning() { return running.get(); }
    public long getTotalCombinations() { return totalCombinations.get(); }
    public long getCompletedCombinations() { return completedCombinations.get(); }
    public String getCurrentRunId() { return currentRunId; }
    public String getStatusMessage() { return statusMessage; }

    public String startOptimization() {
        if (!running.compareAndSet(false, true)) return currentRunId;
        currentRunId = UUID.randomUUID().toString().substring(0, 8);
        topResults.clear();
        resultIdSeq.set(0);
        completedCombinations.set(0);
        totalCombinations.set(0);

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runOptimization(currentRunId);
                } catch (Exception e) {
                    log.error("Optimization error", e);
                    statusMessage = "Error: " + e.getMessage();
                } finally {
                    running.set(false);
                }
            }
        }, "batch-optimization");
        t.setDaemon(true);
        t.start();
        return currentRunId;
    }

    private void runOptimization(String runId) {
        statusMessage = "Loading candle cache...";
        log.info("=== Optimization start (runId={}) ===", runId);

        Map<String, Map<Integer, List<StockCandle>>> allCandles = candleCacheService.getAllCached();
        if (allCandles.isEmpty()) {
            statusMessage = "Candle cache empty";
            return;
        }

        List<StrategyType> buyStrategies = new ArrayList<StrategyType>();
        for (StrategyType st : StrategyType.values()) {
            if (!st.isSellOnly()) buyStrategies.add(st);
        }

        // Build tape tasks
        List<TapeTask> tasks = new ArrayList<TapeTask>();
        for (StrategyType st : buyStrategies) {
            boolean configurable = "CONFIGURABLE".equals(st.emaTrendFilterMode());
            int[] emaGrid = configurable ? EMA_PERIODS : new int[]{0};
            for (String symbol : SYMBOLS) {
                Map<Integer, List<StockCandle>> mc = allCandles.get(symbol);
                if (mc == null) continue;
                for (int interval : INTERVALS) {
                    List<StockCandle> candles = mc.get(interval);
                    if (candles == null || candles.size() < 10) continue;
                    for (int ema : emaGrid) {
                        TapeTask task = new TapeTask();
                        task.strategy = st;
                        task.symbol = symbol;
                        task.interval = interval;
                        task.emaPeriod = ema;
                        task.candles = candles;
                        tasks.add(task);
                    }
                }
            }
        }

        int tapeCount = tasks.size();
        totalCombinations.set((long) tapeCount * RISK_COMBOS);
        log.info("Tapes: {}, Risk combos: {}, Total: {}", tapeCount, RISK_COMBOS, (long) tapeCount * RISK_COMBOS);

        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        ExecutorService executor = Executors.newFixedThreadPool(cores);

        statusMessage = String.format("Optimizing... (%d tapes x %d combos)", tapeCount, RISK_COMBOS);
        long startTime = System.currentTimeMillis();

        List<Future<?>> futures = new ArrayList<Future<?>>();
        for (final TapeTask task : tasks) {
            futures.add(executor.submit(new Runnable() {
                @Override
                public void run() {
                    processTask(task);
                }
            }));
        }

        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception e) { log.error("Task error", e); }
        }
        executor.shutdown();

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("=== Complete: {}m {}s ===", elapsed / 60000, (elapsed % 60000) / 1000);

        statusMessage = "Saving results...";
        saveTopResults(runId);

        statusMessage = String.format("Complete! %d combos, %dm %ds, Top %d saved",
                completedCombinations.get(), elapsed / 60000, (elapsed % 60000) / 1000,
                Math.min(TOP_N, topResults.size()));
        log.info(statusMessage);
    }

    // ===== Tape task =====

    static class TapeTask {
        StrategyType strategy;
        String symbol;
        int interval;
        int emaPeriod;
        List<StockCandle> candles;
    }

    private void processTask(TapeTask task) {
        try {
            FastTape tape = generateFastTape(task);
            if (tape == null || tape.signalCount == 0) {
                completedCombinations.addAndGet(RISK_COMBOS);
                return;
            }

            final double slippage = tradeProps.getSlippageRate();
            final double feeRate = strategyCfg.getFeeRate();
            final double minOrderKrw = tradeProps.getMinOrderKrw();
            final double addBuyMult = tradeProps.getAddBuyMultiplier();

            for (double tp : TP_VALUES) {
                for (double sl : SL_VALUES) {
                    for (int maxAdd : MAX_ADD_BUYS) {
                        for (double minConf : MIN_CONFIDENCES) {
                            for (boolean lock : STRATEGY_LOCKS) {
                                for (int timeStop : TIME_STOPS) {
                                    replayFastTape(tape, tp, sl, maxAdd, minConf, lock, timeStop,
                                            slippage, feeRate, minOrderKrw, addBuyMult);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Task error: {} {} {}min: {}", task.strategy, task.symbol, task.interval, e.getMessage());
            completedCombinations.addAndGet(RISK_COMBOS);
        }
    }

    // ===== Tape generation (evaluate strategy once) =====

    static class FastTape {
        String symbol;
        StrategyType strategy;
        int interval;
        int emaPeriod;
        double[] closes;
        long[] timestamps;
        int[] sigCandleIdx;
        byte[] sigType;       // 1=BUY, 2=SELL, 3=ADD_BUY
        double[] sigConf;
        String[] sigPattern;
        int signalCount;
    }

    static class SparseSignal {
        int candleIdx;
        byte type;     // 1=BUY, 2=SELL, 3=ADD_BUY
        double confidence;
        String patternType;
    }

    private FastTape generateFastTape(TapeTask task) {
        List<StockCandle> candles = task.candles;
        StrategyType strategy = task.strategy;
        boolean selfContained = strategy.isSelfContained();

        Map<String, Integer> emaMap = new HashMap<String, Integer>();
        emaMap.put(strategy.name(), task.emaPeriod);
        List<StrategyType> singleStrat = Collections.singletonList(strategy);

        int n = candles.size() - 1;
        if (n < 1) return null;

        double[] closes = new double[n];
        long[] timestamps = new long[n];
        List<SparseSignal> signalList = new ArrayList<SparseSignal>();

        for (int idx = 1; idx < candles.size(); idx++) {
            StockCandle cur = candles.get(idx);
            if (cur == null || cur.candle_date_time_utc == null) {
                closes[idx - 1] = 0;
                timestamps[idx - 1] = 0;
                continue;
            }

            int arrIdx = idx - 1;
            closes[arrIdx] = cur.trade_price;
            try {
                timestamps[arrIdx] = LocalDateTime.parse(cur.candle_date_time_utc)
                        .toInstant(ZoneOffset.UTC).toEpochMilli();
            } catch (Exception e) {
                timestamps[arrIdx] = 0;
            }

            int windowEnd = Math.min(idx + 1, candles.size());
            if (windowEnd < 2) continue;
            int windowStart = Math.max(0, windowEnd - MAX_WINDOW);
            List<StockCandle> window = candles.subList(windowStart, windowEnd);

            // (A) BUY signal (no position)
            StrategyContext ctxNone = new StrategyContext(
                    task.symbol, task.interval, window, null, 0, emaMap);
            SignalEvaluator.Result buyResult = SignalEvaluator.evaluateStrategies(
                    singleStrat, strategyFactory, ctxNone);

            if (!buyResult.isEmpty() && buyResult.signal.action == SignalAction.BUY) {
                SparseSignal sig = new SparseSignal();
                sig.candleIdx = arrIdx;
                sig.type = 1;
                sig.confidence = buyResult.confidence;
                sig.patternType = buyResult.patternType;
                signalList.add(sig);
            }

            // (B) SELL/ADD_BUY signal (self-contained, has position)
            if (selfContained && (idx % HAS_POS_EVAL_INTERVAL == 0)) {
                PositionEntity syntheticPos = new PositionEntity();
                syntheticPos.setQty(1);
                syntheticPos.setAvgPrice(BigDecimal.valueOf(cur.trade_price));
                syntheticPos.setAddBuys(0);
                syntheticPos.setEntryStrategy(strategy.name());

                StrategyContext ctxOpen = new StrategyContext(
                        task.symbol, task.interval, window, syntheticPos, 0, emaMap);
                SignalEvaluator.Result sellResult = SignalEvaluator.evaluateStrategies(
                        singleStrat, strategyFactory, ctxOpen);

                if (!sellResult.isEmpty()) {
                    SignalAction act = sellResult.signal.action;
                    if (act == SignalAction.SELL || act == SignalAction.ADD_BUY) {
                        SparseSignal sig = new SparseSignal();
                        sig.candleIdx = arrIdx;
                        sig.type = (act == SignalAction.SELL) ? (byte) 2 : (byte) 3;
                        sig.confidence = sellResult.confidence;
                        sig.patternType = sellResult.patternType;
                        signalList.add(sig);
                    }
                }
            }
        }

        if (signalList.isEmpty()) return null;

        FastTape tape = new FastTape();
        tape.symbol = task.symbol;
        tape.strategy = task.strategy;
        tape.interval = task.interval;
        tape.emaPeriod = task.emaPeriod;
        tape.closes = closes;
        tape.timestamps = timestamps;
        tape.signalCount = signalList.size();
        tape.sigCandleIdx = new int[signalList.size()];
        tape.sigType = new byte[signalList.size()];
        tape.sigConf = new double[signalList.size()];
        tape.sigPattern = new String[signalList.size()];

        for (int i = 0; i < signalList.size(); i++) {
            SparseSignal s = signalList.get(i);
            tape.sigCandleIdx[i] = s.candleIdx;
            tape.sigType[i] = s.type;
            tape.sigConf[i] = s.confidence;
            tape.sigPattern[i] = s.patternType;
        }
        return tape;
    }

    // ===== Tape replay (risk parameter sweep) =====

    private void replayFastTape(FastTape tape, double tp, double sl, int maxAdd,
                                 double minConf, boolean lock, int timeStop,
                                 double slippage, double feeRate, double minOrderKrw, double addBuyMult) {
        double capital = DEFAULT_CAPITAL;
        double baseOrder = capital * 0.1;
        double qty = 0, avg = 0;
        int addBuys = 0;
        long entryTsMs = 0;
        String entryPattern = null;
        int sellCount = 0, winCount = 0;
        int tpSells = 0, slSells = 0, patternSells = 0;
        double totalPnl = 0;
        int sigIdx = 0;

        double tpRate = tp / 100.0;
        double slRate = sl / 100.0;

        for (int i = 0; i < tape.closes.length; i++) {
            double close = tape.closes[i];
            if (close <= 0) continue;

            boolean open = qty > 0;

            // TP/SL check
            if (open && avg > 0) {
                double pnlPct = (close - avg) / avg;
                if (pnlPct >= tpRate) {
                    double fill = close * (1.0 - slippage);
                    double gross = qty * fill;
                    double fee = gross * feeRate;
                    double realized = (gross - fee) - (qty * avg);
                    sellCount++;
                    tpSells++;
                    if (realized > 0) winCount++;
                    totalPnl += realized;
                    capital += (gross - fee);
                    qty = 0; avg = 0; addBuys = 0; entryTsMs = 0; entryPattern = null;
                    continue;
                }
                if (pnlPct <= -slRate) {
                    double fill = close * (1.0 - slippage);
                    double gross = qty * fill;
                    double fee = gross * feeRate;
                    double realized = (gross - fee) - (qty * avg);
                    sellCount++;
                    slSells++;
                    if (realized > 0) winCount++;
                    totalPnl += realized;
                    capital += (gross - fee);
                    qty = 0; avg = 0; addBuys = 0; entryTsMs = 0; entryPattern = null;
                    continue;
                }
            }

            // Time Stop
            if (timeStop > 0 && open && entryTsMs > 0 && tape.timestamps[i] > 0) {
                long elapsed = (tape.timestamps[i] - entryTsMs) / 60000L;
                if (elapsed >= timeStop && close < avg) {
                    double fill = close * (1.0 - slippage);
                    double gross = qty * fill;
                    double fee = gross * feeRate;
                    double realized = (gross - fee) - (qty * avg);
                    sellCount++;
                    slSells++;
                    if (realized > 0) winCount++;
                    totalPnl += realized;
                    capital += (gross - fee);
                    qty = 0; avg = 0; addBuys = 0; entryTsMs = 0; entryPattern = null;
                    continue;
                }
            }

            // Process signals at this candle
            while (sigIdx < tape.signalCount && tape.sigCandleIdx[sigIdx] == i) {
                byte type = tape.sigType[sigIdx];
                double conf = tape.sigConf[sigIdx];
                String pat = tape.sigPattern[sigIdx];
                sigIdx++;

                if (type == 1 && !open) { // BUY
                    if (minConf > 0 && conf < minConf) continue;
                    double orderKrw = baseOrder;
                    if (capital < orderKrw) {
                        if (capital >= minOrderKrw) orderKrw = capital; else continue;
                    }
                    double fee = orderKrw * feeRate;
                    double fill = close * (1.0 + slippage);
                    qty = (orderKrw - fee) / fill;
                    avg = fill;
                    addBuys = 0;
                    entryPattern = pat;
                    entryTsMs = tape.timestamps[i];
                    capital -= orderKrw;
                    open = true;
                } else if (type == 3 && open && addBuys < maxAdd) { // ADD_BUY
                    if (lock && entryPattern != null && !entryPattern.equals(pat)) continue;
                    int next = addBuys + 1;
                    double orderKrw = baseOrder * Math.pow(addBuyMult, next);
                    if (orderKrw < minOrderKrw) orderKrw = minOrderKrw;
                    if (capital < orderKrw) {
                        if (capital >= minOrderKrw) orderKrw = capital; else continue;
                    }
                    double fee = orderKrw * feeRate;
                    double fill = close * (1.0 + slippage);
                    double addQty = (orderKrw - fee) / fill;
                    double newQty = qty + addQty;
                    avg = (avg * qty + fill * addQty) / newQty;
                    qty = newQty;
                    addBuys++;
                    capital -= orderKrw;
                } else if (type == 2 && open) { // SELL (pattern)
                    if (lock && entryPattern != null && !entryPattern.equals(pat)) {
                        boolean sellOnly = false;
                        try { sellOnly = StrategyType.valueOf(pat).isSellOnly(); }
                        catch (Exception ignore) {}
                        if (!sellOnly) continue;
                    }
                    double fill = close * (1.0 - slippage);
                    double gross = qty * fill;
                    double fee = gross * feeRate;
                    double realized = (gross - fee) - (qty * avg);
                    sellCount++;
                    patternSells++;
                    if (realized > 0) winCount++;
                    totalPnl += realized;
                    capital += (gross - fee);
                    qty = 0; avg = 0; addBuys = 0; entryTsMs = 0; entryPattern = null;
                    open = false;
                }
            }

            // Skip past remaining signals at this candle index
            while (sigIdx < tape.signalCount && tape.sigCandleIdx[sigIdx] == i) {
                sigIdx++;
            }
        }

        // Close remaining position at last price
        if (qty > 0) {
            double lastClose = tape.closes[tape.closes.length - 1];
            if (lastClose > 0) {
                double fill = lastClose * (1.0 - slippage);
                double gross = qty * fill;
                double fee = gross * feeRate;
                capital += (gross - fee);
            }
        }

        completedCombinations.incrementAndGet();

        double roi = ((capital - DEFAULT_CAPITAL) / DEFAULT_CAPITAL) * 100.0;
        double winRate = sellCount == 0 ? 0.0 : (winCount * 100.0 / sellCount);

        if (sellCount >= 2) {
            ResultEntry entry = new ResultEntry();
            entry.id = resultIdSeq.incrementAndGet();
            entry.strategy = tape.strategy.name();
            entry.symbol = tape.symbol;
            entry.interval = tape.interval;
            entry.emaPeriod = tape.emaPeriod;
            entry.tp = tp;
            entry.sl = sl;
            entry.maxAddBuys = maxAdd;
            entry.minConfidence = minConf;
            entry.strategyLock = lock;
            entry.timeStop = timeStop;
            entry.roi = roi;
            entry.winRate = winRate;
            entry.totalTrades = sellCount;
            entry.wins = winCount;
            entry.totalPnl = totalPnl;
            entry.finalCapital = capital;
            entry.tpSellCount = tpSells;
            entry.slSellCount = slSells;
            entry.patternSellCount = patternSells;

            topResults.add(entry);
            while (topResults.size() > TOP_N * 2) {
                topResults.pollLast();
            }
        }
    }

    // ===== Save results to DB =====

    private void saveTopResults(String runId) {
        int count = 0;
        for (ResultEntry e : topResults) {
            if (count >= TOP_N) break;
            OptimizationResultEntity entity = new OptimizationResultEntity();
            entity.setRunId(runId);
            entity.setStrategyType(e.strategy);
            entity.setSymbol(e.symbol);
            entity.setIntervalMin(e.interval);
            entity.setEmaPeriod(e.emaPeriod);
            entity.setTpPct(e.tp);
            entity.setSlPct(e.sl);
            entity.setMaxAddBuys(e.maxAddBuys);
            entity.setMinConfidence(e.minConfidence);
            entity.setStrategyLock(e.strategyLock);
            entity.setTimeStopMinutes(e.timeStop);
            entity.setRoi(e.roi);
            entity.setWinRate(e.winRate);
            entity.setTotalTrades(e.totalTrades);
            entity.setWins(e.wins);
            entity.setTotalPnl(e.totalPnl);
            entity.setFinalCapital(e.finalCapital);
            entity.setTpSellCount(e.tpSellCount);
            entity.setSlSellCount(e.slSellCount);
            entity.setPatternSellCount(e.patternSellCount);
            entity.setPhase(1);
            resultRepo.save(entity);
            count++;
        }
        log.info("Saved {} optimization results for run {}", count, runId);
    }

    // ===== Result queries =====

    public List<OptimizationResultEntity> getResults(String runId) {
        return resultRepo.findByRunIdOrderByRoiDesc(runId);
    }

    public List<OptimizationResultEntity> getResultsBySymbol(String runId, String symbol) {
        return resultRepo.findByRunIdAndSymbolOrderByRoiDesc(runId, symbol);
    }

    public List<OptimizationResultEntity> getResultsByPhase(String runId, int phase) {
        return resultRepo.findByRunIdAndPhaseOrderByRoiDesc(runId, phase);
    }

    public List<OptimizationResultEntity> getResultsByPhaseAndSymbol(String runId, int phase, String symbol) {
        return resultRepo.findByRunIdAndPhaseAndSymbolOrderByRoiDesc(runId, phase, symbol);
    }

    public List<ResultEntry> getTopResultsInMemory() {
        List<ResultEntry> list = new ArrayList<ResultEntry>();
        int count = 0;
        for (ResultEntry e : topResults) {
            if (count >= TOP_N) break;
            list.add(e);
            count++;
        }
        return list;
    }

    // ===== Result entry =====

    public static class ResultEntry {
        public long id;
        public String strategy;
        public String symbol;
        public int interval;
        public int emaPeriod;
        public double tp;
        public double sl;
        public int maxAddBuys;
        public double minConfidence;
        public boolean strategyLock;
        public int timeStop;
        public double roi;
        public double winRate;
        public int totalTrades;
        public int wins;
        public double totalPnl;
        public double finalCapital;
        public int tpSellCount;
        public int slSellCount;
        public int patternSellCount;
        public String strategiesCsv;
        public String strategyIntervalsCsv;
        public String emaFilterCsv;
        public int phase = 1;
        public Double roi3m;
        public Double winRate3m;
        public Integer totalTrades3m;
        public Integer wins3m;
        public Double roi1m;
        public Double winRate1m;
        public Integer totalTrades1m;
        public Integer wins1m;
    }
}
