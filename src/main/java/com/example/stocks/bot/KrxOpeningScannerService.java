package com.example.stocks.bot;

import com.example.stocks.db.*;
import com.example.stocks.exchange.ExchangeAdapter;
import com.example.stocks.market.CandleService;
import com.example.stocks.market.MarketType;
import com.example.stocks.market.StockCandle;
import com.example.stocks.market.TickerService;
import com.example.stocks.strategy.*;
import com.example.stocks.trade.LiveOrderService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PreDestroy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * KRX 오프닝 레인지 돌파 스캐너.
 * KST 기준: Range 09:00-09:15, Entry 09:15-10:30, Session end 15:15
 * KOSPI 지수 필터로 시장 방향 확인.
 * scanner_source = "KRX_OPENING"
 */
@Service
public class KrxOpeningScannerService {

    private static final Logger log = LoggerFactory.getLogger(KrxOpeningScannerService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String SCANNER_SOURCE = "KRX_OPENING";
    private static final String ENTRY_STRATEGY = "KRX_OPENING_BREAK";

    private final KrxOpeningConfigRepository configRepo;
    private final BotConfigRepository botConfigRepo;
    private final PositionRepository positionRepo;
    private final TradeRepository tradeLogRepo;
    private final CandleService candleService;
    private final LiveOrderService liveOrders;
    private final TickerService tickerService;
    private final StrategyFactory strategyFactory;
    private final TransactionTemplate txTemplate;
    private final ExchangeAdapter exchangeAdapter;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ScheduledExecutorService scheduler;

    // Quick TP ticker
    private volatile ScheduledExecutorService tickerExec;
    private volatile ScheduledFuture<?> tickerFuture;

    // Scanner status (dashboard polling)
    private volatile String statusText = "STOPPED";
    private volatile int scanCount = 0;
    private volatile int activePositions = 0;
    private volatile List<String> lastScannedSymbols = Collections.emptyList();
    private volatile long lastTickEpochMs = 0;

    // Parallel executor for candle fetching
    private final ExecutorService parallelExecutor = Executors.newFixedThreadPool(
            Math.min(Runtime.getRuntime().availableProcessors(), 8),
            new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger(0);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "krx-opening-parallel-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            }
    );

    // Decision Log
    private static final int MAX_DECISION_LOG = 200;
    private final Deque<ScannerDecision> decisionLog = new ArrayDeque<ScannerDecision>();

    /** Result holder for parallel candle fetching */
    private static class CandleFetchResult {
        final String symbol;
        final List<StockCandle> candles;
        final Exception error;

        CandleFetchResult(String symbol, List<StockCandle> candles, Exception error) {
            this.symbol = symbol;
            this.candles = candles;
            this.error = error;
        }
    }

    /** BUY signal holder */
    private static class BuySignal {
        final String symbol;
        final StockCandle candle;
        final Signal signal;
        final List<StockCandle> candles;

        BuySignal(String symbol, StockCandle candle, Signal signal, List<StockCandle> candles) {
            this.symbol = symbol;
            this.candle = candle;
            this.signal = signal;
            this.candles = candles;
        }
    }

    public KrxOpeningScannerService(KrxOpeningConfigRepository configRepo,
                                     BotConfigRepository botConfigRepo,
                                     PositionRepository positionRepo,
                                     TradeRepository tradeLogRepo,
                                     CandleService candleService,
                                     LiveOrderService liveOrders,
                                     TickerService tickerService,
                                     TransactionTemplate txTemplate, StrategyFactory strategyFactory,
                                     ExchangeAdapter exchangeAdapter) {
        this.configRepo = configRepo;
        this.botConfigRepo = botConfigRepo;
        this.positionRepo = positionRepo;
        this.tradeLogRepo = tradeLogRepo;
        this.candleService = candleService;
        this.liveOrders = liveOrders;
        this.tickerService = tickerService;
        this.txTemplate = txTemplate;
        this.strategyFactory = strategyFactory;
        this.exchangeAdapter = exchangeAdapter;
    }

    // ========== Decision Log ==========

    private void addDecision(String symbol, String action, String result,
                              String reasonCode, String reason) {
        ScannerDecision d = new ScannerDecision(
                System.currentTimeMillis(), symbol, action, result, reasonCode, reason);
        synchronized (decisionLog) {
            decisionLog.addFirst(d);
            while (decisionLog.size() > MAX_DECISION_LOG) decisionLog.removeLast();
        }
    }

    public List<Map<String, Object>> getRecentDecisions(int limit) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        synchronized (decisionLog) {
            int count = 0;
            for (ScannerDecision d : decisionLog) {
                if (count >= limit) break;
                list.add(d.toMap());
                count++;
            }
        }
        return list;
    }

