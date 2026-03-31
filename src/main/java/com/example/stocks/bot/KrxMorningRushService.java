package com.example.stocks.bot;

import com.example.stocks.db.*;
import com.example.stocks.exchange.ExchangeAdapter;
import com.example.stocks.kis.KisWebSocketClient;
import com.example.stocks.market.CandleService;
import com.example.stocks.market.MarketType;
import com.example.stocks.market.StockCandle;
import com.example.stocks.market.TickerService;
import com.example.stocks.strategy.Signal;
import com.example.stocks.strategy.SignalAction;
import com.example.stocks.trade.LiveOrderService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionStatus;

import javax.annotation.PreDestroy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * KRX Morning Rush Scanner -- 09:00 KST gap-up spike catcher.
 *
 * Flow:
 * 1. 08:50-09:00 KST -- collect previous close prices from KIS API
 * 2. 09:01:00 KST (entry_delay_sec after open) -- scan for gap-up entries
 *    - price > prevClose * (1 + gapThreshold%)
 *    - volume > avgVolume * volumeMult
 *    - confirmCount consecutive confirms
 *    - VI check: StockSafetyGuard.isNearViLimit()
 * 3. TP 1.5%, SL 1.5%, session end 10:00 KST
 * 4. Time stop: 30min (if losing after 30min, exit)
 *
 * scanner_source = "KRX_MORNING_RUSH"
 */
@Service
public class KrxMorningRushService {

