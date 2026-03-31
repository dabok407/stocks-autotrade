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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * KRX 종일 고확신 돌파 스캐너.
 * KST 기준: Entry 10:35-14:00, Session end 15:15
 * KOSPI 필터, 고확신 돌파, Quick TP.
 * scanner_source = "KRX_ALLDAY"
 */
@Service
public class KrxAlldayScannerService {

    private static final Logger log = LoggerFactory.getLogger(KrxAlldayScannerService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String SCANNER_SOURCE = "KRX_ALLDAY";
    private static final String ENTRY_STRATEGY = "KRX_HIGH_CONFIDENCE";

    private final KrxAlldayConfigRepository configRepo;
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

    // Scanner status
    private volatile String statusText = "STOPPED";
    private volatile int scanCount = 0;
    private volatile int activePositions = 0;
    private volatile List<String> lastScannedSymbols = Collections.emptyList();
    private volatile long lastTickEpochMs = 0;

    private final ExecutorService parallelExecutor = Executors.newFixedThreadPool(
            Math.min(Runtime.getRuntime().availableProcessors(), 8),
            new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger(0);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "krx-allday-parallel-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            }
    );

    private static final int MAX_DECISION_LOG = 200;
    private final Deque<ScannerDecision> decisionLog = new ArrayDeque<ScannerDecision>();

    private static class CandleFetchResult {
        final String symbol;
        final List<StockCandle> candles;
        final Exception error;
        CandleFetchResult(String symbol, List<StockCandle> candles, Exception error) {
            this.symbol = symbol; this.candles = candles; this.error = error;
        }
    }

    private static class BuySignal {
        final String symbol;
        final StockCandle candle;
        final Signal signal;
        final List<StockCandle> candles;
        BuySignal(String symbol, StockCandle candle, Signal signal, List<StockCandle> candles) {
            this.symbol = symbol; this.candle = candle; this.signal = signal; this.candles = candles;
        }
    }

    public KrxAlldayScannerService(KrxAlldayConfigRepository configRepo,
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
            log.info("[KrxAllday] already running");
            return false;
        }
        log.info("[KrxAllday] starting...");
        statusText = "RUNNING";
        scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "krx-allday-scanner");
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
            log.info("[KrxAllday] already stopped");
            return false;
        }
        log.info("[KrxAllday] stopping...");
        statusText = "STOPPED";
        stopQuickTpTicker();
        if (scheduler != null) { scheduler.shutdownNow(); scheduler = null; }
        return true;
    }

    @PreDestroy
    public void destroy() {
        stopQuickTpTicker();
        parallelExecutor.shutdownNow();
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
        KrxAlldayConfigEntity cfg = configRepo.loadOrCreate();
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
        KrxAlldayConfigEntity cfg = configRepo.loadOrCreate();
        int unitMin = cfg.getCandleUnitMin();
        if (unitMin <= 0) unitMin = 5;

        long nowEpochSec = Instant.now().getEpochSecond();
        long epochMin = nowEpochSec / 60;
        long nextBoundaryMin = ((epochMin / unitMin) + 1) * unitMin;
        long delaySec = (nextBoundaryMin * 60) - nowEpochSec + 2;
        if (delaySec <= 0) delaySec = 1;

        try {
            scheduler.schedule(new Runnable() {
                @Override public void run() { tickWrapper(); }
            }, delaySec, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("[KrxAllday] schedule failed", e);
        }
    }

    private void tickWrapper() {
        try { tick(); } catch (Exception e) { log.error("[KrxAllday] tick error", e); }
        finally { scheduleTick(); }
    }

    // ========== Main Tick ==========

    private void tick() {
        if (!running.get()) return;

        KrxAlldayConfigEntity cfg = configRepo.loadOrCreate();
        if (!cfg.isEnabled()) { statusText = "DISABLED"; return; }

        ZonedDateTime nowKst = ZonedDateTime.now(KST);
        int nowMinOfDay = nowKst.getHour() * 60 + nowKst.getMinute();
        int entryStart = cfg.getEntryStartHour() * 60 + cfg.getEntryStartMin();
        int entryEnd = cfg.getEntryEndHour() * 60 + cfg.getEntryEndMin();
        int sessionEnd = cfg.getSessionEndHour() * 60 + cfg.getSessionEndMin();

        boolean inEntryWindow = nowMinOfDay >= entryStart && nowMinOfDay <= entryEnd;
        boolean inSession = nowMinOfDay >= entryStart && nowMinOfDay <= sessionEnd + 30;

        if (!inSession) { statusText = "IDLE (outside hours)"; return; }

        // Check trading day
        DayOfWeek dow = nowKst.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) { statusText = "IDLE (weekend)"; return; }
        if (MarketCalendar.isHoliday(nowKst.toLocalDate(), MarketType.KRX)) { statusText = "IDLE (holiday)"; return; }

        statusText = "SCANNING";
        lastTickEpochMs = System.currentTimeMillis();

        String mode = cfg.getMode();
        boolean isLive = "LIVE".equalsIgnoreCase(mode);

        if (isLive && !liveOrders.isConfigured()) {
            statusText = "ERROR (API key)";
            addDecision("*", "TICK", "BLOCKED", "API_KEY_MISSING", "LIVE mode but exchange API not configured");
            return;
        }

        int candleUnit = cfg.getCandleUnitMin();
        double cfgMinConfidence = cfg.getMinConfidence().doubleValue();

        // Owned positions
        Set<String> ownedSymbols = new HashSet<String>();
        List<PositionEntity> allPositions = positionRepo.findAll();
        int scannerPosCount = 0;
        for (PositionEntity pe : allPositions) {
            if (pe.getQty() > 0) {
                ownedSymbols.add(pe.getSymbol());
                if (ENTRY_STRATEGY.equals(pe.getEntryStrategy())) scannerPosCount++;
            }
        }
        activePositions = scannerPosCount;

        ownedSymbols.addAll(cfg.getExcludeSymbolsSet());

        List<String> topSymbols = getTopSymbolsByVolume(cfg.getTopN(), ownedSymbols, MarketType.KRX);
        lastScannedSymbols = topSymbols;
        scanCount = topSymbols.size();

        // KOSPI filter
        boolean indexAllowLong = true;
        if (cfg.isKospiFilterEnabled()) {
            indexAllowLong = checkIndexFilter("KOSPI", candleUnit, cfg.getKospiEmaPeriod());
            if (!indexAllowLong) {
                addDecision("*", "BUY", "BLOCKED", "INDEX_FILTER",
                        "KOSPI below EMA" + cfg.getKospiEmaPeriod());
            }
        }

        // ========== Phase 1: SELL ==========
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
                    @Override public CandleFetchResult call() {
                        try {
                            List<StockCandle> candles = candleService.getMinuteCandles(
                                    pe.getSymbol(), MarketType.KRX, sellCandleUnit, 80, null);
                            return new CandleFetchResult(pe.getSymbol(), candles, null);
                        } catch (Exception e) { return new CandleFetchResult(pe.getSymbol(), null, e); }
                    }
                }));
            }

            for (PositionEntity pe : scannerPositions) {
                try {
                    CandleFetchResult result = sellFutures.get(pe.getSymbol()).get(30, TimeUnit.SECONDS);
                    if (result.error != null) {
                        addDecision(pe.getSymbol(), "SELL", "ERROR", "EXIT_CHECK_FAIL", result.error.getMessage());
                        continue;
                    }
                    List<StockCandle> candles = result.candles;
                    if (candles == null || candles.isEmpty()) continue;

                    if (nowMinOfDay >= sessionEnd) {
                        executeSell(pe, candles.get(candles.size() - 1),
                                Signal.of(SignalAction.SELL, null, "SESSION_END: KRX allday session closing"), cfg);
                        addDecision(pe.getSymbol(), "SELL", "EXECUTED", "SESSION_END", "Session end forced exit");
                        continue;
                    }

                    StrategyContext ctx = new StrategyContext(pe.getSymbol(), candleUnit, candles, pe, 0);
                    TradingStrategy strategy = strategyFactory.get(StrategyType.MULTI_CONFIRM_MOMENTUM);
                    Signal signal = strategy.evaluate(ctx);
                    if (signal.action == SignalAction.SELL) {
                        executeSell(pe, candles.get(candles.size() - 1), signal, cfg);
                        addDecision(pe.getSymbol(), "SELL", "EXECUTED", "SIGNAL", signal.reason);
                    }
                } catch (TimeoutException e) {
                    addDecision(pe.getSymbol(), "SELL", "ERROR", "EXIT_CHECK_FAIL", "Timeout 30s");
                } catch (Exception e) {
                    addDecision(pe.getSymbol(), "SELL", "ERROR", "EXIT_CHECK_FAIL", e.getMessage());
                }
            }
        }

        // ========== Phase 2: BUY ==========
        boolean canEnter = indexAllowLong && scannerPosCount < cfg.getMaxPositions() && inEntryWindow;

        if (!canEnter && scannerPosCount >= cfg.getMaxPositions()) {
            addDecision("*", "BUY", "BLOCKED", "MAX_POSITIONS",
                    String.format("Max positions (%d) reached", cfg.getMaxPositions()));
        }

        BigDecimal orderAmount = calcOrderSize(cfg);

        if (canEnter) {
            BigDecimal globalCap = getGlobalCapital();
            double totalInvested = calcTotalInvestedAllPositions();
            double remainingBudget = Math.max(0, globalCap.doubleValue() - totalInvested);
            if (orderAmount.doubleValue() > remainingBudget) {
                if (remainingBudget >= 50000) {
                    orderAmount = BigDecimal.valueOf(remainingBudget).setScale(0, RoundingMode.DOWN);
                } else {
                    addDecision("*", "BUY", "BLOCKED", "CAPITAL_LIMIT", "Global capital limit exceeded");
                    canEnter = false;
                }
            }
        }

        if (canEnter) {
            List<String> entryCandidates = new ArrayList<String>();
            for (String symbol : topSymbols) {
                boolean alreadyHas = false;
                for (PositionEntity pe : allPositions) {
                    if (symbol.equals(pe.getSymbol()) && pe.getQty() > 0) { alreadyHas = true; break; }
                }
                if (!alreadyHas) entryCandidates.add(symbol);
            }

            final int buyCandleUnit = candleUnit;
            Map<String, Future<CandleFetchResult>> buyFutures = new LinkedHashMap<String, Future<CandleFetchResult>>();
            for (final String symbol : entryCandidates) {
                buyFutures.put(symbol, parallelExecutor.submit(new Callable<CandleFetchResult>() {
                    @Override public CandleFetchResult call() {
                        try {
                            List<StockCandle> candles = candleService.getMinuteCandles(
                                    symbol, MarketType.KRX, buyCandleUnit, 80, null);
                            return new CandleFetchResult(symbol, candles, null);
                        } catch (Exception e) { return new CandleFetchResult(symbol, null, e); }
                    }
                }));
            }

            List<BuySignal> buySignals = new ArrayList<BuySignal>();
            int entryAttempts = 0;

            for (String symbol : entryCandidates) {
                try {
                    CandleFetchResult result = buyFutures.get(symbol).get(30, TimeUnit.SECONDS);
                    if (result.error != null) {
                        addDecision(symbol, "BUY", "ERROR", "ENTRY_CHECK_FAIL", result.error.getMessage());
                        continue;
                    }
                    List<StockCandle> candles = result.candles;
                    if (candles == null || candles.isEmpty()) continue;

                    StrategyContext ctx = new StrategyContext(symbol, candleUnit, candles, null, 0);
                    TradingStrategy strategy = strategyFactory.get(StrategyType.MULTI_CONFIRM_MOMENTUM);
                    Signal signal = strategy.evaluate(ctx);

                    entryAttempts++;
                    if (signal.action == SignalAction.BUY) {
                        if (signal.confidence < cfgMinConfidence) {
                            addDecision(symbol, "BUY", "BLOCKED", "LOW_CONFIDENCE",
                                    "score " + signal.confidence + " < min " + cfgMinConfidence);
                            continue;
                        }
                        buySignals.add(new BuySignal(symbol, candles.get(candles.size() - 1), signal, candles));
                    } else {
                        addDecision(symbol, "BUY", "SKIPPED", "NO_SIGNAL",
                                signal.reason != null ? signal.reason : "UNKNOWN");
                    }
                } catch (TimeoutException e) {
                    addDecision(symbol, "BUY", "ERROR", "ENTRY_CHECK_FAIL", "Timeout 30s");
                } catch (Exception e) {
                    addDecision(symbol, "BUY", "ERROR", "ENTRY_CHECK_FAIL", e.getMessage());
                }
            }

            // ========== Phase 3: Execute ==========
            Collections.sort(buySignals, new Comparator<BuySignal>() {
                @Override public int compare(BuySignal a, BuySignal b) {
                    return Double.compare(b.signal.confidence, a.signal.confidence);
                }
            });

            int entrySuccess = 0;
            for (BuySignal bs : buySignals) {
                if (scannerPosCount >= cfg.getMaxPositions()) break;
                try {
                    executeBuy(bs.symbol, bs.candle, bs.signal, cfg);
                    scannerPosCount++;
                    entrySuccess++;
                    addDecision(bs.symbol, "BUY", "EXECUTED", "SIGNAL", bs.signal.reason);
                } catch (Exception e) {
                    addDecision(bs.symbol, "BUY", "ERROR", "EXECUTION_FAIL", e.getMessage());
                }
            }

            log.info("[KrxAllday] tick done mode={} symbols={} attempts={} signals={} entries={} positions={}",
                    mode, topSymbols.size(), entryAttempts, buySignals.size(), entrySuccess, scannerPosCount);
        }

        activePositions = scannerPosCount;
        statusText = "SCANNING";
    }

    // ========== Order Execution ==========

    private void executeBuy(String symbol, StockCandle candle, Signal signal,
                             KrxAlldayConfigEntity cfg) {
        double price = candle.trade_price;
        BigDecimal orderAmount = calcOrderSize(cfg);
        if (orderAmount.compareTo(BigDecimal.valueOf(50000)) < 0) {
            addDecision(symbol, "BUY", "BLOCKED", "ORDER_TOO_SMALL", "Order amount below minimum");
            return;
        }

        boolean isPaper = "PAPER".equalsIgnoreCase(cfg.getMode());
        int qty;
        double fillPrice;

        if (isPaper) {
            fillPrice = price * 1.001;
            qty = (int) ((orderAmount.doubleValue() * 0.99985) / fillPrice);
            if (qty <= 0) return;
        } else {
            int estQty = (int) (orderAmount.doubleValue() / price);
            if (estQty <= 0) return;
            try {
                LiveOrderService.LiveOrderResult r = liveOrders.placeBuyOrder(symbol, MarketType.KRX, estQty, price);
                if (!r.isFilled()) {
                    addDecision(symbol, "BUY", "ERROR", "ORDER_NOT_FILLED", "state=" + r.state);
                    return;
                }
                fillPrice = r.avgPrice > 0 ? r.avgPrice : price;
                qty = r.executedQty > 0 ? r.executedQty : estQty;
            } catch (Exception e) {
                addDecision(symbol, "BUY", "ERROR", "ORDER_EXCEPTION", e.getMessage());
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
        log.info("[KrxAllday] BUY {} mode={} price={} qty={} conf={}", symbol, cfg.getMode(), fillPrice, qty, signal.confidence);
    }

    private void executeSell(PositionEntity pe, StockCandle candle, Signal signal,
                              KrxAlldayConfigEntity cfg) {
        double price = candle.trade_price;
        boolean isPaper = "PAPER".equalsIgnoreCase(cfg.getMode());
        double fillPrice;
        int qty = pe.getQty();

        if (isPaper) {
            fillPrice = price * 0.999;
        } else {
            try {
                LiveOrderService.LiveOrderResult r = liveOrders.placeSellOrder(pe.getSymbol(), MarketType.KRX, qty, price);
                if (!r.isFilled()) {
                    addDecision(pe.getSymbol(), "SELL", "ERROR", "ORDER_NOT_FILLED", "state=" + r.state);
                    return;
                }
                fillPrice = r.avgPrice > 0 ? r.avgPrice : price;
            } catch (Exception e) {
                addDecision(pe.getSymbol(), "SELL", "ERROR", "ORDER_EXCEPTION", e.getMessage());
                return;
            }
        }

        double avgPrice = pe.getAvgPrice().doubleValue();
        double pnlKrw = (fillPrice - avgPrice) * qty - fillPrice * qty * 0.00015;
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
        log.info("[KrxAllday] SELL {} price={} pnl={} roi={}%", pe.getSymbol(), fillPrice,
                String.format("%.0f", pnlKrw), String.format("%.2f", roiPct));
    }

    // ========== Quick TP Ticker ==========

    private void startQuickTpTicker() {
        KrxAlldayConfigEntity cfg = configRepo.loadOrCreate();
        if (!cfg.isQuickTpEnabled()) return;
        int intervalSec = Math.max(3, cfg.getQuickTpIntervalSec());
        tickerExec = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "krx-allday-quick-tp"); t.setDaemon(true); return t;
            }
        });
        tickerFuture = tickerExec.scheduleAtFixedRate(new Runnable() {
            @Override public void run() {
                try { tickQuickTp(); } catch (Exception e) { log.error("[KrxAllday] Quick TP error", e); }
            }
        }, intervalSec, intervalSec, TimeUnit.SECONDS);
    }

    private void stopQuickTpTicker() {
        if (tickerFuture != null) { tickerFuture.cancel(false); tickerFuture = null; }
        if (tickerExec != null) { tickerExec.shutdownNow(); tickerExec = null; }
    }

    private void tickQuickTp() {
        if (!running.get()) return;
        KrxAlldayConfigEntity cfg = configRepo.loadOrCreate();
        if (!cfg.isQuickTpEnabled()) return;

        for (PositionEntity pe : positionRepo.findAll()) {
            if (!ENTRY_STRATEGY.equals(pe.getEntryStrategy()) || pe.getQty() <= 0) continue;
            try {
                StockCandle ticker = tickerService.getCurrentPrice(pe.getSymbol(), MarketType.KRX);
                if (ticker == null || ticker.trade_price <= 0) continue;
                double avgPrice = pe.getAvgPrice().doubleValue();
                if (avgPrice <= 0) continue;
                double pnlPct = (ticker.trade_price - avgPrice) / avgPrice * 100.0;
                // 고신뢰(confidence >= 9.7) 포지션은 Quick TP 목표 상향 (최소 1.5%)
                double quickTpPct = cfg.getQuickTpPct();
                try {
                    TradeEntity buyTrade = tradeLogRepo.findTop1BySymbolAndActionOrderByTsEpochMsDesc(pe.getSymbol(), "BUY");
                    if (buyTrade != null && buyTrade.getConfidence() != null && buyTrade.getConfidence() >= 9.7) {
                        quickTpPct = Math.max(quickTpPct, 1.5);
                    }
                } catch (Exception e) {
                    log.debug("[KrxAllday] Failed to lookup confidence for {}: {}", pe.getSymbol(), e.getMessage());
                }
                if (pnlPct >= quickTpPct) {
                    String reason = String.format(Locale.ROOT,
                            "QUICK_TP pnl=%.2f%% >= target=%.2f%%", pnlPct, quickTpPct);
                    PositionEntity fresh = positionRepo.findById(pe.getSymbol()).orElse(null);
                    if (fresh == null || fresh.getQty() <= 0) continue;
                    executeSell(fresh, ticker, Signal.of(SignalAction.SELL, null, reason), cfg);
                    addDecision(pe.getSymbol(), "SELL", "EXECUTED", "QUICK_TP", reason);
                }
            } catch (Exception e) {
                log.error("[KrxAllday] Quick TP failed for {}", pe.getSymbol(), e);
            }
        }
    }

    // ========== Helpers ==========

    private BigDecimal calcOrderSize(KrxAlldayConfigEntity cfg) {
        if ("FIXED".equalsIgnoreCase(cfg.getOrderSizingMode())) return cfg.getOrderSizingValue();
        BigDecimal pct = cfg.getOrderSizingValue();
        return getGlobalCapital().multiply(pct).divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN);
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
            if (pe.getQty() > 0 && pe.getAvgPrice() != null) sum += pe.getQty() * pe.getAvgPrice().doubleValue();
        }
        return sum;
    }

    private boolean checkIndexFilter(String indexSymbol, int candleUnit, int emaPeriod) {
        try {
            List<StockCandle> candles = candleService.getMinuteCandles(indexSymbol, MarketType.KRX, candleUnit, emaPeriod + 10, null);
            if (candles == null || candles.size() < emaPeriod) return true;
            double ema = Indicators.ema(candles, emaPeriod);
            double close = candles.get(candles.size() - 1).trade_price;
            return close >= ema;
        } catch (Exception e) {
            log.error("[KrxAllday] Index filter check failed", e);
            return true;
        }
    }

    private List<String> getTopSymbolsByVolume(int topN, Set<String> excludeSymbols, MarketType marketType) {
        try {
            List<String> all = exchangeAdapter.getTopSymbolsByVolume(
                    Math.max(topN + excludeSymbols.size() + 10, 100), marketType);
            List<String> filtered = new ArrayList<String>();
            for (String symbol : all) {
                if (excludeSymbols.contains(symbol)) continue;
                filtered.add(symbol);
                if (filtered.size() >= topN) break;
            }
            log.info("[KrxAllday] Volume ranking: {} symbols selected (excluded {})",
                    filtered.size(), excludeSymbols.size());
            return filtered;
        } catch (Exception e) {
            log.error("[KrxAllday] Failed to fetch volume ranking", e);
            return Collections.emptyList();
        }
    }
}