    // ========== Start / Stop ==========

    public boolean start() {
        if (!running.compareAndSet(false, true)) {
            log.info("[KrxOpening] already running");
            return false;
        }
        log.info("[KrxOpening] starting...");
        statusText = "RUNNING";
        scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "krx-opening-scanner");
                t.setDaemon(true);
                return t;
            }
        });
        scheduleTick();
        startQuickTpTicker();
        return true;
    }

    public boolean stop() {
        if (!running.compareAndSet(true, false)) {
            log.info("[KrxOpening] already stopped");
            return false;
        }
        log.info("[KrxOpening] stopping...");
        statusText = "STOPPED";
        stopQuickTpTicker();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        return true;
    }

    @PreDestroy
    public void destroy() {
        stopQuickTpTicker();
        parallelExecutor.shutdownNow();
        log.info("[KrxOpening] parallel executor shut down");
    }

    public boolean isRunning() { return running.get(); }
    public String getStatusText() { return statusText; }
    public int getScanCount() { return scanCount; }
    public int getActivePositions() { return activePositions; }
    public List<String> getLastScannedSymbols() { return lastScannedSymbols; }
    public long getLastTickEpochMs() { return lastTickEpochMs; }

    public ScannerStatusDto getStatus() {
        ScannerStatusDto dto = new ScannerStatusDto();
        dto.setRunning(running.get());
        KrxOpeningConfigEntity cfg = configRepo.loadOrCreate();
        dto.setMode(cfg.getMode());
        dto.setStatusText(statusText);
        dto.setScanCount(scanCount);
        dto.setActivePositions(activePositions);
        dto.setScannedSymbols(lastScannedSymbols);
        dto.setLastTickEpochMs(lastTickEpochMs);
        return dto;
    }

    // ========== Scheduling ==========

    private void scheduleTick() {
        if (!running.get() || scheduler == null) return;
        KrxOpeningConfigEntity cfg = configRepo.loadOrCreate();
        int unitMin = cfg.getCandleUnitMin();
        if (unitMin <= 0) unitMin = 5;

        long nowEpochSec = Instant.now().getEpochSecond();
        long epochMin = nowEpochSec / 60;
        long nextBoundaryMin = ((epochMin / unitMin) + 1) * unitMin;
        long delaySec = (nextBoundaryMin * 60) - nowEpochSec + 2;
        if (delaySec <= 0) delaySec = 1;

        try {
            scheduler.schedule(new Runnable() {
                @Override
                public void run() { tickWrapper(); }
            }, delaySec, TimeUnit.SECONDS);
            log.debug("[KrxOpening] next tick in {}s", delaySec);
        } catch (Exception e) {
            log.error("[KrxOpening] schedule failed", e);
        }
    }

    private void tickWrapper() {
        try {
            tick();
        } catch (Exception e) {
            log.error("[KrxOpening] tick error", e);
        } finally {
            scheduleTick();
        }
    }

    // ========== Main Tick ==========

    private void tick() {
        if (!running.get()) return;

        KrxOpeningConfigEntity cfg = configRepo.loadOrCreate();
        if (!cfg.isEnabled()) {
            statusText = "DISABLED";
            return;
        }

        // Check KRX trading day (weekday, not holiday)
        ZonedDateTime nowKst = ZonedDateTime.now(KST);
        if (!MarketCalendar.isMarketOpen(nowKst, MarketType.KRX)
                && !isWithinScannerHours(nowKst, cfg)) {
            statusText = "IDLE (market closed)";
            return;
        }

        int nowMinOfDay = nowKst.getHour() * 60 + nowKst.getMinute();
        int rangeStart = cfg.getRangeStartHour() * 60 + cfg.getRangeStartMin();
        int sessionEnd = cfg.getSessionEndHour() * 60 + cfg.getSessionEndMin();

        if (nowMinOfDay < rangeStart || nowMinOfDay > sessionEnd + 30) {
            statusText = "IDLE (outside hours)";
            return;
        }

        statusText = "SCANNING";
        lastTickEpochMs = System.currentTimeMillis();

        String mode = cfg.getMode();
        boolean isLive = "LIVE".equalsIgnoreCase(mode);

        if (isLive && !liveOrders.isConfigured()) {
            statusText = "ERROR (API key)";
            addDecision("*", "TICK", "BLOCKED", "API_KEY_MISSING",
                    "LIVE mode but exchange API not configured");
            return;
        }

        int candleUnit = cfg.getCandleUnitMin();

        // Check entry window
        int entryStart = cfg.getEntryStartHour() * 60 + cfg.getEntryStartMin();
        int entryEnd = cfg.getEntryEndHour() * 60 + cfg.getEntryEndMin();
        boolean inEntryWindow = nowMinOfDay >= entryStart && nowMinOfDay <= entryEnd;

        // Owned positions
        Set<String> ownedSymbols = new HashSet<String>();
        List<PositionEntity> allPositions = positionRepo.findAll();
        int scannerPosCount = 0;
        for (PositionEntity pe : allPositions) {
            if (pe.getQty() > 0) {
                if (ENTRY_STRATEGY.equals(pe.getEntryStrategy())) {
                    scannerPosCount++;
                } else {
                    ownedSymbols.add(pe.getSymbol());
                }
            }
        }
        activePositions = scannerPosCount;

        // Excluded symbols
        ownedSymbols.addAll(cfg.getExcludeSymbolsSet());

        List<String> topSymbols = getTopSymbolsByVolume(cfg.getTopN(), ownedSymbols, MarketType.KRX);
        lastScannedSymbols = topSymbols;
        scanCount = topSymbols.size();

        // KOSPI index filter
        boolean indexAllowLong = true;
        if (cfg.isKospiFilterEnabled()) {
            indexAllowLong = checkIndexFilter("KOSPI", candleUnit, cfg.getKospiEmaPeriod());
            if (!indexAllowLong) {
                addDecision("*", "BUY", "BLOCKED", "INDEX_FILTER",
                        "KOSPI below EMA" + cfg.getKospiEmaPeriod() + ", all entries blocked");
            }
        }

        // ========== Phase 1: SELL (exit checks) ==========
        List<PositionEntity> scannerPositions = new ArrayList<PositionEntity>();
        for (PositionEntity pe : allPositions) {
            if (!ENTRY_STRATEGY.equals(pe.getEntryStrategy())) continue;
            if (pe.getQty() <= 0) continue;
            scannerPositions.add(pe);
        }

        if (!scannerPositions.isEmpty()) {
            final int sellCandleUnit = candleUnit;
            Map<String, Future<CandleFetchResult>> sellFutures = new LinkedHashMap<String, Future<CandleFetchResult>>();
            for (final PositionEntity pe : scannerPositions) {
                sellFutures.put(pe.getSymbol(), parallelExecutor.submit(new Callable<CandleFetchResult>() {
                    @Override
                    public CandleFetchResult call() {
                        try {
                            List<StockCandle> candles = candleService.getMinuteCandles(
                                    pe.getSymbol(), MarketType.KRX, sellCandleUnit, 40, null);
                            return new CandleFetchResult(pe.getSymbol(), candles, null);
                        } catch (Exception e) {
                            return new CandleFetchResult(pe.getSymbol(), null, e);
                        }
                    }
                }));
            }

            for (PositionEntity pe : scannerPositions) {
                try {
                    CandleFetchResult result = sellFutures.get(pe.getSymbol()).get(30, TimeUnit.SECONDS);
                    if (result.error != null) {
                        addDecision(pe.getSymbol(), "SELL", "ERROR", "EXIT_CHECK_FAIL",
                                "Exit candle fetch error: " + result.error.getMessage());
                        continue;
                    }
                    List<StockCandle> candles = result.candles;
                    if (candles == null || candles.isEmpty()) continue;

                    // Session end check
                    if (nowMinOfDay >= sessionEnd) {
                        executeSell(pe, candles.get(candles.size() - 1),
                                Signal.of(SignalAction.SELL, null, "SESSION_END: KRX session closing"), cfg);
                        addDecision(pe.getSymbol(), "SELL", "EXECUTED", "SESSION_END", "Session end forced exit");
                        continue;
                    }

                    StrategyContext ctx = new StrategyContext(pe.getSymbol(), candleUnit, candles, pe, 0);
                    // Use MultiConfirmMomentum for exit evaluation
                    TradingStrategy strategy = strategyFactory.get(StrategyType.BOLLINGER_SQUEEZE_BREAKOUT);
                    Signal signal = strategy.evaluate(ctx);

                    if (signal.action == SignalAction.SELL) {
                        executeSell(pe, candles.get(candles.size() - 1), signal, cfg);
                        addDecision(pe.getSymbol(), "SELL", "EXECUTED", "SIGNAL", signal.reason);
                    }
                } catch (TimeoutException e) {
                    addDecision(pe.getSymbol(), "SELL", "ERROR", "EXIT_CHECK_FAIL", "Candle fetch timeout (30s)");
                } catch (Exception e) {
                    addDecision(pe.getSymbol(), "SELL", "ERROR", "EXIT_CHECK_FAIL", "Exit check error: " + e.getMessage());
                }
            }
        }

        // ========== Phase 2: BUY signal detection ==========
        boolean canEnter = indexAllowLong && scannerPosCount < cfg.getMaxPositions() && inEntryWindow;

        if (!canEnter && !indexAllowLong) {
            // already logged
        } else if (!canEnter && !inEntryWindow) {
            // outside entry window
        } else if (!canEnter) {
            addDecision("*", "BUY", "BLOCKED", "MAX_POSITIONS",
                    String.format("Max positions (%d) reached", cfg.getMaxPositions()));
        }

        BigDecimal orderAmount = calcOrderSize(cfg);

        // Global Capital check
        if (canEnter) {
            BigDecimal globalCap = getGlobalCapital();
            double totalInvested = calcTotalInvestedAllPositions();
            double remainingBudget = Math.max(0, globalCap.doubleValue() - totalInvested);

            if (orderAmount.doubleValue() > remainingBudget) {
                if (remainingBudget >= 50000) {
                    orderAmount = BigDecimal.valueOf(remainingBudget).setScale(0, RoundingMode.DOWN);
                } else {
                    addDecision("*", "BUY", "BLOCKED", "CAPITAL_LIMIT",
                            String.format("Global capital limit exceeded: invested %.0f / limit %s",
                                    totalInvested, globalCap.toPlainString()));
                    canEnter = false;
                }
            }
        }

        if (canEnter) {
            List<String> entryCandidates = new ArrayList<String>();
            for (String symbol : topSymbols) {
                boolean alreadyHas = false;
                for (PositionEntity pe : allPositions) {
                    if (symbol.equals(pe.getSymbol()) && pe.getQty() > 0) {
                        alreadyHas = true;
                        break;
                    }
                }
                if (!alreadyHas) {
                    entryCandidates.add(symbol);
                }
            }

            final int buyCandleUnit = candleUnit;
            Map<String, Future<CandleFetchResult>> buyFutures = new LinkedHashMap<String, Future<CandleFetchResult>>();
            for (final String symbol : entryCandidates) {
                buyFutures.put(symbol, parallelExecutor.submit(new Callable<CandleFetchResult>() {
                    @Override
                    public CandleFetchResult call() {
                        try {
                            List<StockCandle> candles = candleService.getMinuteCandles(
                                    symbol, MarketType.KRX, buyCandleUnit, 40, null);
                            return new CandleFetchResult(symbol, candles, null);
                        } catch (Exception e) {
                            return new CandleFetchResult(symbol, null, e);
                        }
                    }
                }));
            }

            List<BuySignal> buySignals = new ArrayList<BuySignal>();
            int entryAttempts = 0;

            for (String symbol : entryCandidates) {
                try {
                    CandleFetchResult result = buyFutures.get(symbol).get(30, TimeUnit.SECONDS);
                    if (result.error != null) {
                        addDecision(symbol, "BUY", "ERROR", "ENTRY_CHECK_FAIL",
                                "Entry candle fetch error: " + result.error.getMessage());
                        continue;
                    }
                    List<StockCandle> candles = result.candles;
                    if (candles == null || candles.isEmpty()) continue;

                    StrategyContext ctx = new StrategyContext(symbol, candleUnit, candles, null, 0);
                    TradingStrategy strategy = strategyFactory.get(StrategyType.BOLLINGER_SQUEEZE_BREAKOUT);
                    Signal signal = strategy.evaluate(ctx);

                    entryAttempts++;
                    if (signal.action == SignalAction.BUY) {
                        buySignals.add(new BuySignal(symbol, candles.get(candles.size() - 1), signal, candles));
                    } else {
                        addDecision(symbol, "BUY", "SKIPPED", "NO_SIGNAL",
                                signal.reason != null ? signal.reason : "UNKNOWN");
                    }
                } catch (TimeoutException e) {
                    addDecision(symbol, "BUY", "ERROR", "ENTRY_CHECK_FAIL", "Candle fetch timeout (30s)");
                } catch (Exception e) {
                    addDecision(symbol, "BUY", "ERROR", "ENTRY_CHECK_FAIL", "Entry check error: " + e.getMessage());
                }
            }

            // ========== Phase 3: BUY execution ==========
            Collections.sort(buySignals, new Comparator<BuySignal>() {
                @Override
                public int compare(BuySignal a, BuySignal b) {
                    return Double.compare(b.signal.confidence, a.signal.confidence);
                }
            });

            int entrySuccess = 0;
            double spentAmount = 0;

            for (BuySignal bs : buySignals) {
                if (scannerPosCount >= cfg.getMaxPositions()) {
                    addDecision(bs.symbol, "BUY", "BLOCKED", "MAX_POSITIONS",
                            String.format("Max positions (%d) reached", cfg.getMaxPositions()));
                    break;
                }

                try {
                    executeBuy(bs.symbol, bs.candle, bs.signal, cfg);
                    spentAmount += orderAmount.doubleValue();
                    scannerPosCount++;
                    entrySuccess++;
                    addDecision(bs.symbol, "BUY", "EXECUTED", "SIGNAL", bs.signal.reason);
                } catch (Exception e) {
                    log.error("[KrxOpening] buy execution failed for {}", bs.symbol, e);
                    addDecision(bs.symbol, "BUY", "ERROR", "EXECUTION_FAIL",
                            "Buy execution error: " + e.getMessage());
                }
            }

            log.info("[KrxOpening] tick done mode={} symbols={} attempts={} signals={} entries={} positions={}",
                    mode, topSymbols.size(), entryAttempts, buySignals.size(), entrySuccess, scannerPosCount);
        }

        activePositions = scannerPosCount;
        statusText = "SCANNING";
    }

    // ========== Order Execution ==========

    private void executeBuy(String symbol, StockCandle candle, Signal signal,
                             KrxOpeningConfigEntity cfg) {
        double price = candle.trade_price;
        BigDecimal orderAmount = calcOrderSize(cfg);
        if (orderAmount.compareTo(BigDecimal.valueOf(50000)) < 0) {
            addDecision(symbol, "BUY", "BLOCKED", "ORDER_TOO_SMALL",
                    String.format("Order amount %s below minimum 50,000", orderAmount.toPlainString()));
            return;
        }

        boolean isPaper = "PAPER".equalsIgnoreCase(cfg.getMode());
        int qty;
        double fillPrice;

        if (isPaper) {
            fillPrice = price * 1.001; // 0.1% slippage
            double fee = orderAmount.doubleValue() * 0.00015; // stock fee ~0.015%
            qty = (int) ((orderAmount.doubleValue() - fee) / fillPrice);
            if (qty <= 0) {
                addDecision(symbol, "BUY", "BLOCKED", "QTY_ZERO", "Calculated qty is 0");
                return;
            }
        } else {
            if (!liveOrders.isConfigured()) {
                addDecision(symbol, "BUY", "BLOCKED", "API_KEY_MISSING", "LIVE mode API not configured");
                return;
            }
            int estQty = (int) (orderAmount.doubleValue() / price);
            if (estQty <= 0) {
                addDecision(symbol, "BUY", "BLOCKED", "QTY_ZERO", "Estimated qty is 0");
                return;
            }
            try {
                LiveOrderService.LiveOrderResult r = liveOrders.placeBuyOrder(symbol, MarketType.KRX, estQty, price);
                if (!r.isFilled()) {
                    addDecision(symbol, "BUY", "ERROR", "ORDER_NOT_FILLED",
                            String.format("Order not filled state=%s qty=%d", r.state, r.executedQty));
                    return;
                }
                fillPrice = r.avgPrice > 0 ? r.avgPrice : price;
                qty = r.executedQty > 0 ? r.executedQty : estQty;
            } catch (Exception e) {
                addDecision(symbol, "BUY", "ERROR", "ORDER_EXCEPTION", "Order failed: " + e.getMessage());
                return;
            }
        }

        final double fFillPrice = fillPrice;
        final int fQty = qty;
        txTemplate.execute(new org.springframework.transaction.support.TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(org.springframework.transaction.TransactionStatus status) {
                PositionEntity pe = new PositionEntity();
                pe.setSymbol(symbol);
                pe.setMarketType("KRX");
                pe.setQty(fQty);
                pe.setAvgPrice(fFillPrice);
                pe.setAddBuys(0);
                pe.setOpenedAt(Instant.now());
                pe.setEntryStrategy(ENTRY_STRATEGY);
                positionRepo.save(pe);

                TradeEntity tl = new TradeEntity();
                tl.setTsEpochMs(System.currentTimeMillis());
                tl.setSymbol(symbol);
                tl.setMarketType("KRX");
                tl.setAction("BUY");
                tl.setPrice(fFillPrice);
                tl.setQty(fQty);
                tl.setPnlKrw(0);
                tl.setRoiPercent(0);
                tl.setMode(cfg.getMode());
                tl.setPatternType(ENTRY_STRATEGY);
                tl.setPatternReason(signal.reason);
                tl.setConfidence(signal.confidence);
                tl.setCandleUnitMin(cfg.getCandleUnitMin());
                tl.setCurrency("KRW");
                tradeLogRepo.save(tl);
            }
        });

        log.info("[KrxOpening] BUY {} mode={} price={} qty={} conf={} reason={}",
                symbol, cfg.getMode(), fillPrice, qty, signal.confidence, signal.reason);
    }

    private void executeSell(PositionEntity pe, StockCandle candle, Signal signal,
                              KrxOpeningConfigEntity cfg) {
        double price = candle.trade_price;
        boolean isPaper = "PAPER".equalsIgnoreCase(cfg.getMode());
        double fillPrice;
        int qty = pe.getQty();

        if (isPaper) {
            fillPrice = price * 0.999;
        } else {
            if (!liveOrders.isConfigured()) {
                addDecision(pe.getSymbol(), "SELL", "BLOCKED", "API_KEY_MISSING", "LIVE mode API not configured");
                return;
            }
            try {
                LiveOrderService.LiveOrderResult r = liveOrders.placeSellOrder(pe.getSymbol(), MarketType.KRX, qty, price);
                if (!r.isFilled()) {
                    addDecision(pe.getSymbol(), "SELL", "ERROR", "ORDER_NOT_FILLED",
                            String.format("Sell not filled state=%s qty=%d", r.state, r.executedQty));
                    return;
                }
                fillPrice = r.avgPrice > 0 ? r.avgPrice : price;
            } catch (Exception e) {
                addDecision(pe.getSymbol(), "SELL", "ERROR", "ORDER_EXCEPTION", "Sell failed: " + e.getMessage());
                return;
            }
        }

        double avgPrice = pe.getAvgPrice().doubleValue();
        double pnlKrw = (fillPrice - avgPrice) * qty;
        double fee = fillPrice * qty * 0.00015;
        pnlKrw -= fee;
        double roiPct = avgPrice > 0 ? ((fillPrice - avgPrice) / avgPrice) * 100.0 : 0;

        final double fFillPrice = fillPrice;
        final int fQty = qty;
        final double fPnlKrw = pnlKrw;
        final double fRoiPct = roiPct;
        final String peSymbol = pe.getSymbol();
        txTemplate.execute(new org.springframework.transaction.support.TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(org.springframework.transaction.TransactionStatus status) {
                TradeEntity tl = new TradeEntity();
                tl.setTsEpochMs(System.currentTimeMillis());
                tl.setSymbol(peSymbol);
                tl.setMarketType("KRX");
                tl.setAction("SELL");
                tl.setPrice(fFillPrice);
                tl.setQty(fQty);
                tl.setPnlKrw(fPnlKrw);
                tl.setRoiPercent(fRoiPct);
                tl.setMode(cfg.getMode());
                tl.setPatternType(ENTRY_STRATEGY);
                tl.setPatternReason(signal.reason);
                tl.setAvgBuyPrice(pe.getAvgPrice().doubleValue());
                tl.setConfidence(signal.confidence);
                tl.setCandleUnitMin(cfg.getCandleUnitMin());
                tl.setCurrency("KRW");
                tradeLogRepo.save(tl);

                positionRepo.deleteById(peSymbol);
            }
        });

        log.info("[KrxOpening] SELL {} price={} pnl={} roi={}% reason={}",
                pe.getSymbol(), fillPrice, String.format("%.0f", pnlKrw),
                String.format("%.2f", roiPct), signal.reason);
    }

    // ========== Quick TP Ticker ==========

    private void startQuickTpTicker() {
        KrxOpeningConfigEntity cfg = configRepo.loadOrCreate();
        if (!cfg.isQuickTpEnabled()) return;
        int intervalSec = Math.max(3, cfg.getQuickTpIntervalSec());
        tickerExec = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "krx-opening-quick-tp");
                t.setDaemon(true);
                return t;
            }
        });
        tickerFuture = tickerExec.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try { tickQuickTp(); } catch (Exception e) { log.error("[KrxOpening] Quick TP error", e); }
            }
        }, intervalSec, intervalSec, TimeUnit.SECONDS);
        log.info("[KrxOpening] Quick TP ticker started -- {}s interval, target {}%",
                intervalSec, cfg.getQuickTpPct());
    }

    private void stopQuickTpTicker() {
        if (tickerFuture != null) { tickerFuture.cancel(false); tickerFuture = null; }
        if (tickerExec != null) { tickerExec.shutdownNow(); tickerExec = null; }
    }

    private void tickQuickTp() {
        if (!running.get()) return;
        KrxOpeningConfigEntity cfg = configRepo.loadOrCreate();
        if (!cfg.isQuickTpEnabled()) return;

        List<PositionEntity> allPos = positionRepo.findAll();
        for (PositionEntity pe : allPos) {
            if (!ENTRY_STRATEGY.equals(pe.getEntryStrategy()) || pe.getQty() <= 0) continue;

            try {
                StockCandle ticker = tickerService.getCurrentPrice(pe.getSymbol(), MarketType.KRX);
                if (ticker == null || ticker.trade_price <= 0) continue;

                double avgPrice = pe.getAvgPrice().doubleValue();
                if (avgPrice <= 0) continue;

                double pnlPct = (ticker.trade_price - avgPrice) / avgPrice * 100.0;
                if (pnlPct >= cfg.getQuickTpPct()) {
                    String reason = String.format(Locale.ROOT,
                            "QUICK_TP pnl=%.2f%% >= target=%.2f%% price=%.2f avg=%.2f",
                            pnlPct, cfg.getQuickTpPct(), ticker.trade_price, avgPrice);
                    log.info("[KrxOpening] QUICK_TP triggered | {} | {}", pe.getSymbol(), reason);

                    PositionEntity fresh = positionRepo.findById(pe.getSymbol()).orElse(null);
                    if (fresh == null || fresh.getQty() <= 0) continue;

                    executeSell(fresh, ticker,
                            Signal.of(SignalAction.SELL, null, reason), cfg);
                    addDecision(pe.getSymbol(), "SELL", "EXECUTED", "QUICK_TP", reason);
                }
            } catch (Exception e) {
                log.error("[KrxOpening] Quick TP check failed for {}", pe.getSymbol(), e);
            }
        }
    }

    // ========== Helpers ==========

    private boolean isWithinScannerHours(ZonedDateTime nowKst, KrxOpeningConfigEntity cfg) {
        DayOfWeek dow = nowKst.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        if (MarketCalendar.isHoliday(nowKst.toLocalDate(), MarketType.KRX)) return false;

        int nowMin = nowKst.getHour() * 60 + nowKst.getMinute();
        int rangeStart = cfg.getRangeStartHour() * 60 + cfg.getRangeStartMin();
        int sessionEnd = cfg.getSessionEndHour() * 60 + cfg.getSessionEndMin();
        return nowMin >= rangeStart && nowMin <= sessionEnd + 30;
    }

    private BigDecimal calcOrderSize(KrxOpeningConfigEntity cfg) {
        if ("FIXED".equalsIgnoreCase(cfg.getOrderSizingMode())) {
            return cfg.getOrderSizingValue();
        }
        BigDecimal pct = cfg.getOrderSizingValue();
        BigDecimal globalCapital = getGlobalCapital();
        return globalCapital.multiply(pct).divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN);
    }

    private BigDecimal getGlobalCapital() {
        List<BotConfigEntity> configs = botConfigRepo.findAll();
        if (configs.isEmpty()) return BigDecimal.valueOf(500000);
        BigDecimal cap = configs.get(0).getCapitalKrw();
        return cap != null && cap.compareTo(BigDecimal.ZERO) > 0 ? cap : BigDecimal.valueOf(500000);
    }

    private double calcTotalInvestedAllPositions() {
        double sum = 0.0;
        for (PositionEntity pe : positionRepo.findAll()) {
            if (pe.getQty() > 0 && pe.getAvgPrice() != null) {
                sum += pe.getQty() * pe.getAvgPrice().doubleValue();
            }
        }
        return sum;
    }

    private boolean checkIndexFilter(String indexSymbol, int candleUnit, int emaPeriod) {
        try {
            List<StockCandle> candles = candleService.getMinuteCandles(
                    indexSymbol, MarketType.KRX, candleUnit, emaPeriod + 10, null);
            if (candles == null || candles.size() < emaPeriod) return true;
            double ema = Indicators.ema(candles, emaPeriod);
            double close = candles.get(candles.size() - 1).trade_price;
            boolean allow = close >= ema;
            if (!allow) {
                log.info("[KrxOpening] Index filter BLOCKED: {} close={} < EMA({})={}", indexSymbol, close, emaPeriod, ema);
            }
            return allow;
        } catch (Exception e) {
            log.error("[KrxOpening] Index filter check failed", e);
            return true;
        }
    }

    private List<String> getTopSymbolsByVolume(int topN, Set<String> excludeSymbols, MarketType marketType) {
        try {
            // 제외 종목을 고려하여 여유있게 조회 (100개)
            List<String> all = exchangeAdapter.getTopSymbolsByVolume(
                    Math.max(topN + excludeSymbols.size() + 10, 100), marketType);
            List<String> filtered = new ArrayList<String>();
            for (String symbol : all) {
                if (excludeSymbols.contains(symbol)) continue;
                filtered.add(symbol);
                if (filtered.size() >= topN) break;
            }
            log.info("[KrxOpening] Volume ranking: {} symbols selected (excluded {})",
                    filtered.size(), excludeSymbols.size());
            return filtered;
        } catch (Exception e) {
            log.error("[KrxOpening] Failed to fetch volume ranking", e);
            return Collections.emptyList();
        }
    }
}