    private static final Logger log = LoggerFactory.getLogger(KrxMorningRushService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String SCANNER_SOURCE = "KRX_MORNING_RUSH";
    private static final String ENTRY_STRATEGY = "KRX_MORNING_RUSH";

    // ========== Dependencies ==========

    private final KrxMorningRushConfigRepository configRepo;
    private final BotConfigRepository botConfigRepo;
    private final PositionRepository positionRepo;
    private final TradeRepository tradeLogRepo;
    private final LiveOrderService liveOrders;
    private final TickerService tickerService;
    private final CandleService candleService;
    private final KisWebSocketClient kisWs;
    private final TransactionTemplate txTemplate;
    private final ExchangeAdapter exchangeAdapter;

    // ========== Runtime state ==========

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ScheduledExecutorService scheduler;

    // Dashboard state
    private volatile String statusText = "STOPPED";
    private volatile int scanCount = 0;
    private volatile int activePositions = 0;
    private volatile List<String> lastScannedSymbols = Collections.emptyList();
    private volatile long lastTickEpochMs = 0;

    // Price tracking for gap-up confirmation
    private final ConcurrentHashMap<String, Double> prevCloseMap = new ConcurrentHashMap<String, Double>();
    private final ConcurrentHashMap<String, Double> avgVolumeMap = new ConcurrentHashMap<String, Double>();
    private final ConcurrentHashMap<String, Integer> confirmCounts = new ConcurrentHashMap<String, Integer>();

    // Session phase tracking
    private volatile boolean rangeCollected = false;
    private volatile boolean entryPhaseComplete = false;

    // Decision log
    private static final int MAX_DECISION_LOG = 200;
    private final Deque<ScannerDecision> decisionLog = new ArrayDeque<ScannerDecision>();

    public KrxMorningRushService(KrxMorningRushConfigRepository configRepo,
                                  BotConfigRepository botConfigRepo,
                                  PositionRepository positionRepo,
                                  TradeRepository tradeLogRepo,
                                  LiveOrderService liveOrders,
                                  TickerService tickerService,
                                  CandleService candleService,
                                  KisWebSocketClient kisWs,
                                  TransactionTemplate txTemplate,
                                  ExchangeAdapter exchangeAdapter) {
        this.configRepo = configRepo;
        this.botConfigRepo = botConfigRepo;
        this.positionRepo = positionRepo;
        this.tradeLogRepo = tradeLogRepo;
        this.liveOrders = liveOrders;
        this.tickerService = tickerService;
        this.candleService = candleService;
        this.kisWs = kisWs;
        this.txTemplate = txTemplate;
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

    // ========== Lifecycle ==========

    public boolean start() {
        if (!running.compareAndSet(false, true)) {
            log.info("[KrxMorningRush] already running");
            return false;
        }
        log.info("[KrxMorningRush] starting...");
        statusText = "RUNNING";
        rangeCollected = false;
        entryPhaseComplete = false;
        confirmCounts.clear();
        prevCloseMap.clear();
        avgVolumeMap.clear();

        scheduler = Executors.newScheduledThreadPool(2, new ThreadFactory() {
            private int seq = 0;
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "krx-mr-" + (seq++));
                t.setDaemon(true);
                return t;
            }
        });

        // Schedule the main loop at 1-second resolution to detect time phases
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    mainLoop();
                } catch (Exception e) {
                    log.error("[KrxMorningRush] main loop error", e);
                }
            }
        }, 0, 1, TimeUnit.SECONDS);

        return true;
    }

    public boolean stop() {
        if (!running.compareAndSet(true, false)) {
            log.info("[KrxMorningRush] already stopped");
            return false;
        }
        log.info("[KrxMorningRush] stopping...");
        statusText = "STOPPED";
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        return true;
    }

    @PreDestroy
    public void destroy() {
        stop();
    }

    // ========== Status accessors ==========

    public boolean isRunning() { return running.get(); }
    public String getStatusText() { return statusText; }
    public int getScanCount() { return scanCount; }
    public int getActivePositions() { return activePositions; }
    public List<String> getLastScannedSymbols() { return lastScannedSymbols; }
    public long getLastTickEpochMs() { return lastTickEpochMs; }

    public ScannerStatusDto getStatus() {
        ScannerStatusDto dto = new ScannerStatusDto();
        dto.setRunning(running.get());
        KrxMorningRushConfigEntity cfg = configRepo.loadOrCreate();
        dto.setMode(cfg.getMode());
        dto.setStatusText(statusText);
        dto.setScanCount(scanCount);
        dto.setActivePositions(activePositions);
        dto.setScannedSymbols(lastScannedSymbols);
        dto.setLastTickEpochMs(lastTickEpochMs);
        return dto;
    }

    // ========== Main Loop ==========

    private void mainLoop() {
        if (!running.get()) return;

        KrxMorningRushConfigEntity cfg = configRepo.loadOrCreate();
        if (!cfg.isEnabled()) {
            statusText = "DISABLED";
            return;
        }

        ZonedDateTime nowKst = ZonedDateTime.now(KST);

        // Check KRX trading day
        DayOfWeek dow = nowKst.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            statusText = "IDLE (weekend)";
            return;
        }
        if (MarketCalendar.isHoliday(nowKst.toLocalDate(), MarketType.KRX)) {
            statusText = "IDLE (holiday)";
            return;
        }

        int hour = nowKst.getHour();
        int minute = nowKst.getMinute();
        int second = nowKst.getSecond();
        int nowMinOfDay = hour * 60 + minute;

        int sessionEndMin = cfg.getSessionEndHour() * 60 + cfg.getSessionEndMin();

        // Phase timing (KST)
        // Range:  08:50 - 09:00  (collect previous close)
        // Entry:  09:00 + entryDelaySec - sessionEnd  (scan for gap-up entries)
        // Session end: force exit
        boolean isRangePhase = (nowMinOfDay >= 8 * 60 + 50) && (nowMinOfDay < 9 * 60);
        boolean isEntryPhase;
        if (nowMinOfDay == 9 * 60) {
            // At 09:00, entry starts after entry_delay_sec
            isEntryPhase = second >= cfg.getEntryDelaySec();
        } else {
            isEntryPhase = (nowMinOfDay > 9 * 60) && (nowMinOfDay < sessionEndMin);
        }
        boolean isSessionEnd = (nowMinOfDay >= sessionEndMin);

        // Update active position count
        int rushPosCount = 0;
        List<PositionEntity> allPos = positionRepo.findAll();
        for (PositionEntity pe : allPos) {
            if (ENTRY_STRATEGY.equals(pe.getEntryStrategy()) && pe.getQty() > 0) {
                rushPosCount++;
            }
        }
        activePositions = rushPosCount;

        // Session end: force exit all morning rush positions
        if (isSessionEnd && rushPosCount > 0) {
            statusText = "SESSION_END";
            forceExitAll(cfg);
            return;
        }

        if (!isRangePhase && !isEntryPhase) {
            // Outside operating hours -- reset for next day
            if (rangeCollected || entryPhaseComplete) {
                rangeCollected = false;
                entryPhaseComplete = false;
                confirmCounts.clear();
                prevCloseMap.clear();
                avgVolumeMap.clear();
            }
            statusText = "IDLE (outside hours)";
            return;
        }

        // Throttle: only run trade logic at checkIntervalSec frequency
        long nowMs = System.currentTimeMillis();
        int intervalSec = cfg.getCheckIntervalSec();
        if (nowMs - lastTickEpochMs < intervalSec * 1000L) {
            return; // skip this second
        }
        lastTickEpochMs = nowMs;

        // ---- Range Phase: collect previous close prices ----
        if (isRangePhase) {
            statusText = "COLLECTING_RANGE";
            collectRange(cfg);
            return;
        }

        // ---- Entry Phase: scan for gap-up spikes ----
        if (isEntryPhase && !entryPhaseComplete) {
            statusText = "SCANNING";
            scanForEntry(cfg);
        }

        // ---- Monitor existing positions for TP/SL/TimeStop ----
        if (rushPosCount > 0) {
            monitorPositions(cfg);
        }
    }

    // ========== Phase 1: Range Collection ==========

    private void collectRange(KrxMorningRushConfigEntity cfg) {
        if (rangeCollected) return;

        Set<String> excludeSet = cfg.getExcludeSymbolsSet();

        // Get top symbols by volume using the exchange
        // Use yesterday's daily candle close as previous close
        List<String> topSymbols = getTopSymbolsByVolume(cfg.getTopN(), excludeSet, cfg.getMinPriceKrw());

        for (String symbol : topSymbols) {
            try {
                // Get previous day candle for close price
                StockCandle ticker = tickerService.getCurrentPrice(symbol, MarketType.KRX);
                if (ticker != null && ticker.trade_price > 0) {
                    prevCloseMap.put(symbol, ticker.trade_price);
                }

                // Get average volume from recent candles
                List<StockCandle> candles = candleService.getMinuteCandles(symbol, MarketType.KRX, 5, 20, null);
                if (candles != null && !candles.isEmpty()) {
                    double totalVol = 0;
                    for (StockCandle c : candles) {
                        totalVol += c.candle_acc_trade_volume;
                    }
                    double avgVol = totalVol / candles.size();
                    avgVolumeMap.put(symbol, avgVol);
                }

                // Subscribe to KIS WebSocket for real-time prices
                kisWs.subscribe(symbol, false);
            } catch (Exception e) {
                log.debug("[KrxMorningRush] range collect error for {}: {}", symbol, e.getMessage());
            }
        }

        lastScannedSymbols = topSymbols;
        scanCount = topSymbols.size();
        rangeCollected = true;
        log.info("[KrxMorningRush] range collected: {} symbols, prevClose entries: {}",
                topSymbols.size(), prevCloseMap.size());
    }

    // ========== Phase 2: Entry Scanning ==========

    private void scanForEntry(KrxMorningRushConfigEntity cfg) {
        if (prevCloseMap.isEmpty()) {
            addDecision("*", "BUY", "BLOCKED", "NO_RANGE", "No previous close data collected");
            return;
        }

        String mode = cfg.getMode();
        boolean isLive = "LIVE".equalsIgnoreCase(mode);
        if (isLive && !liveOrders.isConfigured()) {
            addDecision("*", "TICK", "BLOCKED", "API_KEY_MISSING",
                    "LIVE mode but exchange API not configured");
            return;
        }

        int rushPosCount = 0;
        List<PositionEntity> allPos = positionRepo.findAll();
        Set<String> ownedSymbols = new HashSet<String>();
        for (PositionEntity pe : allPos) {
            if (pe.getQty() > 0) {
                ownedSymbols.add(pe.getSymbol());
                if (ENTRY_STRATEGY.equals(pe.getEntryStrategy())) {
                    rushPosCount++;
                }
            }
        }

        if (rushPosCount >= cfg.getMaxPositions()) {
            addDecision("*", "BUY", "BLOCKED", "MAX_POSITIONS",
                    String.format("Max positions (%d) reached", cfg.getMaxPositions()));
            return;
        }

        BigDecimal orderAmount = calcOrderSize(cfg);
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
                return;
            }
        }

        double gapThreshold = cfg.getGapThresholdPct().doubleValue() / 100.0;
        double volumeMultiplier = cfg.getVolumeMult().doubleValue();
        int requiredConfirms = cfg.getConfirmCount();

        for (String symbol : prevCloseMap.keySet()) {
            if (ownedSymbols.contains(symbol)) continue;
            if (rushPosCount >= cfg.getMaxPositions()) break;

            Double prevClose = prevCloseMap.get(symbol);
            if (prevClose == null || prevClose <= 0) continue;

            // Get real-time price from KIS WebSocket
            double currentPrice = kisWs.getLatestPrice(symbol);
            if (currentPrice <= 0) {
                // Fallback to REST API
                try {
                    StockCandle ticker = tickerService.getCurrentPrice(symbol, MarketType.KRX);
                    if (ticker != null && ticker.trade_price > 0) {
                        currentPrice = ticker.trade_price;
                    }
                } catch (Exception e) {
                    continue;
                }
            }
            if (currentPrice <= 0) continue;

            // Min price filter
            if (currentPrice < cfg.getMinPriceKrw()) continue;

            // VI proximity check
            if (StockSafetyGuard.isNearViLimit(currentPrice, prevClose)) {
                addDecision(symbol, "BUY", "BLOCKED", "VI_LIMIT",
                        String.format("Near VI limit: price=%.0f prevClose=%.0f", currentPrice, prevClose));
                continue;
            }

            // Gap check: price > prevClose * (1 + gapThreshold)
            double gapPct = (currentPrice - prevClose) / prevClose;
            if (gapPct < gapThreshold) {
                confirmCounts.remove(symbol);
                continue;
            }

            // Volume check
            Double avgVol = avgVolumeMap.get(symbol);
            if (avgVol != null && avgVol > 0) {
                // Get current volume from recent candle
                try {
                    List<StockCandle> recentCandles = candleService.getMinuteCandles(symbol, MarketType.KRX, 1, 1, null);
                    if (recentCandles != null && !recentCandles.isEmpty()) {
                        double currentVol = recentCandles.get(0).candle_acc_trade_volume;
                        if (currentVol < avgVol * volumeMultiplier) {
                            addDecision(symbol, "BUY", "SKIPPED", "LOW_VOLUME",
                                    String.format("Volume %.0f < avg %.0f x %.1f", currentVol, avgVol, volumeMultiplier));
                            confirmCounts.remove(symbol);
                            continue;
                        }
                    }
                } catch (Exception e) {
                    // Volume check failed, skip
                    continue;
                }
            }

            // Confirm count tracking
            Integer prevCount = confirmCounts.get(symbol);
            int count = (prevCount != null ? prevCount : 0) + 1;
            confirmCounts.put(symbol, count);

            if (count < requiredConfirms) {
                addDecision(symbol, "BUY", "SKIPPED", "CONFIRMING",
                        String.format("Confirm %d/%d gap=%.2f%%", count, requiredConfirms, gapPct * 100));
                continue;
            }

            // Execute BUY
            try {
                executeBuy(symbol, currentPrice, gapPct, cfg, orderAmount);
                rushPosCount++;
                addDecision(symbol, "BUY", "EXECUTED", "GAP_UP",
                        String.format("Gap %.2f%% price=%.0f prevClose=%.0f", gapPct * 100, currentPrice, prevClose));
            } catch (Exception e) {
                log.error("[KrxMorningRush] buy execution failed for {}", symbol, e);
                addDecision(symbol, "BUY", "ERROR", "EXECUTION_FAIL",
                        "Buy execution error: " + e.getMessage());
            }
        }
    }

    // ========== Position Monitoring (TP/SL/TimeStop) ==========

    private void monitorPositions(KrxMorningRushConfigEntity cfg) {
        List<PositionEntity> allPos = positionRepo.findAll();
        double tpPct = cfg.getTpPct().doubleValue() / 100.0;
        double slPct = cfg.getSlPct().doubleValue() / 100.0;
        int timeStopMin = cfg.getTimeStopMin();

        for (PositionEntity pe : allPos) {
            if (!ENTRY_STRATEGY.equals(pe.getEntryStrategy()) || pe.getQty() <= 0) continue;

            double avgPrice = pe.getAvgPrice().doubleValue();
            if (avgPrice <= 0) continue;

            // Get current price
            double currentPrice = kisWs.getLatestPrice(pe.getSymbol());
            if (currentPrice <= 0) {
                try {
                    StockCandle ticker = tickerService.getCurrentPrice(pe.getSymbol(), MarketType.KRX);
                    if (ticker != null && ticker.trade_price > 0) {
                        currentPrice = ticker.trade_price;
                    }
                } catch (Exception e) {
                    continue;
                }
            }
            if (currentPrice <= 0) continue;

            double pnlPct = (currentPrice - avgPrice) / avgPrice;

            // TP check
            if (pnlPct >= tpPct) {
                String reason = String.format(Locale.ROOT,
                        "TP hit: pnl=%.2f%% >= tp=%.2f%% price=%.0f avg=%.0f",
                        pnlPct * 100, tpPct * 100, currentPrice, avgPrice);
                executeSell(pe, currentPrice, Signal.of(SignalAction.SELL, null, reason), cfg);
                addDecision(pe.getSymbol(), "SELL", "EXECUTED", "TP", reason);
                continue;
            }

            // SL check
            if (pnlPct <= -slPct) {
                String reason = String.format(Locale.ROOT,
                        "SL hit: pnl=%.2f%% <= sl=-%.2f%% price=%.0f avg=%.0f",
                        pnlPct * 100, slPct * 100, currentPrice, avgPrice);
                executeSell(pe, currentPrice, Signal.of(SignalAction.SELL, null, reason), cfg);
                addDecision(pe.getSymbol(), "SELL", "EXECUTED", "SL", reason);
                continue;
            }

            // Time stop check
            if (pe.getOpenedAt() != null && timeStopMin > 0) {
                long elapsedMin = Duration.between(pe.getOpenedAt(), Instant.now()).toMinutes();
                if (elapsedMin >= timeStopMin && pnlPct < 0) {
                    String reason = String.format(Locale.ROOT,
                            "TIME_STOP: %dmin elapsed (limit=%d), pnl=%.2f%% price=%.0f",
                            elapsedMin, timeStopMin, pnlPct * 100, currentPrice);
                    executeSell(pe, currentPrice, Signal.of(SignalAction.SELL, null, reason), cfg);
                    addDecision(pe.getSymbol(), "SELL", "EXECUTED", "TIME_STOP", reason);
                }
            }
        }
    }

    // ========== Force Exit All ==========

    private void forceExitAll(KrxMorningRushConfigEntity cfg) {
        List<PositionEntity> allPos = positionRepo.findAll();
        for (PositionEntity pe : allPos) {
            if (!ENTRY_STRATEGY.equals(pe.getEntryStrategy()) || pe.getQty() <= 0) continue;

            double currentPrice = kisWs.getLatestPrice(pe.getSymbol());
            if (currentPrice <= 0) {
                try {
                    StockCandle ticker = tickerService.getCurrentPrice(pe.getSymbol(), MarketType.KRX);
                    if (ticker != null && ticker.trade_price > 0) {
                        currentPrice = ticker.trade_price;
                    }
                } catch (Exception e) {
                    log.error("[KrxMorningRush] force exit price fetch failed for {}", pe.getSymbol(), e);
                    continue;
                }
            }
            if (currentPrice <= 0) continue;

            String reason = "SESSION_END: KRX morning rush session closing";
            executeSell(pe, currentPrice, Signal.of(SignalAction.SELL, null, reason), cfg);
            addDecision(pe.getSymbol(), "SELL", "EXECUTED", "SESSION_END", reason);
        }
    }

    // ========== Order Execution ==========

    private void executeBuy(final String symbol, final double price, double gapPct,
                             final KrxMorningRushConfigEntity cfg, BigDecimal orderAmount) {
        if (orderAmount.compareTo(BigDecimal.valueOf(50000)) < 0) {
            addDecision(symbol, "BUY", "BLOCKED", "ORDER_TOO_SMALL",
                    String.format("Order amount %s below minimum 50,000", orderAmount.toPlainString()));
            return;
        }

        boolean isPaper = "PAPER".equalsIgnoreCase(cfg.getMode());
        final int qty;
        final double fillPrice;

        if (isPaper) {
            fillPrice = price * 1.001; // 0.1% slippage
            double fee = orderAmount.doubleValue() * 0.00015;
            qty = (int) ((orderAmount.doubleValue() - fee) / fillPrice);
            if (qty <= 0) {
                addDecision(symbol, "BUY", "BLOCKED", "QTY_ZERO", "Calculated qty is 0");
                return;
            }
        } else {
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

        txTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                PositionEntity pe = new PositionEntity();
                pe.setSymbol(symbol);
                pe.setMarketType("KRX");
                pe.setQty(qty);
                pe.setAvgPrice(fillPrice);
                pe.setAddBuys(0);
                pe.setOpenedAt(Instant.now());
                pe.setEntryStrategy(ENTRY_STRATEGY);
                pe.setScannerSource(SCANNER_SOURCE);
                positionRepo.save(pe);

                TradeEntity tl = new TradeEntity();
                tl.setTsEpochMs(System.currentTimeMillis());
                tl.setSymbol(symbol);
                tl.setMarketType("KRX");
                tl.setAction("BUY");
                tl.setPrice(fillPrice);
                tl.setQty(qty);
                tl.setPnlKrw(0);
                tl.setRoiPercent(0);
                tl.setMode(cfg.getMode());
                tl.setPatternType(ENTRY_STRATEGY);
                tl.setPatternReason("Gap-up entry");
                tl.setCurrency("KRW");
                tl.setScannerSource(SCANNER_SOURCE);
                tradeLogRepo.save(tl);
            }
        });

        log.info("[KrxMorningRush] BUY {} mode={} price={} qty={}", symbol, cfg.getMode(), fillPrice, qty);
    }

    private void executeSell(final PositionEntity pe, double price, Signal signal,
                              final KrxMorningRushConfigEntity cfg) {
        boolean isPaper = "PAPER".equalsIgnoreCase(cfg.getMode());
        final double fillPrice;
        final int qty = pe.getQty();

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
        final double roiPct = avgPrice > 0 ? ((fillPrice - avgPrice) / avgPrice) * 100.0 : 0;
        final double fPnlKrw = pnlKrw;
        final String peSymbol = pe.getSymbol();
        final String signalReason = signal.reason;

        txTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                TradeEntity tl = new TradeEntity();
                tl.setTsEpochMs(System.currentTimeMillis());
                tl.setSymbol(peSymbol);
                tl.setMarketType("KRX");
                tl.setAction("SELL");
                tl.setPrice(fillPrice);
                tl.setQty(qty);
                tl.setPnlKrw(fPnlKrw);
                tl.setRoiPercent(roiPct);
                tl.setMode(cfg.getMode());
                tl.setPatternType(ENTRY_STRATEGY);
                tl.setPatternReason(signalReason);
                tl.setAvgBuyPrice(pe.getAvgPrice().doubleValue());
                tl.setCurrency("KRW");
                tl.setScannerSource(SCANNER_SOURCE);
                tradeLogRepo.save(tl);

                positionRepo.deleteById(peSymbol);
            }
        });

        log.info("[KrxMorningRush] SELL {} price={} pnl={} roi={}% reason={}",
                peSymbol, fillPrice, String.format("%.0f", fPnlKrw),
                String.format("%.2f", roiPct), signalReason);
    }

    // ========== Helpers ==========

    private BigDecimal calcOrderSize(KrxMorningRushConfigEntity cfg) {
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

    private List<String> getTopSymbolsByVolume(int topN, Set<String> excludeSymbols, int minPriceKrw) {
        try {
            List<String> all = exchangeAdapter.getTopSymbolsByVolume(
                    Math.max(topN + excludeSymbols.size() + 10, 100), MarketType.KRX);
            List<String> filtered = new ArrayList<String>();
            for (String symbol : all) {
                if (excludeSymbols.contains(symbol)) continue;
                filtered.add(symbol);
                if (filtered.size() >= topN) break;
            }
            log.info("[KrxMorningRush] Volume ranking: {} symbols selected (excluded {})",
                    filtered.size(), excludeSymbols.size());
            return filtered;
        } catch (Exception e) {
            log.error("[KrxMorningRush] Failed to fetch volume ranking", e);
            return Collections.emptyList();
        }
    }
}
