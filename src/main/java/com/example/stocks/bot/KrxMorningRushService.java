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

import javax.annotation.PostConstruct;
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
    private final KrxSharedTradeThrottle sharedThrottle;

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
    // V34: positionCache [avgPrice, peakPrice, trailActivated(0/1), openedAtEpochMs, splitPhase]
    private final ConcurrentHashMap<String, double[]> positionCache = new ConcurrentHashMap<String, double[]>();
    // V34: TP_TRAIL + 티어드 SL + Split-Exit cached 변수 (DB에서 갱신)
    private volatile double cachedTpTrailActivatePct = 2.1;
    private volatile double cachedTpTrailDropPct = 1.5;
    private volatile double cachedSlPct = 2.0;        // Tight SL (wide_period 이후)
    private volatile double cachedWideSlPct = 2.0;    // Wide SL (grace 후 ~ wide_period)
    private volatile long cachedGracePeriodMs = 30_000L;
    private volatile long cachedWidePeriodMs = 10 * 60_000L;
    // V34: Split-Exit cached 변수
    private volatile boolean cachedSplitExitEnabled = true;
    private volatile double cachedSplitTpPct = 1.6;
    private volatile double cachedSplitRatio = 0.40;
    private volatile double cachedTrailDropAfterSplit = 1.5;
    private volatile KisWebSocketClient.PriceListener wsListener;
    // V34: race condition 방어 (WS + REST 동시 매도 방지)
    private final Set<String> sellingSymbols = ConcurrentHashMap.newKeySet();

    // V39 (2026-04-18) B1: SPLIT_1ST 반복 재시도 폭주 방지.
    // 운영 사고: 010170 (4/17 09:01~09:05) — placeSellOrder 미체결이 반복되면서
    //   REST 백업이 매 tick 마다 SPLIT_1ST 재시도 + "SELL EXECUTED" 거짓 decision 38건 발행.
    //   근본 원인은 (a) executeSplitFirstSell 실패 시에도 caller가 EXECUTED decision을 남김,
    //   (b) DB 롤백 후 splitPhase=0 복원되어 다음 tick에 즉시 재진입 허용.
    // 수정:
    //   1) executeSplitFirstSell 을 boolean 반환으로 변경, caller는 true일 때만 EXECUTED 기록.
    //   2) 실패 시각을 기록하고 SPLIT_1ST_COOLDOWN_MS 동안 재진입 차단.
    private final ConcurrentHashMap<String, Long> lastSplitFailMs = new ConcurrentHashMap<String, Long>();
    static final long SPLIT_1ST_COOLDOWN_MS = 30_000L;

    // ── 상태 머신 (V109: 시간 빈틈 버그 근본 해결) ──
    // 기존 boolean 플래그(rangeCollected, entryPhaseComplete) → Phase enum 전환
    // Phase 전환은 명시적으로만 발생, 시간 빈틈에 의한 데이터 삭제 불가
    enum Phase {
        IDLE,               // 운영시간 외 (데이터 없음)
        COLLECTING_RANGE,   // 08:50~09:00 레인지 수집 중
        RANGE_COLLECTED,    // 수집 완료, 진입 대기 중 (09:00:00~09:00:30)
        SCANNING,           // 09:00:30~09:10 진입 스캔 중
        MONITORING,         // 09:10~sessionEnd 보유 모니터링
        SESSION_END         // sessionEnd 이후 강제 청산
    }
    private volatile Phase currentPhase = Phase.IDLE;

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

    // V109 하위 호환: 기존 코드에서 참조하는 플래그 (Phase enum 기반으로 동작)
    private boolean rangeCollected() { return currentPhase.ordinal() >= Phase.RANGE_COLLECTED.ordinal(); }
    private boolean entryPhaseComplete() { return currentPhase.ordinal() >= Phase.MONITORING.ordinal(); }

    // 당일 매매 완료 종목 (익절/손절 후 재매수 방지, 2026-04-11 추가)
    // 모닝러쉬 원칙: 종목당 1회만 매수. 매도 후 같은 종목 재진입 금지.
    // 재시작 시 @PostConstruct에서 오늘 trade_log 기반으로 복원 (2026-04-17 #2)
    private final Set<String> tradedSymbols = ConcurrentHashMap.newKeySet();

    // #1 (2026-04-17): ORDER_NOT_FILLED retry drift guard.
    // 같은 심볼이 여러 번 buy 시도될 때 최초 시도 가격을 기록 → 재시도 시점에
    // 가격이 RETRY_PRICE_DRIFT_MAX 이상 올라갔으면 포기 (펌프 따라가기 방지).
    // 성공 매수 시 remove, IDLE 전환 시 clear.
    private final ConcurrentHashMap<String, Double> firstBuyAttemptPrice = new ConcurrentHashMap<String, Double>();
    static final double RETRY_PRICE_DRIFT_MAX = 0.02; // 2%

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
                                  com.example.stocks.db.KrxOvertimeRankLogRepository overtimeRankRepo,
                                  KrxSharedTradeThrottle sharedThrottle) {
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
        this.sharedThrottle = sharedThrottle;
    }

    // ========== Restore on restart (2026-04-17 #2) ==========

    /**
     * 앱 재시작 시 오늘(KST) 장중 거래된 모닝러쉬 종목을 tradedSymbols에 복원.
     * 목적: 장중 크래시/재배포 후 이미 매매 완료된 종목을 재매수하지 않기.
     *
     * 기준:
     * - tsEpochMs >= 오늘 KST 00:00
     * - patternType == ENTRY_STRATEGY ("KRX_MORNING_RUSH")
     * - 어떤 action이든 한 번이라도 기록된 심볼 → 재매수 금지
     */
    @PostConstruct
    public void restoreTradedSymbolsFromDb() {
        try {
            ZonedDateTime nowKst = ZonedDateTime.now(KST);
            long startMs = nowKst.toLocalDate().atStartOfDay(KST).toInstant().toEpochMilli();
            long endMs = System.currentTimeMillis();

            List<TradeEntity> todays = tradeLogRepo.findByTsEpochMsBetween(startMs, endMs);
            int count = 0;
            for (TradeEntity t : todays) {
                if (ENTRY_STRATEGY.equals(t.getPatternType()) && t.getSymbol() != null) {
                    if (tradedSymbols.add(t.getSymbol())) count++;
                }
            }
            if (count > 0) {
                log.info("[KrxMorningRush] tradedSymbols restored from DB: {} symbols (today's MR trades)",
                        tradedSymbols.size());
            }
        } catch (Exception e) {
            log.warn("[KrxMorningRush] tradedSymbols restore failed: {}", e.getMessage());
        }
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
        // V109: 모든 decision을 INFO 로그로 기록 (분석용)
        log.info("[KrxMorningRush] {} {} {} {} | {}", symbol, action, result, reasonCode, reason);
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
        currentPhase = Phase.IDLE;
        confirmCounts.clear();
        confirmPriceHistory.clear();
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
        int entryDelaySec = cfg.getEntryDelaySec();

        // ══════════════════════════════════════════════════════
        // 상태 머신 기반 Phase 전환 (V109: 시간 빈틈 버그 근본 해결)
        //
        // Phase 전환 규칙:
        //   IDLE → COLLECTING_RANGE : 08:50 도달
        //   COLLECTING_RANGE → RANGE_COLLECTED : collectRange() 완료
        //   RANGE_COLLECTED → SCANNING : 09:00 + entryDelaySec 도달
        //   SCANNING → MONITORING : 09:10 도달
        //   MONITORING → SESSION_END : sessionEnd 도달
        //   SESSION_END → IDLE : 다음 날 자동 (데이터 clear)
        //
        // 핵심: 데이터 clear는 IDLE 상태에서만 발생
        // 시간 빈틈이 있어도 phase가 유지되므로 데이터 삭제 불가
        // ══════════════════════════════════════════════════════

        // Update active position count
        int rushPosCount = 0;
        List<PositionEntity> allPos = positionRepo.findAll();
        for (PositionEntity pe : allPos) {
            if (ENTRY_STRATEGY.equals(pe.getEntryStrategy()) && pe.getQty() > 0) {
                rushPosCount++;
            }
        }
        activePositions = rushPosCount;

        // ── Phase 전환 판단 ──
        switch (currentPhase) {
            case IDLE:
                if (nowMinOfDay >= 8 * 60 + 50 && nowMinOfDay < 9 * 60) {
                    // IDLE → COLLECTING_RANGE
                    currentPhase = Phase.COLLECTING_RANGE;
                    confirmCounts.clear();
                    confirmPriceHistory.clear(); // 2026-04-17 #5: 어제 이력 잔존 방지
                    prevCloseMap.clear();
                    avgVolumeMap.clear();
                    tradedSymbols.clear();
                    firstBuyAttemptPrice.clear(); // 2026-04-17 #1
                    lastSplitFailMs.clear(); // V39 B1: 어제 실패 기록 잔존 방지
                    log.info("[KrxMorningRush] Phase: IDLE → COLLECTING_RANGE (08:50 도달, 신규 데이터 수집 시작)");
                } else {
                    statusText = "IDLE (outside hours)";
                    return;
                }
                break;

            case COLLECTING_RANGE:
                if (nowMinOfDay >= 9 * 60) {
                    // 09:00 넘으면 수집 시간 종료 → 자동 전환
                    if (!prevCloseMap.isEmpty()) {
                        currentPhase = Phase.RANGE_COLLECTED;
                        log.info("[KrxMorningRush] Phase: COLLECTING_RANGE → RANGE_COLLECTED (수집 완료, prevClose {}개, 진입 대기)",
                                prevCloseMap.size());
                    } else {
                        log.warn("[KrxMorningRush] 09:00 도달했지만 prevCloseMap 비어있음 — IDLE로 복귀");
                        currentPhase = Phase.IDLE;
                        return;
                    }
                }
                break;

            case RANGE_COLLECTED:
                // 09:00 + entryDelaySec 도달 → SCANNING
                if (nowMinOfDay > 9 * 60 || (nowMinOfDay == 9 * 60 && second >= entryDelaySec)) {
                    currentPhase = Phase.SCANNING;
                    log.info("[KrxMorningRush] Phase: RANGE_COLLECTED → SCANNING (진입 대기 완료, 스캔 시작)");
                } else {
                    statusText = "WAITING (entry delay)";
                    // 실시간 TP/SL 캐시는 업데이트 (기존 포지션 모니터링)
                    updateRealtimeCache(cfg);
                    return;
                }
                break;

            case SCANNING:
                if (nowMinOfDay >= 9 * 60 + 10) {
                    currentPhase = Phase.MONITORING;
                    log.info("[KrxMorningRush] Phase: SCANNING → MONITORING (09:10 도달, 진입 종료)");
                }
                break;

            case MONITORING:
                if (nowMinOfDay >= sessionEndMin) {
                    currentPhase = Phase.SESSION_END;
                    log.info("[KrxMorningRush] Phase: MONITORING → SESSION_END (세션 종료)");
                }
                break;

            case SESSION_END:
                if (rushPosCount > 0) {
                    statusText = "SESSION_END";
                    forceExitAll(cfg);
                }
                // 세션 종료 후 → IDLE로 복귀 (다음 날 대비)
                if (rushPosCount <= 0) {
                    currentPhase = Phase.IDLE;
                    log.info("[KrxMorningRush] Phase: SESSION_END → IDLE (모든 포지션 청산 완료)");
                }
                return;
        }

        statusText = currentPhase.name();

        // Throttle: only run trade logic at checkIntervalSec frequency
        long nowMs = System.currentTimeMillis();
        int intervalSec = cfg.getCheckIntervalSec();
        if (nowMs - lastTickEpochMs < intervalSec * 1000L) {
            return;
        }
        lastTickEpochMs = nowMs;

        // ── Phase별 실행 ──
        if (currentPhase == Phase.COLLECTING_RANGE) {
            collectRange(cfg);
        }

        if (currentPhase == Phase.SCANNING) {
            scanForEntry(cfg);
        }

        // 실시간 TP/SL 캐시 업데이트 (모든 활성 phase에서)
        updateRealtimeCache(cfg);

        // Monitor existing positions (REST fallback)
        if (rushPosCount > 0 && currentPhase.ordinal() >= Phase.SCANNING.ordinal()) {
            monitorPositions(cfg);
        }
    }

    // ========== Phase 1: Range Collection ==========

    private void collectRange(KrxMorningRushConfigEntity cfg) {
        if (currentPhase != Phase.COLLECTING_RANGE) return;

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

        // 상태 전환: COLLECTING_RANGE → RANGE_COLLECTED (mainLoop의 다음 tick에서 처리)
        // collectRange는 여러 번 호출될 수 있으므로 (throttle 간격마다) 여기서는 플래그만
        currentPhase = Phase.RANGE_COLLECTED;
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
            if (ownedSymbols.contains(symbol)) {
                addDecision(symbol, "BUY", "SKIPPED", "ALREADY_HELD", "이미 보유 중");
                continue;
            }
            if (rushPosCount >= cfg.getMaxPositions()) break;

            // 당일 매매 완료 종목 재매수 차단 (종목당 1회, 2026-04-11)
            if (tradedSymbols.contains(symbol)) {
                addDecision(symbol, "BUY", "SKIPPED", "ALREADY_TRADED", "당일 매매 완료 (재매수 금지)");
                continue;
            }

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
                    addDecision(symbol, "BUY", "SKIPPED", "PRICE_FETCH_FAIL",
                            "WS/REST 가격 조회 모두 실패");
                    continue;
                }
            }
            if (currentPrice <= 0) {
                addDecision(symbol, "BUY", "SKIPPED", "NO_PRICE", "현재가 0 (조회 불가)");
                continue;
            }

            // Min price filter
            if (currentPrice < cfg.getMinPriceKrw()) {
                addDecision(symbol, "BUY", "SKIPPED", "MIN_PRICE",
                        String.format("price=%.0f < minPrice=%d", currentPrice, cfg.getMinPriceKrw()));
                continue;
            }

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
            // V39 (2026-04-18) R5: 갭 과열 스킵 — gap ≥ 10% 이면 상한가 주자/차트 뒤쫓기 리스크.
            // 2026-04-17 184230 gap 17.63% → SPLIT_1ST 반복 트리거 손실 케이스.
            if (gapPct >= 0.10) {
                addDecision(symbol, "BUY", "SKIPPED", "GAP_OVERHEATED",
                        String.format("gap=%.2f%% >= 10%% price=%.0f prevClose=%.0f — 과열 구간",
                                gapPct * 100, currentPrice, prevClose));
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

            // ════════════════════════════════════════════════════════════════
            // #5 (2026-04-17): confirm 중 단조 감소(strictly decreasing) 감지 → 진입 스킵
            // ════════════════════════════════════════════════════════════════
            // 배경: 4/16 073540 — Confirm 1/3 @6265 → 2/3 @6230 → EXECUTED @6200
            //       3번 모두 하락 중인데 gap은 여전히 2% 이상 → 진입 허용 → 즉시 SL.
            // 정책: ascending(엄격한 상승)이 아니라 "단조 감소만 차단" — 출렁임(1014→968→1081)은 허용.
            if (prices.size() >= 2) {
                Double[] arr = prices.toArray(new Double[0]);
                boolean strictlyDecreasing = true;
                for (int i = 1; i < arr.length; i++) {
                    if (arr[i] >= arr[i - 1]) { strictlyDecreasing = false; break; }
                }
                if (strictlyDecreasing) {
                    addDecision(symbol, "BUY", "SKIPPED", "DOWNTREND_IN_CONFIRM",
                            String.format("Confirm prices %s strictly decreasing — skip (falling knife)",
                                    java.util.Arrays.toString(arr)));
                    confirmCounts.remove(symbol);
                    confirmPriceHistory.remove(symbol);
                    continue;
                }
            }

            // ════════════════════════════════════════════════════════════════
            // #1 (2026-04-17): ORDER_NOT_FILLED retry drift guard
            // ════════════════════════════════════════════════════════════════
            // 배경: 4/16 072950 — 09:04:14 "주문가능금액 초과" 실패 2회 → 자금 확보 후 09:04:41 체결
            //       시점엔 가격이 22500 → 25150 (+11.8%)로 폭등, 꼭지 잡음 → -3.38% SL.
            // 정책: 같은 심볼의 첫 시도 가격 대비 현재가가 RETRY_PRICE_DRIFT_MAX 이상 올라가면 포기.
            Double firstPrice = firstBuyAttemptPrice.get(symbol);
            if (firstPrice != null) {
                double drift = (currentPrice - firstPrice) / firstPrice;
                if (drift >= RETRY_PRICE_DRIFT_MAX) {
                    addDecision(symbol, "BUY", "SKIPPED", "RETRY_PRICE_DRIFT",
                            String.format("retry price %.0f drifted +%.2f%% from first attempt %.0f (cap %.2f%%)",
                                    currentPrice, drift * 100, firstPrice, RETRY_PRICE_DRIFT_MAX * 100));
                    firstBuyAttemptPrice.remove(symbol);
                    tradedSymbols.add(symbol); // 당일 재시도 완전 차단
                    confirmCounts.remove(symbol);
                    confirmPriceHistory.remove(symbol);
                    continue;
                }
            } else {
                firstBuyAttemptPrice.put(symbol, currentPrice);
            }

            // 확인 완료 — 로그 초기화
            confirmCounts.remove(symbol);
            prices.clear();

            // Execute BUY
            try {
                boolean bought = executeBuy(symbol, currentPrice, gapPct, cfg, orderAmount);
                if (bought) {
                    rushPosCount++;
                    tradedSymbols.add(symbol); // 당일 재매수 방지
                    firstBuyAttemptPrice.remove(symbol); // #1: 성공 시 retry 기록 삭제
                    addDecision(symbol, "BUY", "EXECUTED", "GAP_UP",
                            String.format("Gap %.2f%% price=%.0f prevClose=%.0f", gapPct * 100, currentPrice, prevClose));
                }
                // bought=false인 경우 executeBuy 내부에서 이미 BLOCKED/ERROR 로그 기록됨
            } catch (Exception e) {
                log.error("[KrxMorningRush] buy execution failed for {}", symbol, e);
                addDecision(symbol, "BUY", "ERROR", "EXECUTION_FAIL",
                        "Buy execution error: " + e.getMessage());
            }
        }
    }

    // ========== Position Monitoring (TP/SL/TimeStop) ==========

    /**
     * V40 (2026-04-20): REST 폴링 — peak 업데이트 + POS_STATUS 로그 + TIME_STOP 만 담당.
     * SL/TP 판정은 WebSocket realtime(checkRealtimeTpSl) 단독 책임.
     */
    private void monitorPositions(KrxMorningRushConfigEntity cfg) {
        List<PositionEntity> allPos = positionRepo.findAll();
        int timeStopMin = cfg.getTimeStopMin();

        for (PositionEntity pe : allPos) {
            if (!ENTRY_STRATEGY.equals(pe.getEntryStrategy()) || pe.getQty() <= 0) continue;

            double avgPrice = pe.getAvgPrice().doubleValue();
            if (avgPrice <= 0) continue;

            String symbol = pe.getSymbol();
            double currentPrice = kisWs.getLatestPrice(symbol);
            if (currentPrice <= 0) {
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

            double pnlPct = (currentPrice - avgPrice) / avgPrice * 100.0;

            long openedAtMs = pe.getOpenedAt() != null
                    ? pe.getOpenedAt().toEpochMilli() : System.currentTimeMillis();
            long elapsedMs = System.currentTimeMillis() - openedAtMs;
            long elapsedMinTotal = elapsedMs / 60_000L;

            // positionCache에서 peak/trail 상태 읽기
            double[] cached = positionCache.get(symbol);
            double peakPrice = cached != null ? cached[1] : avgPrice;
            boolean trailActivated = cached != null && cached[2] > 0;
            int splitPhase = pe.getSplitPhase();

            // peak 업데이트 (REST에서도)
            if (currentPrice > peakPrice) {
                peakPrice = currentPrice;
                if (cached != null) cached[1] = currentPrice;
            }

            addDecision(symbol, "MONITOR", "CHECK", "POS_STATUS",
                    String.format(Locale.ROOT, "price=%.0f avg=%.0f pnl=%.2f%% elapsed=%dmin split=%d trail=%s",
                            currentPrice, avgPrice, pnlPct, elapsedMinTotal, splitPhase, trailActivated));

            // V40 (2026-04-20): REST 경로는 TIME_STOP 만 담당.
            // SL/TP 는 WebSocket realtime(checkRealtimeTpSl) 단독 책임.
            // 제거 이유: Grace 종료 직후 REST 5초 폴링이 WS 다음 틱보다 먼저 SL 판정하여
            //            V자 반등 직전 -2~-3% 에서 조기 청산되는 패턴 관측됨.
            if (pe.getOpenedAt() == null || timeStopMin <= 0) continue;
            if (elapsedMinTotal < timeStopMin || pnlPct >= 0) continue;

            String sellReason = String.format(Locale.ROOT,
                    "TIME_STOP: %dmin elapsed (limit=%d), pnl=%.2f%% price=%.0f",
                    elapsedMinTotal, timeStopMin, pnlPct, currentPrice);

            // race 방어: WS realtime과 REST monitorPositions 동시 매도 방지
            if (!sellingSymbols.add(symbol)) {
                log.info("[KrxMorningRush] REST SELL skip, already in progress by another path (likely WS realtime): {} reason={}",
                        symbol, sellReason);
                continue;
            }

            log.info("[KrxMorningRush] REST path TIME_STOP triggered | {} | {}", symbol, sellReason);

            try {
                PositionEntity fresh = positionRepo.findById(symbol).orElse(null);
                if (fresh == null || fresh.getQty() <= 0) continue;

                boolean sold = executeSell(fresh, currentPrice, Signal.of(SignalAction.SELL, null, sellReason), cfg);
                if (sold) {
                    positionCache.remove(symbol);
                    addDecision(symbol, "SELL", "EXECUTED", "TIME_STOP", sellReason);
                }
            } catch (Exception e) {
                log.error("[KrxMorningRush] REST sell failed for {}", symbol, e);
                addDecision(symbol, "SELL", "ERROR", "SELL_FAIL", "REST 매도 실행 오류: " + e.getMessage());
            } finally {
                sellingSymbols.remove(symbol);
            }
        }
    }

    // ========== Force Exit All ==========

    private void forceExitAll(KrxMorningRushConfigEntity cfg) {
        List<PositionEntity> allPos = positionRepo.findAll();
        // V39 B2 (2026-04-18): SESSION_END 진입/결과 로그 강화.
        // 운영 사고: 4/14 SESSION_END 발생 후 8개 포지션 전량 청산됐지만 SELL 로그가 전혀 없음.
        //   executeSell 내부의 log.info 는 있으나 entry/exit 가 없어서 "실제 실행됐는지" 판별 불가.
        //   강화: 시작/종료/결과 카운트를 명시적 로깅하여 사건 재구성 가능하게 함.
        int target = 0, ok = 0, fail = 0, skipNoPrice = 0;
        for (PositionEntity pe : allPos) {
            if (ENTRY_STRATEGY.equals(pe.getEntryStrategy()) && pe.getQty() > 0) target++;
        }
        log.info("[KrxMorningRush] forceExitAll START target={} positions", target);

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
                    skipNoPrice++;
                    addDecision(pe.getSymbol(), "SELL", "ERROR", "SESSION_END",
                            "force exit price fetch failed: " + e.getMessage());
                    continue;
                }
            }
            if (currentPrice <= 0) {
                skipNoPrice++;
                addDecision(pe.getSymbol(), "SELL", "ERROR", "SESSION_END",
                        "현재가 0 — 청산 불가");
                continue;
            }

            // V34: splitPhase=1이면 SPLIT_SESSION_END note 구분
            String reason = pe.getSplitPhase() == 1
                    ? "SPLIT_SESSION_END: 2차 잔량 세션 종료 청산"
                    : "SESSION_END: KRX morning rush session closing";
            // #3 (2026-04-17): 성공한 건만 decision 로그에 EXECUTED 기록.
            log.info("[KrxMorningRush] forceExitAll TRY {} qty={} avg={} price={}",
                    pe.getSymbol(), pe.getQty(), pe.getAvgPrice(), currentPrice);
            boolean sold = executeSell(pe, currentPrice, Signal.of(SignalAction.SELL, null, reason), cfg);
            if (sold) {
                positionCache.remove(pe.getSymbol());
                addDecision(pe.getSymbol(), "SELL", "EXECUTED", "SESSION_END", reason);
                ok++;
            } else {
                fail++;
                log.warn("[KrxMorningRush] forceExitAll FAIL {} — position remains", pe.getSymbol());
            }
        }
        log.info("[KrxMorningRush] forceExitAll DONE target={} ok={} fail={} skipNoPrice={}",
                target, ok, fail, skipNoPrice);
    }

    // ========== Order Execution ==========

    /** @return true if buy succeeded, false if blocked/failed */
    private boolean executeBuy(final String symbol, final double price, double gapPct,
                             final KrxMorningRushConfigEntity cfg, BigDecimal orderAmount) {
        if (orderAmount.compareTo(BigDecimal.valueOf(50000)) < 0) {
            addDecision(symbol, "BUY", "BLOCKED", "ORDER_TOO_SMALL",
                    String.format("Order amount %s below minimum 50,000", orderAmount.toPlainString()));
            return false;
        }

        // V39 (2026-04-18) B3: 공유 throttle — MR + Opening Scanner 간 동일 종목 race 차단.
        // tryClaim = synchronized (canBuy + recordBuy). 실패 시 매수 중단.
        if (sharedThrottle != null && !sharedThrottle.tryClaim(symbol)) {
            long waitMs = sharedThrottle.remainingWaitMs(symbol);
            addDecision(symbol, "BUY", "BLOCKED", "THROTTLED",
                    String.format("Shared throttle: waitMs=%d (1h 2회/20분 쿨다운)", waitMs));
            return false;
        }
        boolean claimed = sharedThrottle != null;

        boolean isPaper = "PAPER".equalsIgnoreCase(cfg.getMode());
        final int qty;
        final double fillPrice;

        if (isPaper) {
            fillPrice = price * 1.001; // 0.1% slippage
            double fee = orderAmount.doubleValue() * 0.00015;
            qty = (int) ((orderAmount.doubleValue() - fee) / fillPrice);
            if (qty <= 0) {
                if (claimed) sharedThrottle.releaseClaim(symbol);
                addDecision(symbol, "BUY", "BLOCKED", "QTY_ZERO", "Calculated qty is 0");
                return false;
            }
        } else {
            int estQty = (int) (orderAmount.doubleValue() / price);
            if (estQty <= 0) {
                if (claimed) sharedThrottle.releaseClaim(symbol);
                addDecision(symbol, "BUY", "BLOCKED", "QTY_ZERO", "Estimated qty is 0");
                return false;
            }
            try {
                LiveOrderService.LiveOrderResult r = liveOrders.placeBuyOrder(symbol, MarketType.KRX, estQty, price);
                if (!r.isFilled()) {
                    if (claimed) sharedThrottle.releaseClaim(symbol);
                    addDecision(symbol, "BUY", "ERROR", "ORDER_NOT_FILLED",
                            String.format("Order not filled state=%s qty=%d", r.state, r.executedQty));
                    return false;
                }
                fillPrice = r.avgPrice > 0 ? r.avgPrice : price;
                qty = r.executedQty > 0 ? r.executedQty : estQty;
            } catch (Exception e) {
                if (claimed) sharedThrottle.releaseClaim(symbol);
                addDecision(symbol, "BUY", "ERROR", "ORDER_EXCEPTION", "Order failed: " + e.getMessage());
                return false;
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

        // V34: 실시간 TP_TRAIL + 티어드 SL + Split-Exit 캐시 등록
        // [avgPrice, peakPrice, activated, openedAtMs, splitPhase]
        positionCache.put(symbol, new double[]{fillPrice, fillPrice, 0, System.currentTimeMillis(), 0});
        cachedTpTrailActivatePct = cfg.getTpTrailActivatePct().doubleValue();
        cachedTpTrailDropPct = cfg.getTpTrailDropPct().doubleValue();
        cachedSlPct = cfg.getSlPct().doubleValue();
        cachedWideSlPct = cfg.getWideSlPct().doubleValue();
        cachedGracePeriodMs = cfg.getGracePeriodSec() * 1000L;
        cachedWidePeriodMs = cfg.getWidePeriodMin() * 60_000L;
        cachedSplitExitEnabled = cfg.isSplitExitEnabled();
        cachedSplitTpPct = cfg.getSplitTpPct().doubleValue();
        cachedSplitRatio = cfg.getSplitRatio().doubleValue();
        cachedTrailDropAfterSplit = cfg.getTrailDropAfterSplit().doubleValue();

        log.info("[KrxMorningRush] BUY {} mode={} price={} qty={}", symbol, cfg.getMode(), fillPrice, qty);
        return true;
    }

    /**
     * #3 (2026-04-17): executeSell returns boolean — true if DB commit succeeded (position deleted).
     * 호출자는 true인 경우에만 positionCache 에서 제거해야 함.
     * 주문 미체결/예외 상황에서 position 은 DB에 살아있으므로 cache 도 유지해야 realtime 재시도 가능.
     */
    private boolean executeSell(final PositionEntity pe, double price, Signal signal,
                              final KrxMorningRushConfigEntity cfg) {
        boolean isPaper = "PAPER".equalsIgnoreCase(cfg.getMode());
        final double fillPrice;
        final int qty = pe.getQty();

        if (isPaper) {
            fillPrice = price * 0.999;
        } else {
            if (!liveOrders.isConfigured()) {
                addDecision(pe.getSymbol(), "SELL", "BLOCKED", "API_KEY_MISSING", "LIVE mode API not configured");
                return false;
            }
            try {
                LiveOrderService.LiveOrderResult r = liveOrders.placeSellOrder(pe.getSymbol(), MarketType.KRX, qty, price);
                if (!r.isFilled()) {
                    addDecision(pe.getSymbol(), "SELL", "ERROR", "ORDER_NOT_FILLED",
                            String.format("Sell not filled state=%s qty=%d", r.state, r.executedQty));
                    return false;
                }
                fillPrice = r.avgPrice > 0 ? r.avgPrice : price;
            } catch (Exception e) {
                addDecision(pe.getSymbol(), "SELL", "ERROR", "ORDER_EXCEPTION", "Sell failed: " + e.getMessage());
                return false;
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

        try {
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
        } catch (Exception e) {
            // DB commit 실패 시 주문은 이미 체결됨 — 로그만 남기고 호출자는 cache 제거하지 않음.
            // 다음 재시도/재시작에서 reconcile 필요.
            log.error("[KrxMorningRush] SELL DB commit failed for {} (order was filled at {}): {}",
                    peSymbol, fillPrice, e.getMessage(), e);
            addDecision(peSymbol, "SELL", "ERROR", "DB_COMMIT_FAIL",
                    "Order filled but DB commit failed: " + e.getMessage());
            return false;
        }

        log.info("[KrxMorningRush] SELL {} price={} pnl={} roi={}% reason={}",
                peSymbol, fillPrice, String.format("%.0f", fPnlKrw),
                String.format("%.2f", roiPct), signalReason);
        return true;
    }

    // ========== V34: Split-Exit 1차 분할 매도 ==========

    /**
     * Split-Exit 1차 매도: 전체 수량의 ratio(40%) 매도, 나머지 보유.
     * 코인봇 executeSplitFirstSell과 동일 구조.
     *
     * Dust 처리: 잔량 * 가격 < 50,000원이면 전량 매도.
     * DB 실패 시: cache rollback (splitPhase=0으로 복원).
     *
     * V39 B1 (2026-04-18): boolean 반환 — caller 에서 EXECUTED decision 을 성공 시에만 기록.
     * @return true = 주문 체결 + DB 커밋 성공, false = 미체결/API 미설정/예외/DB 실패
     */
    private boolean executeSplitFirstSell(final PositionEntity pe, double price, String reason,
                                        final KrxMorningRushConfigEntity cfg) {
        final String symbol = pe.getSymbol();
        if (pe.getSplitPhase() != 0) {
            log.debug("[KrxMorningRush] SPLIT_1ST: already split for {} phase={}", symbol, pe.getSplitPhase());
            return false;
        }

        int totalQty = pe.getQty();
        double avgPrice = pe.getAvgPrice().doubleValue();
        double sellRatio = cfg.getSplitRatio().doubleValue();
        int sellQty = (int) Math.round(totalQty * sellRatio);
        if (sellQty <= 0) sellQty = 1;
        if (sellQty >= totalQty) sellQty = totalQty - 1;
        int remainQty = totalQty - sellQty;

        // Dust 체크: 잔량 가치 < 50,000원이면 전량 매도
        boolean isDust = remainQty <= 0 || remainQty * price < 50000;
        int actualSellQty = isDust ? totalQty : sellQty;

        boolean isPaper = "PAPER".equalsIgnoreCase(cfg.getMode());
        final double fillPrice;

        if (isPaper) {
            fillPrice = price * 0.999; // 0.1% slippage
        } else {
            if (!liveOrders.isConfigured()) {
                addDecision(symbol, "SELL", "BLOCKED", "SPLIT_1ST", "LIVE 모드 API 키 미설정");
                return false;
            }
            try {
                LiveOrderService.LiveOrderResult r = liveOrders.placeSellOrder(symbol, MarketType.KRX, actualSellQty, price);
                if (!r.isFilled()) {
                    addDecision(symbol, "SELL", "ERROR", "SPLIT_1ST",
                            String.format("매도 미체결 state=%s qty=%d", r.state, r.executedQty));
                    return false;
                }
                fillPrice = r.avgPrice > 0 ? r.avgPrice : price;
                if (r.executedQty > 0 && r.executedQty != actualSellQty) {
                    actualSellQty = r.executedQty;
                    remainQty = totalQty - actualSellQty;
                    isDust = remainQty <= 0 || remainQty * fillPrice < 50000;
                }
            } catch (Exception e) {
                log.error("[KrxMorningRush] SPLIT_1ST sell failed for {}", symbol, e);
                addDecision(symbol, "SELL", "ERROR", "SPLIT_1ST", "매도 실패: " + e.getMessage());
                return false;
            }
        }

        double pnlKrw = (fillPrice - avgPrice) * actualSellQty;
        double fee = fillPrice * actualSellQty * 0.00015;
        pnlKrw -= fee;
        double roiPct = avgPrice > 0 ? ((fillPrice - avgPrice) / avgPrice) * 100.0 : 0;

        final double fFillPrice = fillPrice;
        final double fPnlKrw = pnlKrw;
        final double fRoiPct = roiPct;
        final String fReason = reason;
        final boolean fIsDust = isDust;
        final int fActualSellQty = actualSellQty;
        final int fRemainQty = remainQty;

        try {
            txTemplate.execute(new TransactionCallbackWithoutResult() {
                @Override
                protected void doInTransactionWithoutResult(TransactionStatus status) {
                    TradeEntity tl = new TradeEntity();
                    tl.setTsEpochMs(System.currentTimeMillis());
                    tl.setSymbol(symbol);
                    tl.setMarketType("KRX");
                    tl.setAction("SELL");
                    tl.setPrice(fFillPrice);
                    tl.setQty(fActualSellQty);
                    tl.setPnlKrw(fPnlKrw);
                    tl.setRoiPercent(fRoiPct);
                    tl.setMode(cfg.getMode());
                    tl.setPatternType(ENTRY_STRATEGY);
                    tl.setPatternReason(fReason);
                    tl.setAvgBuyPrice(pe.getAvgPrice().doubleValue());
                    tl.setNote(fIsDust ? "SPLIT_1ST_DUST" : "SPLIT_1ST");
                    tl.setCurrency("KRW");
                    tl.setScannerSource(SCANNER_SOURCE);
                    tradeLogRepo.save(tl);

                    if (fIsDust) {
                        positionRepo.deleteById(symbol);
                    } else {
                        pe.setQty(fRemainQty);
                        pe.setSplitPhase(1);
                        pe.setSplitOriginalQty(totalQty);
                        positionRepo.save(pe);
                    }
                }
            });
        } catch (Exception e) {
            // DB 실패 시 cache rollback
            log.error("[KrxMorningRush] SPLIT_1ST DB commit failed for {} — cache rollback", symbol, e);
            double[] rollback = positionCache.get(symbol);
            if (rollback != null && rollback.length >= 5) {
                rollback[4] = 0;  // splitPhase → 0 복원
            }
            addDecision(symbol, "SELL", "ERROR", "SPLIT_1ST", "DB 실패, cache 롤백: " + e.getMessage());
            return false;
        }

        if (isDust) {
            positionCache.remove(symbol);
            log.info("[KrxMorningRush] SPLIT_1ST_DUST {} (전량, 잔량<50000원) price={} pnl={} roi={}%",
                    symbol, fFillPrice, Math.round(pnlKrw), String.format("%.2f", roiPct));
        } else {
            log.info("[KrxMorningRush] SPLIT_1ST {} qty={}/{} price={} pnl={} roi={}%",
                    symbol, actualSellQty, totalQty, fFillPrice,
                    Math.round(pnlKrw), String.format("%.2f", roiPct));
        }

        addDecision(symbol, "SELL", "EXECUTED", "SPLIT_1ST", fReason);
        return true;
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

    // ========== WebSocket 실시간 TP_TRAIL + 티어드 SL + Split-Exit (V34) ==========

    /**
     * V34: Split-Exit + TP_TRAIL + 티어드 SL 통합 실시간 체크 (코인봇 동일 구조).
     *
     * 우선순위:
     * 1. Split-Exit (splitExitEnabled)
     *    · splitPhase=0 + pnl >= splitTpPct → SPLIT_1ST (40% 매도, 60% 보유)
     *    · splitPhase=1 → breakeven(SPLIT_2ND_BEV) 또는 trail drop(SPLIT_2ND_TRAIL)
     * 2. TP_TRAIL (splitExit 비활성 시)
     *    · 수익 +2.1% 도달 → trail 활성, peak 대비 -1.5% drop 시 매도
     * 3. 티어드 SL (TP_TRAIL 미발동 시)
     *    · Grace 0~30초: SL 무시 (비상 -10%만)
     *    · Wide 30초~10분: SL_WIDE -2%
     *    · Tight 10분~: SL_TIGHT -2%
     *
     * positionCache: [avgPrice, peakPrice, trailActivated(0/1), openedAtEpochMs, splitPhase]
     */
    private void checkRealtimeTpSl(final String symbol, final double price) {
        if (!running.get()) return;

        double[] pos = positionCache.get(symbol);
        if (pos == null) return;

        double avgPrice = pos[0];
        if (avgPrice <= 0) return;
        double peakPrice = pos[1];
        boolean activated = pos[2] > 0;
        long openedAtMs = pos.length >= 4 ? (long) pos[3] : 0;
        int splitPhase = pos.length >= 5 ? (int) pos[4] : 0;

        double pnlPct = (price - avgPrice) / avgPrice * 100.0;
        long elapsedMs = openedAtMs > 0 ? System.currentTimeMillis() - openedAtMs : Long.MAX_VALUE;

        // 피크 업데이트
        if (price > peakPrice) {
            pos[1] = price;
            peakPrice = price;
        }

        String sellType = null;
        String reason = null;
        boolean isSplitFirst = false;

        // ━━━ V34: Split-Exit 1차 매도 (splitPhase=0, +splitTpPct 도달) ━━━
        // V39 B1: cooldown 체크 — REST 백업 실패가 누적되면 realtime 도 함께 억제
        Long lastFailMs = lastSplitFailMs.get(symbol);
        boolean splitOnCooldown = lastFailMs != null
                && (System.currentTimeMillis() - lastFailMs) < SPLIT_1ST_COOLDOWN_MS;
        if (cachedSplitExitEnabled && splitPhase == 0 && pnlPct >= cachedSplitTpPct && !splitOnCooldown) {
            sellType = "SPLIT_1ST";
            isSplitFirst = true;
            reason = String.format(java.util.Locale.ROOT,
                    "SPLIT_1ST pnl=+%.2f%% >= %.2f%% avg=%.0f now=%.0f ratio=%.0f%% (realtime)",
                    pnlPct, cachedSplitTpPct, avgPrice, price, cachedSplitRatio * 100);
        }
        // ━━━ V34: Split 2차 관리 (splitPhase=1) ━━━
        else if (cachedSplitExitEnabled && splitPhase == 1) {
            if (pnlPct <= 0) {
                sellType = "SPLIT_2ND_BEV";
                reason = String.format(java.util.Locale.ROOT,
                        "SPLIT_2ND_BEV pnl=%.2f%% <= 0%% (breakeven) avg=%.0f now=%.0f (realtime)",
                        pnlPct, avgPrice, price);
            } else if (peakPrice > avgPrice) {
                double dropFromPeakPct = (peakPrice - price) / peakPrice * 100.0;
                if (dropFromPeakPct >= cachedTrailDropAfterSplit) {
                    sellType = "SPLIT_2ND_TRAIL";
                    reason = String.format(java.util.Locale.ROOT,
                            "SPLIT_2ND_TRAIL avg=%.0f peak=%.0f now=%.0f drop=%.2f%% >= %.2f%% pnl=%.2f%% (realtime)",
                            avgPrice, peakPrice, price, dropFromPeakPct, cachedTrailDropAfterSplit, pnlPct);
                }
            }
        }
        // ━━━ 기존: splitExit 비활성 ━━━
        else {
            // TP_TRAIL 활성화 체크
            if (!activated && pnlPct >= cachedTpTrailActivatePct) {
                pos[2] = 1.0;
                activated = true;
                log.info("[KrxMorningRush] TP_TRAIL activated: {} pnl=+{} peak={} (realtime)",
                        symbol, String.format(java.util.Locale.ROOT, "%.2f%%", pnlPct), price);
            }

            // 분기: TP_TRAIL 활성 vs 티어드 SL
            if (activated) {
                double dropFromPeak = (peakPrice - price) / peakPrice * 100.0;
                if (dropFromPeak >= cachedTpTrailDropPct) {
                    sellType = "TP_TRAIL";
                    reason = String.format(java.util.Locale.ROOT,
                            "TP_TRAIL avg=%.0f peak=%.0f now=%.0f drop=%.2f%% pnl=%.2f%% (realtime)",
                            avgPrice, peakPrice, price, dropFromPeak, pnlPct);
                }
            }

            // 티어드 SL (TP_TRAIL 미발동 시)
            if (sellType == null) {
                if (elapsedMs < cachedGracePeriodMs) {
                    // Grace: SL 무시 (비상 -10%만)
                    if (pnlPct <= -10.0) {
                        sellType = "SL_EMERGENCY";
                        reason = String.format(java.util.Locale.ROOT,
                                "SL_EMERGENCY pnl=%.2f%% price=%.0f avg=%.0f (grace, realtime)", pnlPct, price, avgPrice);
                    }
                } else if (elapsedMs < cachedWidePeriodMs) {
                    if (pnlPct <= -cachedWideSlPct) {
                        sellType = "SL_WIDE";
                        reason = String.format(java.util.Locale.ROOT,
                                "SL_WIDE pnl=%.2f%% <= -%.2f%% price=%.0f avg=%.0f (realtime)",
                                pnlPct, cachedWideSlPct, price, avgPrice);
                    }
                } else {
                    if (pnlPct <= -cachedSlPct) {
                        sellType = "SL_TIGHT";
                        reason = String.format(java.util.Locale.ROOT,
                                "SL_TIGHT pnl=%.2f%% <= -%.2f%% price=%.0f avg=%.0f (realtime)",
                                pnlPct, cachedSlPct, price, avgPrice);
                    }
                }
            }
        }

        if (sellType == null) return;

        // race 방어
        // #4 (2026-04-17): debug → info 로 승격. 경로 명시로 REST/WS 어느 쪽이 먼저 잡았는지 추적.
        if (!sellingSymbols.add(symbol)) {
            log.info("[KrxMorningRush] WS realtime SELL skip, already in progress by another path (likely REST backup): {}",
                    symbol);
            return;
        }

        // #3 (2026-04-17): splitFirst가 아닌 일반 매도에서 positionCache 사전 제거 제거.
        // 이전 구현: WS realtime 경로에서 DB commit 전에 cache 먼저 제거 → LIVE 주문 실패 시 유령 포지션.
        // 수정: splitPhase 갱신만 여기서 하고, 일반 매도의 cache 제거는 executeSell 성공 후로 이동.
        if (isSplitFirst) {
            // V34: 1차 매도 — 캐시 유지, splitPhase=1로 갱신
            pos[4] = 1.0;    // splitPhase=1
            pos[1] = price;  // peak 리셋 (2차 trail 기준점)
        }

        final String fSellType = sellType;
        final String fReason = reason;
        final boolean fIsSplitFirst = isSplitFirst;
        log.info("[KrxMorningRush] realtime path {} detected | {} | {}", fSellType, symbol, fReason);

        tpSlExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    PositionEntity fresh = positionRepo.findById(symbol).orElse(null);
                    if (fresh == null || fresh.getQty() <= 0) return;
                    if (!ENTRY_STRATEGY.equals(fresh.getEntryStrategy())) return;
                    KrxMorningRushConfigEntity cfg = configRepo.loadOrCreate();
                    if (fIsSplitFirst) {
                        // V39 B1: 반환값 활용 — 실패 시 cooldown 기록 + cache 롤백
                        boolean splitOk = executeSplitFirstSell(fresh, price, fReason, cfg);
                        if (splitOk) {
                            lastSplitFailMs.remove(symbol);
                        } else {
                            lastSplitFailMs.put(symbol, System.currentTimeMillis());
                            // cache 롤백 (realtime 경로는 pos[4]=1.0 미리 설정했으므로 복원)
                            double[] p = positionCache.get(symbol);
                            if (p != null && p.length >= 5) p[4] = 0.0;
                            log.warn("[KrxMorningRush] SPLIT_1ST (realtime) failed for {} — cooldown {}ms 적용",
                                    symbol, SPLIT_1ST_COOLDOWN_MS);
                        }
                    } else {
                        boolean sold = executeSell(fresh, price, Signal.of(SignalAction.SELL, null, fReason), cfg);
                        if (sold) {
                            positionCache.remove(symbol);
                        }
                        // 실패 시 cache 유지 → 다음 tick에서 재시도 가능
                    }
                } catch (Exception e) {
                    log.error("[KrxMorningRush] realtime sell failed for {}", symbol, e);
                } finally {
                    sellingSymbols.remove(symbol);
                }
            }
        });
    }

    /**
     * mainLoop에서 호출: 포지션 캐시 + TP/SL 캐시 업데이트.
     */
    private void updateRealtimeCache(KrxMorningRushConfigEntity cfg) {
        // V34: TP_TRAIL + 티어드 SL + Split-Exit cached 변수 갱신
        cachedTpTrailActivatePct = cfg.getTpTrailActivatePct().doubleValue();
        cachedTpTrailDropPct = cfg.getTpTrailDropPct().doubleValue();
        cachedSlPct = cfg.getSlPct().doubleValue();
        cachedWideSlPct = cfg.getWideSlPct().doubleValue();
        cachedGracePeriodMs = cfg.getGracePeriodSec() * 1000L;
        cachedWidePeriodMs = cfg.getWidePeriodMin() * 60_000L;
        cachedSplitExitEnabled = cfg.isSplitExitEnabled();
        cachedSplitTpPct = cfg.getSplitTpPct().doubleValue();
        cachedSplitRatio = cfg.getSplitRatio().doubleValue();
        cachedTrailDropAfterSplit = cfg.getTrailDropAfterSplit().doubleValue();

        Set<String> current = new java.util.HashSet<String>();
        for (PositionEntity pe : positionRepo.findAll()) {
            if (ENTRY_STRATEGY.equals(pe.getEntryStrategy()) && pe.getQty() > 0) {
                current.add(pe.getSymbol());
                if (!positionCache.containsKey(pe.getSymbol())) {
                    double avg = pe.getAvgPrice().doubleValue();
                    long openedAt = pe.getOpenedAt() != null ? pe.getOpenedAt().toEpochMilli() : System.currentTimeMillis();
                    positionCache.put(pe.getSymbol(), new double[]{avg, avg, 0, openedAt, pe.getSplitPhase()});
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
