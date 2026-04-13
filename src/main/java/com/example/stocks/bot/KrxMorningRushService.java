package com.example.stocks.bot;

import com.example.stocks.db.*;
import com.example.stocks.exchange.ExchangeAdapter;
import com.example.stocks.kis.KisPublicClient;
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
    private final KisPublicClient kisPublic;

    // ========== Runtime state ==========

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ScheduledExecutorService scheduler;

    // 실시간 TP/SL 매도 전용 스레드 + 포지션 캐시
    private final java.util.concurrent.ExecutorService tpSlExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(
            new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "krx-mr-tp-sell");
                    t.setDaemon(true);
                    return t;
                }
            });
    private final ConcurrentHashMap<String, double[]> positionCache = new ConcurrentHashMap<String, double[]>();
    private volatile double cachedTpPct = 3.0;
    private volatile double cachedSlPct = 3.0;
    private volatile KisWebSocketClient.PriceListener wsListener;

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
    // confirm 가격 이력 (상승 추세 확인용, 2026-04-09 추가)
    private final ConcurrentHashMap<String, Object> confirmPriceHistory = new ConcurrentHashMap<String, Object>();

    // Session phase tracking
    private volatile boolean rangeCollected = false;
    private volatile boolean entryPhaseComplete = false;

    // 당일 매매 완료 종목 (익절/손절 후 재매수 방지, 2026-04-11 추가)
    // 모닝러쉬 원칙: 종목당 1회만 매수. 매도 후 같은 종목 재진입 금지.
    private final Set<String> tradedSymbols = ConcurrentHashMap.newKeySet();

    // Alive heartbeat (every 60s) — proves mainLoop is still ticking,
    // even when statusText is silent (e.g., IDLE outside hours)
    private volatile long lastAliveLogMs = 0;

    // Decision log
    private static final int MAX_DECISION_LOG = 200;
    private final Deque<ScannerDecision> decisionLog = new ArrayDeque<ScannerDecision>();

    private final com.example.stocks.db.KrxOvertimeRankLogRepository overtimeRankRepo;

    public KrxMorningRushService(KrxMorningRushConfigRepository configRepo,
                                  BotConfigRepository botConfigRepo,
                                  PositionRepository positionRepo,
                                  TradeRepository tradeLogRepo,
                                  LiveOrderService liveOrders,
                                  TickerService tickerService,
                                  CandleService candleService,
                                  KisWebSocketClient kisWs,
                                  TransactionTemplate txTemplate,
                                  ExchangeAdapter exchangeAdapter,
                                  KisPublicClient kisPublic,
                                  com.example.stocks.db.KrxOvertimeRankLogRepository overtimeRankRepo) {
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
        this.kisPublic = kisPublic;
        this.overtimeRankRepo = overtimeRankRepo;
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
        // 파일 로그 기록 (2026-04-11 버그 수정: 메모리에만 저장하고 파일에 안 남던 문제)
        if ("SKIPPED".equals(result) || "BLOCKED".equals(result) || "ERROR".equals(result)) {
            log.info("[KrxMorningRush] {} {} {} {} | {}", symbol, action, result, reasonCode, reason);
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
        // CRITICAL: catch Throwable (NOT Exception) to prevent ScheduledExecutorService
        // from cancelling the future on unchecked errors. ScheduledExecutorService doc:
        // "If any execution of the task encounters an exception, subsequent executions
        // are suppressed." → catching Exception only would let Error/Throwable kill the
        // scheduler silently, leaving the bot in zombie state (process alive, mainLoop dead).
        // Sacrificed reproducer: 2026-04-07 KRX morning rush silently dead, only KIS WS
        // PINGPONG kept process appearing alive.
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    mainLoop();
                } catch (Throwable t) {
                    log.error("[KrxMorningRush] main loop error (caught Throwable, scheduler kept alive)", t);
                }
            }
        }, 0, 1, TimeUnit.SECONDS);

        // WebSocket 실시간 TP/SL 리스너 등록
        wsListener = new KisWebSocketClient.PriceListener() {
            @Override
            public void onPrice(String symbol, double price) {
                checkRealtimeTpSl(symbol, price);
            }
        };
        kisWs.addPriceListener(wsListener);

        return true;
    }

    public boolean stop() {
        if (!running.compareAndSet(true, false)) {
            log.info("[KrxMorningRush] already stopped");
            return false;
        }
        log.info("[KrxMorningRush] stopping...");
        if (wsListener != null) {
            kisWs.removePriceListener(wsListener);
            wsListener = null;
        }
        positionCache.clear();
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

        // Alive heartbeat (every 60s) — proves the scheduled future is still firing
        long aliveNow = System.currentTimeMillis();
        if (aliveNow - lastAliveLogMs > 60_000L) {
            log.info("[KrxMorningRush] alive: status={} kstNow={}",
                    statusText, ZonedDateTime.now(KST));
            lastAliveLogMs = aliveNow;
        }

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
        // Range:  08:50 - 09:00  (collect previous close + avg volume)
        // Entry:  09:00 + entryDelaySec ~ 09:10  (10분 entry window, 5분→10분 확장)
        // Hold:   09:10 ~ session_end (보유만, 신규 매수 X)
        boolean isRangePhase = (nowMinOfDay >= 8 * 60 + 50) && (nowMinOfDay < 9 * 60);
        boolean isEntryPhase;
        if (nowMinOfDay == 9 * 60) {
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

        // 09:00:00 ~ 09:00+entryDelaySec: 레인지 수집 완료 후 진입 대기 구간
        // 이 구간은 isRangePhase=false, isEntryPhase=false이지만 데이터 유지 필요
        boolean isWaitingForEntry = (nowMinOfDay == 9 * 60) && (second < cfg.getEntryDelaySec());

        if (!isRangePhase && !isEntryPhase && !isWaitingForEntry) {
            // Outside operating hours -- reset for next day
            if (rangeCollected || entryPhaseComplete) {
                rangeCollected = false;
                entryPhaseComplete = false;
                confirmCounts.clear();
                prevCloseMap.clear();
                avgVolumeMap.clear();
                tradedSymbols.clear();
            }
            statusText = "IDLE (outside hours)";
            return;
        }

        if (isWaitingForEntry) {
            statusText = "WAITING (entry delay)";
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

        // ---- Entry Phase: scan for gap-up spikes (09:00 ~ 09:10, 10분 entry window) ----
        if (isEntryPhase && !entryPhaseComplete) {
            if (nowMinOfDay >= 9 * 60 + 10) {
                entryPhaseComplete = true;
                log.info("[KrxMorningRush] Entry phase complete (09:10), switching to MONITORING");
            } else {
                statusText = "SCANNING";
                scanForEntry(cfg);
            }
        }

        // ---- 실시간 TP/SL 캐시 업데이트 ----
        updateRealtimeCache(cfg);

        // ---- Monitor existing positions for TP/SL/TimeStop (REST fallback) ----
        if (rushPosCount > 0) {
            monitorPositions(cfg);
        }
    }

    // ========== Phase 1: Range Collection ==========

    private void collectRange(KrxMorningRushConfigEntity cfg) {
        if (rangeCollected) return;

        Set<String> excludeSet = cfg.getExcludeSymbolsSet();

        // Get top symbols by volume using the exchange
        List<String> volumeSymbols = getTopSymbolsByVolume(cfg.getTopN(), excludeSet, cfg.getMinPriceKrw());

        // Get after-hours (시간외) updown ranking — DB 우선, fallback API
        // 2026-04-09 변경: 매일 18:05 수집된 DB 데이터를 1순위로 사용.
        // DB에 없으면 KIS API 실시간 호출 (fallback).
        List<String> overtimeSymbols = new ArrayList<String>();
        boolean fromDb = false;
        try {
            // 1순위: DB에서 직전 거래일 시간외 순위 로드
            java.time.LocalDate today = java.time.LocalDate.now(KST);
            // 주말이면 금요일, 평일이면 전일
            java.time.LocalDate prevTradeDate = today.minusDays(1);
            while (prevTradeDate.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                    || prevTradeDate.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
                prevTradeDate = prevTradeDate.minusDays(1);
            }
            // 오늘 날짜도 체크 (18:05 수집 후 같은 날 모닝러쉬 테스트 가능)
            java.util.List<com.example.stocks.db.KrxOvertimeRankLogEntity> dbRanking =
                    overtimeRankRepo.findByTradeDateOrderByRankNoAsc(today);
            if (dbRanking.isEmpty()) {
                dbRanking = overtimeRankRepo.findByTradeDateOrderByRankNoAsc(prevTradeDate);
            }
            if (!dbRanking.isEmpty()) {
                long minOtVol = cfg.getMinOvertimeVolume();
                int beforeFilter = 0;
                int filteredOut = 0;
                for (com.example.stocks.db.KrxOvertimeRankLogEntity e : dbRanking) {
                    String sym = e.getSymbol();
                    if (sym != null && !sym.isEmpty() && !excludeSet.contains(sym)) {
                        beforeFilter++;
                        // 시간외 거래량 필터 (2026-04-11): "가짜 상한가" 제거
                        // 거래량 1~50주로 +10% 상한가인 종목은 09:00 모멘텀 없음
                        Long otVol = e.getVolume();
                        if (minOtVol > 0 && (otVol == null || otVol < minOtVol)) {
                            log.info("[KrxMorningRush] 시간외 거래량 미달 제외: {} {} vol={} < min={}",
                                    sym, e.getSymbolName(), otVol, minOtVol);
                            filteredOut++;
                            continue;
                        }
                        overtimeSymbols.add(sym);
                    }
                }
                fromDb = true;
                log.info("[KrxMorningRush] After-hours ranking from DB: {} symbols (date={}, 거래량 미달 제외={})",
                        overtimeSymbols.size(), dbRanking.get(0).getTradeDate(), filteredOut);
            }
        } catch (Exception e) {
            log.warn("[KrxMorningRush] DB overtime ranking load failed: {}", e.getMessage());
        }

        // 2순위: DB에 없으면 KIS API 실시간 호출 (fallback)
        if (!fromDb || overtimeSymbols.isEmpty()) {
            try {
                List<Map<String, Object>> overtimeRanking = kisPublic.getOvertimeUpdownRanking(15);
                for (Map<String, Object> item : overtimeRanking) {
                    Object code = item.get("mksc_shrn_iscd");
                    if (code != null) {
                        String sym = code.toString().trim();
                        if (!sym.isEmpty() && !excludeSet.contains(sym)) {
                            overtimeSymbols.add(sym);
                        }
                    }
                }
                log.info("[KrxMorningRush] After-hours ranking from API (fallback): {} symbols", overtimeSymbols.size());
            } catch (Exception e) {
                log.warn("[KrxMorningRush] Failed to fetch after-hours ranking (API fallback): {}", e.getMessage());
            }
        }

        // Merge: after-hours first, then volume-based (deduplicate)
        Set<String> seen = new LinkedHashSet<String>();
        for (String sym : overtimeSymbols) {
            seen.add(sym);
        }
        for (String sym : volumeSymbols) {
            seen.add(sym);
        }
        List<String> mergedAll = new ArrayList<String>(seen);
        // WebSocket 최대 20종목 제한 → 상위 20개만
        List<String> topSymbols = mergedAll.size() > 20
                ? new ArrayList<String>(mergedAll.subList(0, 20))
                : mergedAll;

        // Log source breakdown
        int fromOvertime = 0;
        int fromVolume = 0;
        Set<String> overtimeSet = new HashSet<String>(overtimeSymbols);
        for (String sym : topSymbols) {
            if (overtimeSet.contains(sym)) {
                fromOvertime++;
            } else {
                fromVolume++;
            }
        }
        log.info("[KrxMorningRush] Merged symbols: {} total (after-hours={}, volume-only={})",
                topSymbols.size(), fromOvertime, fromVolume);

        for (String symbol : topSymbols) {
            try {
                // Get previous day close price (전일 종가) — stck_sdpr 필드 사용
                // 2026-04-09 변경: stck_prpr(현재가, 시간외 종가 포함) → stck_sdpr(전일 종가)
                // stck_prpr 사용 시 시간외 종가가 반환되어 gap이 0%에 가까워 매수 불가했음
                Map<String, Object> priceData = kisPublic.getDomesticCurrentPrice(symbol);
                Object sdpr = priceData.get("stck_sdpr");  // 전일 종가 (기준가)
                if (sdpr != null) {
                    double prevClose = Double.parseDouble(sdpr.toString().trim());
                    if (prevClose > 0) {
                        prevCloseMap.put(symbol, prevClose);
                        log.debug("[KrxMorningRush] {} prevClose(전일종가)={}", symbol, prevClose);
                    }
                }
                if (!prevCloseMap.containsKey(symbol)) {
                    // fallback: getCurrentPrice (이전 방식)
                    StockCandle ticker = tickerService.getCurrentPrice(symbol, MarketType.KRX);
                    if (ticker != null && ticker.trade_price > 0) {
                        prevCloseMap.put(symbol, ticker.trade_price);
                        log.debug("[KrxMorningRush] {} prevClose(fallback 현재가)={}", symbol, ticker.trade_price);
                    }
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

        // ★ 선정 종목 전체 + prevClose 로그 (사용자 다음날 확인용, 2026-04-09)
        log.info("[KrxMorningRush] ========== 스캔 종목 선정 결과 ==========");
        log.info("[KrxMorningRush] 시간외 순위: {} symbols, 거래대금: {} symbols, 합계(중복제거): {} → TOP-{}",
                overtimeSymbols.size(), volumeSymbols.size(), mergedAll.size(), topSymbols.size());
        log.info("[KrxMorningRush] 선정 종목 list: {}", topSymbols);
        for (String sym : topSymbols) {
            Double pc = prevCloseMap.get(sym);
            String src = overtimeSet.contains(sym) ? "시간외순위" : "거래대금";
            log.info("[KrxMorningRush] 종목 {} | prevClose(전일종가)={}원 | 출처={}", sym, pc, src);
        }
        log.info("[KrxMorningRush] ==========================================");

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

            // 당일 매매 완료 종목 재매수 차단 (종목당 1회, 2026-04-11)
            if (tradedSymbols.contains(symbol)) continue;

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
                    log.debug("[KrxMorningRush] {} REST fallback failed", symbol);
                    continue;
                }
            }
            if (currentPrice <= 0) {
                continue;
            }

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
                // ★ gap 미달 로그 추가 (사용자 확인용, 2026-04-09)
                addDecision(symbol, "BUY", "SKIPPED", "GAP_LOW",
                        String.format("gap=%.2f%% < %.2f%% price=%.0f prevClose=%.0f",
                                gapPct * 100, gapThreshold * 100, currentPrice, prevClose));
                confirmCounts.remove(symbol);
                confirmPriceHistory.remove(symbol);
                continue;
            }

            // Volume check (REST API 호출이므로 volumeMult > 1.0일 때만 실행)
            if (volumeMultiplier > 1.0) {
                Double avgVol = avgVolumeMap.get(symbol);
                if (avgVol != null && avgVol > 0) {
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
            }

            // Confirm count tracking + 상승 추세 확인 (2026-04-09 추가)
            // confirm 3회: gap 2% 이상 + 마지막(3번째) 가격이 1번째, 2번째보다 높아야 함
            // confirmPrices에 가격 이력 저장 → 3번째에 상승 여부 판단
            Integer prevCount = confirmCounts.get(symbol);
            int count = (prevCount != null ? prevCount : 0) + 1;
            confirmCounts.put(symbol, count);

            // 가격 이력 저장
            @SuppressWarnings("unchecked")
            java.util.Deque<Double> prices = (java.util.Deque<Double>) confirmPriceHistory
                    .computeIfAbsent(symbol, k -> new java.util.ArrayDeque<Double>());
            prices.addLast(currentPrice);
            // requiredConfirms 초과분 제거
            while (prices.size() > requiredConfirms) prices.pollFirst();

            if (count < requiredConfirms) {
                addDecision(symbol, "BUY", "SKIPPED", "CONFIRMING",
                        String.format("Confirm %d/%d gap=%.2f%% price=%.0f", count, requiredConfirms, gapPct * 100, currentPrice));
                continue;
            }

            // ════════════════════════════════════════════════════════════════
            // ascending check 비활성화 (2026-04-11)
            // ════════════════════════════════════════════════════════════════
            // 비활성화 이유:
            //   - 4/10 실거래 분석: 엔피(291230) prevClose=846 → 시가 922(+8.98% gap)
            //     09:00 1014 → 09:02 968(하락) → 09:03 1081(반등) → ascending 실패 반복
            //   - 모닝러쉬 첫봉 급등 후 등락이 심한 것이 정상 패턴
            //   - 3연속 상승 조건은 이 패턴에서 절대 충족 불가
            //   - confirm 3회(gap 2% 유지 확인)만으로 충분한 진입 검증
            //   - 4/10 시뮬레이션: ascending 제거 시 엔피 968원 매수 → +2% 익절 가능
            // ════════════════════════════════════════════════════════════════

            // 확인 완료 — 로그 초기화
            confirmCounts.remove(symbol);
            prices.clear();

            // Execute BUY
            try {
                executeBuy(symbol, currentPrice, gapPct, cfg, orderAmount);
                rushPosCount++;
                tradedSymbols.add(symbol); // 당일 재매수 방지
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

        // 실시간 TP/SL 캐시에 즉시 등록
        positionCache.put(symbol, new double[]{fillPrice});
        cachedTpPct = cfg.getTpPct().doubleValue();
        cachedSlPct = cfg.getSlPct().doubleValue();

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

    // ========== WebSocket 실시간 TP/SL ==========

    /**
     * WebSocket 가격 수신 시 즉시 TP/SL 체크 (DB 접근 없음, 메모리 캐시만 사용).
     * TP/SL 도달 시 tpSlExecutor에서 매도 실행.
     */
    private void checkRealtimeTpSl(final String symbol, final double price) {
        if (!running.get()) return;

        double[] pos = positionCache.get(symbol);
        if (pos == null) return;

        double avgPrice = pos[0];
        if (avgPrice <= 0) return;

        double pnlPct = (price - avgPrice) / avgPrice * 100.0;

        if (pnlPct >= cachedTpPct || pnlPct <= -cachedSlPct) {
            final String sellType = pnlPct >= cachedTpPct ? "TP" : "SL";
            final String reason = String.format(java.util.Locale.ROOT,
                    "%s pnl=%.2f%% price=%.0f avg=%.0f (realtime)",
                    sellType, pnlPct, price, avgPrice);

            // 중복 방지: 캐시에서 즉시 제거
            if (positionCache.remove(symbol) == null) return;

            log.info("[KrxMorningRush] realtime {} detected | {} | {}", sellType, symbol, reason);

            tpSlExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        PositionEntity fresh = positionRepo.findById(symbol).orElse(null);
                        if (fresh == null || fresh.getQty() <= 0) return;
                        if (!ENTRY_STRATEGY.equals(fresh.getEntryStrategy())) return;
                        KrxMorningRushConfigEntity cfg = configRepo.loadOrCreate();
                        executeSell(fresh, price, Signal.of(SignalAction.SELL, null, reason), cfg);
                    } catch (Exception e) {
                        log.error("[KrxMorningRush] realtime sell failed for {}", symbol, e);
                    }
                }
            });
        }
    }

    /**
     * mainLoop에서 호출: 포지션 캐시 + TP/SL 캐시 업데이트.
     */
    private void updateRealtimeCache(KrxMorningRushConfigEntity cfg) {
        cachedTpPct = cfg.getTpPct().doubleValue();
        cachedSlPct = cfg.getSlPct().doubleValue();

        Set<String> current = new java.util.HashSet<String>();
        for (PositionEntity pe : positionRepo.findAll()) {
            if (ENTRY_STRATEGY.equals(pe.getEntryStrategy()) && pe.getQty() > 0) {
                current.add(pe.getSymbol());
                if (!positionCache.containsKey(pe.getSymbol())) {
                    positionCache.put(pe.getSymbol(), new double[]{pe.getAvgPrice().doubleValue()});
                }
            }
        }
        for (String sym : new java.util.ArrayList<String>(positionCache.keySet())) {
            if (!current.contains(sym)) positionCache.remove(sym);
        }
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
