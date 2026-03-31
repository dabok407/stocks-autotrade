package com.example.stocks.bot;

import com.example.stocks.config.BotProperties;
import com.example.stocks.config.StrategyProperties;
import com.example.stocks.config.TradeProperties;
import com.example.stocks.db.*;
import com.example.stocks.kis.KisWebSocketClient;
import com.example.stocks.market.CandleService;
import com.example.stocks.market.MarketType;
import com.example.stocks.market.StockCandle;
import com.example.stocks.market.TickerService;
import com.example.stocks.strategy.*;
import com.example.stocks.trade.LiveOrderService;
import com.example.stocks.trade.TickSizeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 주식 자동매매 핵심 서비스.
 *
 * 정책:
 * - UI에서 start() 해야 tick이 돌며 자동매매 실행
 * - 시장 개장 시간에만 매매 (MarketCalendar 참조)
 * - PAPER 모드: 가상 체결 (슬리피지+수수료 반영)
 * - LIVE 모드: KIS API를 통한 실 주문
 *
 * 크립토 봇 대비 v1 간소화:
 * - 스캐너(오프닝/종일) 없음
 * - 전략 그룹 없음 (글로벌 설정만 사용)
 * - 정수 수량 (주식은 1주 단위)
 */
@Service
public class TradingBotService {

    private static final Logger log = LoggerFactory.getLogger(TradingBotService.class);

    private static double bd(BigDecimal v) { return v == null ? 0.0 : v.doubleValue(); }

    private final CandleService candleService;
    private final BotProperties botProps;
    private final StrategyProperties cfg;
    private final TradeProperties tradeProps;

    private final BotConfigRepository botConfigRepo;
    private final StockConfigRepository stockConfigRepo;
    private final PositionRepository positionRepo;
    private final TradeRepository tradeRepo;

    private final LiveOrderService liveOrders;
    private final StrategyFactory strategyFactory;
    private final TickerService tickerService;
    private final KisWebSocketClient wsClient;
    private final TransactionTemplate txTemplate;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean liveKeyVerified = false;
    private volatile int currentTickUnitMin = 5;
    private volatile long startedAt = 0L;
    private volatile boolean sellOnlyTick = false;

    // === Boundary scheduler ===
    private final ScheduledExecutorService boundaryExec =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "bot-boundary-scheduler");
                    t.setDaemon(true);
                    return t;
                }
            });

    // === Real-time TP/SL ticker (WebSocket 기반) ===
    private final ScheduledExecutorService tpSlExec =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "bot-tpsl-ticker");
                    t.setDaemon(true);
                    return t;
                }
            });
    private volatile ScheduledFuture<?> tpSlFuture;

    // Runtime knobs
    private volatile boolean boundarySchedulerEnabled = true;
    private volatile int boundaryBufferSeconds = 2;
    private volatile int boundaryMaxRetry = 5;
    private volatile int boundaryRetrySleepMs = 1000;
    private volatile int boundaryCatchUpMaxCandles = 3;
    private volatile int staleEntryTtlSeconds = 60;

    // Decision guard ring buffer
    private final Deque<DecisionLog> decisionLogs = new ArrayDeque<DecisionLog>();
    private final Object decisionLogLock = new Object();
    private volatile int decisionLogMaxSize = 200;

    // Per-symbol runtime state
    private final Map<String, SymbolState> states = new ConcurrentHashMap<String, SymbolState>();

    public TradingBotService(CandleService candleService,
                             BotProperties botProps,
                             StrategyProperties cfg,
                             TradeProperties tradeProps,
                             BotConfigRepository botConfigRepo,
                             StockConfigRepository stockConfigRepo,
                             PositionRepository positionRepo,
                             TradeRepository tradeRepo,
                             LiveOrderService liveOrders,
                             StrategyFactory strategyFactory,
                             TickerService tickerService,
                             KisWebSocketClient wsClient,
                             TransactionTemplate txTemplate) {
        this.candleService = candleService;
        this.botProps = botProps;
        this.cfg = cfg;
        this.tradeProps = tradeProps;
        this.botConfigRepo = botConfigRepo;
        this.stockConfigRepo = stockConfigRepo;
        this.positionRepo = positionRepo;
        this.tradeRepo = tradeRepo;
        this.liveOrders = liveOrders;
        this.strategyFactory = strategyFactory;
        this.tickerService = tickerService;
        this.wsClient = wsClient;
        this.txTemplate = txTemplate;

        refreshSymbolStates();
    }

    @PostConstruct
    public void init() {
        try {
            this.boundarySchedulerEnabled = botProps.isBoundarySchedulerEnabled();
            this.boundaryBufferSeconds = botProps.getBoundaryBufferSeconds();
            this.boundaryMaxRetry = botProps.getBoundaryMaxRetry();
            this.boundaryRetrySleepMs = botProps.getBoundaryRetrySleepMs();
            this.boundaryCatchUpMaxCandles = botProps.getCatchUpMaxCandles();
            this.staleEntryTtlSeconds = botProps.getStaleEntryTtlSeconds();
        } catch (Exception ignore) {
        }

        try {
            refreshSymbolStates();
        } catch (Exception e) {
            log.warn("[BOT] init symbol states failed: {}", e.getMessage());
        }

        if (boundarySchedulerEnabled) {
            startBoundaryScheduler();
        }
    }

    // =====================================================================
    // Start / Stop
    // =====================================================================

    public synchronized boolean start() {
        boolean changed = running.compareAndSet(false, true);
        if (changed) {
            this.startedAt = System.currentTimeMillis();
            log.info("[BOT] 자동매매 시작");

            // WebSocket 연결 & 활성 종목 구독
            try {
                wsClient.connect();
                subscribeActiveSymbols();
            } catch (Exception e) {
                log.warn("[BOT] WebSocket 연결 실패: {}", e.getMessage());
            }

            // 첫 tick: 매도만 체크
            boundaryExec.schedule(new Runnable() {
                @Override
                public void run() {
                    sellOnlyTick = true;
                    try {
                        log.info("[BOT] Start 즉시 - 매도 전략만 체크");
                        tickInternal(true);
                    } catch (Exception e) {
                        log.error("[BOT] 첫 tick 실패", e);
                    } finally {
                        sellOnlyTick = false;
                    }
                }
            }, 0, TimeUnit.MILLISECONDS);

            // TP/SL 티커 시작
            startTpSlTicker();
        }
        return changed;
    }

    public synchronized boolean stop() {
        boolean changed = running.compareAndSet(true, false);
        if (changed) {
            liveKeyVerified = false;
            sellOnlyTick = false;
            stopTpSlTicker();
            wsClient.disconnect();
            log.info("[BOT] 자동매매 중지");
        }
        return changed;
    }

    public boolean isRunning() {
        return running.get();
    }

    // =====================================================================
    // Decision Log
    // =====================================================================

    public static class DecisionLog {
        public long tsEpochMs;
        public String symbol;
        public Integer candleUnitMin;
        public String signalAction;
        public String result;
        public String reasonCode;
        public String reasonKo;
        public Map<String, Object> details = new LinkedHashMap<String, Object>();
    }

    public List<DecisionLog> getRecentDecisionLogs(int limit) {
        int lim = Math.max(1, Math.min(500, limit));
        synchronized (decisionLogLock) {
            List<DecisionLog> out = new ArrayList<DecisionLog>(Math.min(lim, decisionLogs.size()));
            Iterator<DecisionLog> it = decisionLogs.descendingIterator();
            while (it.hasNext() && out.size() < lim) out.add(it.next());
            return out;
        }
    }

    private void addDecisionLog(String symbol, Integer unit, String signalAction, String result,
                                String reasonCode, String reasonKo, Map<String, Object> details) {
        DecisionLog d = new DecisionLog();
        d.tsEpochMs = System.currentTimeMillis();
        d.symbol = symbol;
        d.candleUnitMin = unit;
        d.signalAction = signalAction;
        d.result = result;
        d.reasonCode = reasonCode;
        d.reasonKo = reasonKo;
        if (details != null) d.details.putAll(details);

        synchronized (decisionLogLock) {
            decisionLogs.addLast(d);
            while (decisionLogs.size() > decisionLogMaxSize) decisionLogs.removeFirst();
        }
    }

    // =====================================================================
    // Symbol state
    // =====================================================================

    private static class SymbolState {
        final String symbol;
        int downStreak;
        String lastCandleUtc;
        double lastPrice;
        double peakHighSinceEntry;

        SymbolState(String symbol) {
            this.symbol = symbol;
        }
    }

    public synchronized void refreshSymbolStates() {
        List<StockConfigEntity> all = stockConfigRepo.findAll();
        for (StockConfigEntity sc : all) {
            if (!states.containsKey(sc.getSymbol())) {
                states.put(sc.getSymbol(), new SymbolState(sc.getSymbol()));
            }
        }
        Set<String> existing = new HashSet<String>();
        for (StockConfigEntity sc : all) existing.add(sc.getSymbol());
        Iterator<String> it = states.keySet().iterator();
        while (it.hasNext()) {
            if (!existing.contains(it.next())) it.remove();
        }
    }

    private void subscribeActiveSymbols() {
        List<StockConfigEntity> enabled = stockConfigRepo.findByEnabledTrue();
        for (StockConfigEntity sc : enabled) {
            MarketType mt = parseMarketType(sc.getMarketType());
            boolean isOverseas = (mt == MarketType.NYSE || mt == MarketType.NASDAQ);
            String wsKey = isOverseas ? resolveExchangeCode(mt) + "." + sc.getSymbol() : sc.getSymbol();
            wsClient.subscribe(wsKey, isOverseas);
        }
    }

    // =====================================================================
    // TP/SL Ticker (WebSocket-based real-time monitoring)
    // =====================================================================

    private void startTpSlTicker() {
        if (!botProps.isTpSlTickerEnabled()) {
            log.info("[TP/SL TICKER] 비활성화 (bot.tpSlTickerEnabled=false)");
            return;
        }
        int intervalSec = botProps.getTpSlPollIntervalSeconds();
        log.info("[TP/SL TICKER] 시작 - {}초 간격 모니터링", intervalSec);
        tpSlFuture = tpSlExec.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    tickTpSlFromWebSocket();
                } catch (Exception e) {
                    log.debug("[TP/SL TICKER] 예외: {}", e.getMessage());
                }
            }
        }, intervalSec, intervalSec, TimeUnit.SECONDS);
    }

    private void stopTpSlTicker() {
        if (tpSlFuture != null) {
            tpSlFuture.cancel(false);
            tpSlFuture = null;
            log.info("[TP/SL TICKER] 중지");
        }
    }

    private void tickTpSlFromWebSocket() {
        if (!running.get()) return;

        BotConfigEntity bc = getBotConfig();
        double tpPctVal = bd(bc.getTakeProfitPct());
        double slPctVal = bd(bc.getStopLossPct());
        double trailingStopPctVal = bd(bc.getTrailingStopPct());
        final String mode = bc.getMode() == null ? "PAPER" : bc.getMode().toUpperCase();

        List<StockConfigEntity> enabled = stockConfigRepo.findByEnabledTrue();
        for (StockConfigEntity sc : enabled) {
            String symbol = sc.getSymbol();
            PositionEntity pe = positionRepo.findById(symbol).orElse(null);
            if (pe == null || pe.getQty() <= 0) continue;

            MarketType mt = parseMarketType(sc.getMarketType());
            boolean isOverseas = (mt == MarketType.NYSE || mt == MarketType.NASDAQ);
            String wsKey = isOverseas ? resolveExchangeCode(mt) + "." + symbol : symbol;

            double tickerPrice = wsClient.getLatestPrice(wsKey);
            if (tickerPrice <= 0) continue;

            SymbolState st = states.get(symbol);
            if (st != null) st.lastPrice = tickerPrice;

            double avgPrice = bd(pe.getAvgPrice());
            if (avgPrice <= 0) continue;

            // LIVE: pending 주문 있으면 스킵
            if ("LIVE".equals(mode) && liveOrders.isConfigured() && liveOrders.hasPendingOrder(symbol)) {
                continue;
            }

            SignalEvaluator.Result tpSlResult = SignalEvaluator.checkTpSl(true, avgPrice, tickerPrice, tpPctVal, slPctVal);

            // 트레일링 스탑
            if (tpSlResult == null && trailingStopPctVal > 0 && st != null) {
                if (tickerPrice > st.peakHighSinceEntry) {
                    st.peakHighSinceEntry = tickerPrice;
                }
                double peakHigh = st.peakHighSinceEntry;
                if (peakHigh > avgPrice && tickerPrice > avgPrice) {
                    double trailStop = peakHigh * (1.0 - trailingStopPctVal / 100.0);
                    if (tickerPrice <= trailStop) {
                        double pnlPct = ((tickerPrice - avgPrice) / avgPrice) * 100.0;
                        String reason = String.format(Locale.ROOT,
                                "TRAILING_STOP peak=%.2f trail=%.2f pnl=%.2f%% [ws]",
                                peakHigh, trailStop, pnlPct);
                        Signal sig = Signal.of(SignalAction.SELL, null, reason);
                        tpSlResult = new SignalEvaluator.Result(sig, null, "TRAILING_STOP", reason, true);
                    }
                }
            }

            if (tpSlResult != null) {
                // 재확인
                PositionEntity recheck = positionRepo.findById(symbol).orElse(null);
                if (recheck == null || recheck.getQty() <= 0) continue;

                final String tpSlType = tpSlResult.patternType;
                final String tpSlReason = tpSlResult.reason + " [ws]";
                final int qty = recheck.getQty();

                log.info("[TP/SL WS] {} | {} | 평단:{} -> 현재:{} | {}",
                        tpSlType, symbol,
                        String.format("%.2f", avgPrice), String.format("%.2f", tickerPrice), tpSlReason);

                executeSell(mode, symbol, mt, qty, tickerPrice, avgPrice, tpSlType, tpSlReason, st);
            }
        }
    }

    // =====================================================================
    // Boundary Scheduler
    // =====================================================================

    private void startBoundaryScheduler() {
        if (!boundarySchedulerEnabled) return;
        boundaryExec.schedule(new Runnable() {
            @Override
            public void run() {
                scheduleNextBoundary();
            }
        }, 0, TimeUnit.MILLISECONDS);
    }

    private void scheduleNextBoundary() {
        try {
            long delayMs = computeDelayToNextCandleCloseMs();
            log.debug("[SCHEDULER] 다음 tick 예약: {}초 후", delayMs / 1000);
            boundaryExec.schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        tickInternal(true);
                    } catch (Exception e) {
                        log.error("[TICK] 예외 발생", e);
                    } finally {
                        scheduleNextBoundary();
                    }
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("[SCHEDULER] 스케줄링 실패, 5초 후 재시도", e);
            boundaryExec.schedule(new Runnable() {
                @Override
                public void run() {
                    scheduleNextBoundary();
                }
            }, 5000, TimeUnit.MILLISECONDS);
        }
    }

    private long computeDelayToNextCandleCloseMs() {
        BotConfigEntity bc = getBotConfig();
        int unit = bc.getCandleUnitMin();
        if (unit <= 0) unit = 5;

        Instant now = Instant.now();
        long epochMin = now.getEpochSecond() / 60L;
        long nextBoundaryMin = ((epochMin / unit) + 1L) * unit;
        Instant nextBoundary = Instant.ofEpochSecond(nextBoundaryMin * 60L);

        long delay = Duration.between(now, nextBoundary).toMillis();
        long buffer = Math.max(0, boundaryBufferSeconds) * 1000L;
        delay += buffer;

        long minDelay = 200L;
        if (delay < minDelay) delay += (long) unit * 60_000L;

        return delay;
    }

    // =====================================================================
    // Tick logic
    // =====================================================================

    private void tickInternal(boolean boundaryAligned) {
        if (!running.get()) return;

        BotConfigEntity bc = getBotConfig();
        int unit = bc.getCandleUnitMin();
        currentTickUnitMin = unit;
        final String mode = bc.getMode() == null ? "PAPER" : bc.getMode().toUpperCase();
        MarketType activeMarketType = parseMarketType(bc.getMarketType());

        // 시장 개장 체크
        ZonedDateTime now = ZonedDateTime.now(activeMarketType.timezone());
        if (!MarketCalendar.isMarketOpen(now, activeMarketType)) {
            log.debug("[TICK] 시장 폐장 중 ({}) - 스킵", activeMarketType);
            return;
        }

        List<StockConfigEntity> enabled = stockConfigRepo.findByEnabledTrue();
        if (enabled.isEmpty()) {
            log.warn("[TICK] 활성화된 종목이 없습니다.");
            return;
        }

        log.info("[TICK] {}분봉 | {} 모드 | {} | 활성 종목: {} | sellOnly={}",
                unit, mode, activeMarketType, enabled.size(), sellOnlyTick);

        // LIVE: API 키 검증
        if ("LIVE".equals(mode) && liveOrders.isConfigured() && !liveKeyVerified) {
            try {
                liveKeyVerified = true;
                log.info("[LIVE] API 키 검증 성공");
            } catch (Exception e) {
                log.error("[LIVE] API 키 검증 실패: {}", e.getMessage());
                addDecisionLog(null, unit, "SYSTEM", "BLOCKED",
                        "API_KEY_INVALID",
                        "LIVE 모드 API 키 검증 실패. KIS 설정을 확인하세요.",
                        null);
            }
        }

        double tpPctVal = bd(bc.getTakeProfitPct());
        double slPctVal = bd(bc.getStopLossPct());
        List<StrategyType> activeStrats = parseActiveStrategyTypes(bc);

        for (StockConfigEntity sc : enabled) {
            final String symbol = sc.getSymbol();
            MarketType mt = parseMarketType(sc.getMarketType());

            // 현재 활성 시장과 다른 시장의 종목은 스킵
            if (mt != activeMarketType) continue;

            try {
                SymbolState st = states.get(symbol);
                if (st == null) {
                    st = new SymbolState(symbol);
                    states.put(symbol, st);
                }

                // sellOnly: 포지션 없으면 스킵
                if (sellOnlyTick) {
                    PositionEntity sellCheckPos = positionRepo.findById(symbol).orElse(null);
                    if (sellCheckPos == null || sellCheckPos.getQty() <= 0) {
                        continue;
                    }
                }

                // LIVE: pending 주문 체크
                if ("LIVE".equals(mode) && liveOrders.isConfigured() && liveOrders.hasPendingOrder(symbol)) {
                    log.info("[{}] pending 주문 존재 -> 스킵", symbol);
                    continue;
                }

                // 캔들 조회
                List<StockCandle> candles = candleService.getMinuteCandles(symbol, mt, unit, 2, null);
                if (candles == null || candles.size() < 2) {
                    log.warn("[{}] 캔들 조회 실패 ({}개)", symbol, candles == null ? 0 : candles.size());
                    continue;
                }

                StockCandle older = candles.get(0);
                StockCandle latest = candles.get(1);
                if (older.candle_date_time_utc != null && latest.candle_date_time_utc != null
                        && older.candle_date_time_utc.compareTo(latest.candle_date_time_utc) > 0) {
                    StockCandle tmp = older;
                    older = latest;
                    latest = tmp;
                }

                st.lastPrice = latest.trade_price;

                // 새 캔들 체크
                if (latest.candle_date_time_utc == null) continue;
                if (latest.candle_date_time_utc.equals(st.lastCandleUtc)) {
                    log.debug("[{}] 동일 봉 -> 스킵", symbol);
                    continue;
                }

                log.info("[{}] 새 캔들 | {} | 종가:{}", symbol, latest.candle_date_time_utc,
                        String.format("%.2f", latest.trade_price));

                double close = latest.trade_price;
                double prevClose = older.trade_price;

                // 연속하락 카운트
                if (close < prevClose) st.downStreak++;
                else {
                    st.downStreak = 0;
                    st.peakHighSinceEntry = 0.0;
                }

                st.lastCandleUtc = latest.candle_date_time_utc;

                // Stale entry guard
                boolean staleEntry = isStaleForEntry(latest, unit);

                PositionEntity pe = positionRepo.findById(symbol).orElse(null);

                // === STEP 1: TP/SL ===
                boolean openForTpSl = pe != null && pe.getQty() > 0;
                double avgForTpSl = openForTpSl ? bd(pe.getAvgPrice()) : 0;

                SignalEvaluator.Result tpSlResult = SignalEvaluator.checkTpSl(openForTpSl, avgForTpSl, close, tpPctVal, slPctVal);
                if (tpSlResult != null) {
                    String tpSlType = tpSlResult.patternType;
                    String tpSlReason = tpSlResult.reason;
                    log.info("[{}] {} | 평단:{} -> 현재:{} | {}", symbol, tpSlType,
                            String.format("%.2f", avgForTpSl), String.format("%.2f", close), tpSlReason);
                    executeSell(mode, symbol, mt, pe.getQty(), close, avgForTpSl, tpSlType, tpSlReason, st);
                    continue;
                }

                // === STEP 1.5: Time Stop ===
                int timeStopMin = bc.getTimeStopMinutes();
                if (timeStopMin > 0 && openForTpSl && pe.getOpenedAt() != null) {
                    long elapsedMs = System.currentTimeMillis() - pe.getOpenedAt().toEpochMilli();
                    long elapsedMin = elapsedMs / 60000L;
                    if (elapsedMin >= timeStopMin) {
                        boolean isBuyOnlyEntry = false;
                        String entryStrat = pe.getEntryStrategy();
                        if (entryStrat != null && !entryStrat.isEmpty()) {
                            try {
                                isBuyOnlyEntry = StrategyType.valueOf(entryStrat).isBuyOnly();
                            } catch (Exception ignore) {
                            }
                        }
                        if (isBuyOnlyEntry) {
                            double pnlPct = avgForTpSl > 0 ? ((close - avgForTpSl) / avgForTpSl) * 100.0 : 0;
                            if (pnlPct < 0) {
                                String tsReason = String.format(Locale.ROOT,
                                        "TIME_STOP %dmin elapsed=%dmin entry=%s pnl=%.2f%%",
                                        timeStopMin, elapsedMin, entryStrat, pnlPct);
                                log.info("[{}] Time Stop | {}분 경과 + 손실 {}%", symbol, elapsedMin, String.format("%.2f", pnlPct));
                                executeSell(mode, symbol, mt, pe.getQty(), close, avgForTpSl, "TIME_STOP", tsReason, st);
                                continue;
                            }
                        }
                    }
                }

                // === STEP 2: 전략 평가 ===
                boolean needsLargeWindow = activeStrats.contains(StrategyType.REGIME_PULLBACK);
                int windowSize = needsLargeWindow ? 450 : 200;

                List<StockCandle> window = candleService.getMinuteCandles(symbol, mt, unit, windowSize, null);
                if (window == null || window.size() < 5) {
                    log.debug("[{}] 전략 평가용 캔들 부족 ({}개)", symbol, window == null ? 0 : window.size());
                    continue;
                }
                Collections.sort(window, new Comparator<StockCandle>() {
                    @Override
                    public int compare(StockCandle a, StockCandle b) {
                        if (a.candle_date_time_utc == null && b.candle_date_time_utc == null) return 0;
                        if (a.candle_date_time_utc == null) return -1;
                        if (b.candle_date_time_utc == null) return 1;
                        return a.candle_date_time_utc.compareTo(b.candle_date_time_utc);
                    }
                });

                // EMA 트렌드 필터 맵
                Map<String, Integer> emaTrendFilterMap = new HashMap<String, Integer>();
                for (StrategyType stype : activeStrats) {
                    emaTrendFilterMap.put(stype.name(), stype.recommendedEmaPeriod());
                }

                StrategyContext ctx = new StrategyContext(symbol, unit, window, pe, st.downStreak, emaTrendFilterMap);
                SignalEvaluator.Result evalResult = SignalEvaluator.evaluateStrategies(activeStrats, strategyFactory, ctx);

                if (evalResult == null || evalResult.isEmpty()) {
                    log.info("[{}] 신호 없음 | 전략: {}", symbol, activeStrats.size() > 3 ? activeStrats.size() + "개" : activeStrats.toString());
                    continue;
                }

                Signal chosen = evalResult.signal;
                log.info("[{}] 신호 감지 | {} -> {} | {}", symbol, evalResult.patternType, chosen.action, evalResult.reason);

                // BUY / ADD_BUY guard
                if (chosen.action == SignalAction.BUY || chosen.action == SignalAction.ADD_BUY) {
                    if (sellOnlyTick) {
                        log.info("[{}] SELL_ONLY_TICK - 매수 스킵", symbol);
                        addDecisionLog(symbol, unit, chosen.action.name(), "SKIPPED",
                                "SELL_ONLY_TICK", "Start 직후 매도 전용 tick", null);
                        continue;
                    }
                    if (staleEntry) {
                        log.info("[{}] STALE_ENTRY_BLOCK - 매수 차단 (캔들 지연)", symbol);
                        addDecisionLog(symbol, unit, chosen.action.name(), "BLOCKED",
                                "STALE_ENTRY_BLOCK", "캔들 지연으로 매수 차단", null);
                        continue;
                    }

                    // 폐장 임박 시 매수 차단 (30분 미만)
                    long minutesToClose = MarketCalendar.minutesToClose(ZonedDateTime.now(mt.timezone()), mt);
                    if (minutesToClose > 0 && minutesToClose < 30) {
                        log.info("[{}] 폐장 임박 ({}분) - 매수 차단", symbol, minutesToClose);
                        addDecisionLog(symbol, unit, chosen.action.name(), "BLOCKED",
                                "MARKET_CLOSE_SOON", "폐장 " + minutesToClose + "분 전 - 매수 차단", null);
                        continue;
                    }

                    // Min confidence guard
                    double minConf = bc.getMinConfidence();
                    if (minConf > 0 && chosen.confidence > 0 && chosen.confidence < minConf) {
                        log.info("[{}] 신뢰도 부족 ({} < {}) - 매수 차단", symbol,
                                String.format("%.1f", chosen.confidence), String.format("%.1f", minConf));
                        addDecisionLog(symbol, unit, chosen.action.name(), "BLOCKED",
                                "LOW_CONFIDENCE", "신뢰도 " + String.format("%.1f", chosen.confidence) + " < " + String.format("%.1f", minConf), null);
                        continue;
                    }
                }

                // === STEP 3: 주문 실행 ===
                if (chosen.action == SignalAction.SELL) {
                    if (pe == null || pe.getQty() <= 0) {
                        log.debug("[{}] SELL 신호이나 포지션 없음", symbol);
                        continue;
                    }
                    // 전략 락: entryStrategy와 다르면 매도 차단
                    if (bc.isStrategyLock() && evalResult.strategyType != null && pe.getEntryStrategy() != null) {
                        if (!evalResult.strategyType.name().equals(pe.getEntryStrategy())) {
                            log.info("[{}] STRATEGY_LOCK - {} != {} -> 매도 차단", symbol,
                                    evalResult.strategyType.name(), pe.getEntryStrategy());
                            continue;
                        }
                    }
                    executeSell(mode, symbol, mt, pe.getQty(), close, bd(pe.getAvgPrice()),
                            evalResult.patternType, evalResult.reason, st);

                } else if (chosen.action == SignalAction.BUY) {
                    if (pe != null && pe.getQty() > 0) {
                        log.debug("[{}] BUY 신호이나 이미 포지션 보유", symbol);
                        continue;
                    }
                    executeBuy(mode, symbol, mt, close, bc, evalResult, st);

                } else if (chosen.action == SignalAction.ADD_BUY) {
                    if (pe == null || pe.getQty() <= 0) {
                        log.debug("[{}] ADD_BUY 신호이나 포지션 없음", symbol);
                        continue;
                    }
                    if (pe.getAddBuys() >= bc.getMaxAddBuysGlobal()) {
                        log.info("[{}] 추가매수 한도 초과 ({}/{})", symbol, pe.getAddBuys(), bc.getMaxAddBuysGlobal());
                        continue;
                    }
                    executeAddBuy(mode, symbol, mt, close, pe, bc, evalResult, st);
                }

            } catch (Exception e) {
                log.error("[{}] tick 처리 실패: {}", symbol, e.getMessage(), e);
            }
        }
    }

    // =====================================================================
    // Order execution helpers
    // =====================================================================

    private void executeSell(final String mode, final String symbol, final MarketType mt,
                             final int qty, double price, final double avgPrice,
                             final String patternType, final String reason,
                             SymbolState st) {
        if ("PAPER".equals(mode)) {
            final double fill = price * (1.0 - tradeProps.getSlippageRate());
            final double gross = qty * fill;
            final double fee = gross * cfg.getFeeRate();
            final double realized = (gross - fee) - (qty * avgPrice);
            txTemplate.execute(new TransactionCallbackWithoutResult() {
                @Override
                protected void doInTransactionWithoutResult(TransactionStatus status) {
                    persistTrade(mode, symbol, "SELL", fill, qty, realized, 0.0, reason, patternType, reason, avgPrice);
                    positionRepo.deleteById(symbol);
                }
            });
        } else {
            if (!liveOrders.isConfigured()) {
                log.error("[{}] LIVE 모드인데 KIS 키가 없습니다", symbol);
                return;
            }
            double tickPrice = TickSizeUtil.roundToTickSize(price, mt);
            final LiveOrderService.LiveOrderResult r = liveOrders.placeSellOrder(symbol, mt, qty, tickPrice);
            if (!r.isFilled()) {
                persistTrade(mode, symbol, "SELL_PENDING", price, qty, 0.0, 0.0,
                        "state=" + r.state, patternType, reason, avgPrice);
                return;
            }
            final double fill = r.avgPrice > 0 ? r.avgPrice : price;
            final double gross = qty * fill;
            final double fee = gross * cfg.getFeeRate();
            final double realized = (gross - fee) - (qty * avgPrice);
            txTemplate.execute(new TransactionCallbackWithoutResult() {
                @Override
                protected void doInTransactionWithoutResult(TransactionStatus status) {
                    persistTrade(mode, symbol, "SELL", fill, qty, realized, 0.0,
                            reason + " ordNo=" + r.ordNo, patternType, reason, avgPrice);
                    positionRepo.deleteById(symbol);
                }
            });
        }
        if (st != null) {
            st.downStreak = 0;
            st.peakHighSinceEntry = 0.0;
        }
    }

    private void executeBuy(final String mode, final String symbol, final MarketType mt, double price,
                            BotConfigEntity bc, SignalEvaluator.Result evalResult, SymbolState st) {
        double baseOrderKrw = resolveBaseOrderKrw(bc);
        int qty = computeQty(baseOrderKrw, price);
        if (qty <= 0) {
            log.info("[{}] 주문금액({})으로 1주도 못 삼 (가격={})", symbol, String.format("%.0f", baseOrderKrw), String.format("%.2f", price));
            return;
        }

        final String patternType = evalResult.patternType;
        final String reason = evalResult.reason;
        final double confidence = evalResult.confidence;
        final String strategyName = evalResult.strategyType != null ? evalResult.strategyType.name() : patternType;

        if ("PAPER".equals(mode)) {
            final double fill = price * (1.0 + tradeProps.getSlippageRate());
            final int fQty = qty;
            txTemplate.execute(new TransactionCallbackWithoutResult() {
                @Override
                protected void doInTransactionWithoutResult(TransactionStatus status) {
                    persistTrade(mode, symbol, "BUY", fill, fQty, 0.0, confidence, reason, patternType, reason, 0.0);

                    PositionEntity pos = new PositionEntity();
                    pos.setSymbol(symbol);
                    pos.setMarketType(mt.name());
                    pos.setQty(fQty);
                    pos.setAvgPrice(fill);
                    pos.setAddBuys(0);
                    pos.setOpenedAt(Instant.now());
                    pos.setUpdatedAt(Instant.now());
                    pos.setEntryStrategy(strategyName);
                    positionRepo.save(pos);
                }
            });
        } else {
            if (!liveOrders.isConfigured()) {
                log.error("[{}] LIVE 모드인데 KIS 키가 없습니다", symbol);
                return;
            }
            double tickPrice = TickSizeUtil.roundToTickSize(price, mt);
            final LiveOrderService.LiveOrderResult r = liveOrders.placeBuyOrder(symbol, mt, qty, tickPrice);
            if (!r.isFilled()) {
                persistTrade(mode, symbol, "BUY_PENDING", price, qty, 0.0, confidence,
                        "state=" + r.state, patternType, reason, 0.0);
                return;
            }
            final double fill = r.avgPrice > 0 ? r.avgPrice : price;
            final int fQty = r.executedQty > 0 ? r.executedQty : qty;
            txTemplate.execute(new TransactionCallbackWithoutResult() {
                @Override
                protected void doInTransactionWithoutResult(TransactionStatus status) {
                    persistTrade(mode, symbol, "BUY", fill, fQty, 0.0, confidence,
                            reason + " ordNo=" + r.ordNo, patternType, reason, 0.0);

                    PositionEntity pos = new PositionEntity();
                    pos.setSymbol(symbol);
                    pos.setMarketType(mt.name());
                    pos.setQty(fQty);
                    pos.setAvgPrice(fill);
                    pos.setAddBuys(0);
                    pos.setOpenedAt(Instant.now());
                    pos.setUpdatedAt(Instant.now());
                    pos.setEntryStrategy(strategyName);
                    positionRepo.save(pos);
                }
            });
        }
        if (st != null) st.peakHighSinceEntry = price;
        log.info("[{}] BUY 체결 | {}주 @ {} | {}", symbol, qty, String.format("%.2f", price), reason);
    }

    private void executeAddBuy(final String mode, final String symbol, final MarketType mt, double price,
                                final PositionEntity pe, BotConfigEntity bc,
                                SignalEvaluator.Result evalResult, SymbolState st) {
        double baseOrderKrw = resolveBaseOrderKrw(bc);
        double multiplier = Math.pow(tradeProps.getAddBuyMultiplier(), pe.getAddBuys() + 1);
        int qty = computeQty(baseOrderKrw * multiplier, price);
        if (qty <= 0) return;

        final String patternType = evalResult.patternType;
        final String reason = evalResult.reason;
        final double confidence = evalResult.confidence;

        if ("PAPER".equals(mode)) {
            final double fill = price * (1.0 + tradeProps.getSlippageRate());
            final int fQty = qty;
            txTemplate.execute(new TransactionCallbackWithoutResult() {
                @Override
                protected void doInTransactionWithoutResult(TransactionStatus status) {
                    persistTrade(mode, symbol, "ADD_BUY", fill, fQty, 0.0, confidence, reason, patternType, reason, bd(pe.getAvgPrice()));

                    int totalQty = pe.getQty() + fQty;
                    double totalCost = bd(pe.getAvgPrice()) * pe.getQty() + fill * fQty;
                    double newAvg = totalCost / totalQty;

                    pe.setQty(totalQty);
                    pe.setAvgPrice(newAvg);
                    pe.setAddBuys(pe.getAddBuys() + 1);
                    pe.setUpdatedAt(Instant.now());
                    positionRepo.save(pe);
                }
            });
        } else {
            if (!liveOrders.isConfigured()) return;
            double tickPrice = TickSizeUtil.roundToTickSize(price, mt);
            final LiveOrderService.LiveOrderResult r = liveOrders.placeBuyOrder(symbol, mt, qty, tickPrice);
            if (!r.isFilled()) return;

            final double fill = r.avgPrice > 0 ? r.avgPrice : price;
            final int fQty = r.executedQty > 0 ? r.executedQty : qty;
            txTemplate.execute(new TransactionCallbackWithoutResult() {
                @Override
                protected void doInTransactionWithoutResult(TransactionStatus status) {
                    persistTrade(mode, symbol, "ADD_BUY", fill, fQty, 0.0, confidence,
                            reason + " ordNo=" + r.ordNo, patternType, reason, bd(pe.getAvgPrice()));

                    int totalQty = pe.getQty() + fQty;
                    double totalCost = bd(pe.getAvgPrice()) * pe.getQty() + fill * fQty;
                    double newAvg = totalCost / totalQty;

                    pe.setQty(totalQty);
                    pe.setAvgPrice(newAvg);
                    pe.setAddBuys(pe.getAddBuys() + 1);
                    pe.setUpdatedAt(Instant.now());
                    positionRepo.save(pe);
                }
            });
        }
        log.info("[{}] ADD_BUY 체결 | {}주 @ {} | 추가매수 #{}", symbol, qty, String.format("%.2f", price), pe.getAddBuys() + 1);
    }

    // =====================================================================
    // Persist trade log
    // =====================================================================

    private void persistTrade(String mode, String symbol, String action, double price,
                              int qty, double pnlKrw, double confidence,
                              String note, String patternType, String patternReason,
                              double avgBuyPrice) {
        TradeEntity t = new TradeEntity();
        t.setTsEpochMs(System.currentTimeMillis());
        t.setSymbol(symbol);
        t.setAction(action);
        t.setPrice(price);
        t.setQty(qty);
        t.setPnlKrw(pnlKrw);
        t.setMode(mode);
        t.setNote(note != null && note.length() > 500 ? note.substring(0, 500) : note);
        t.setPatternType(patternType);
        t.setPatternReason(patternReason != null && patternReason.length() > 500 ? patternReason.substring(0, 500) : patternReason);
        if (avgBuyPrice > 0) t.setAvgBuyPrice(avgBuyPrice);
        if (confidence > 0) t.setConfidence(confidence);
        t.setCandleUnitMin(currentTickUnitMin);

        if (avgBuyPrice > 0 && price > 0 && "SELL".equals(action)) {
            double roiPct = ((price - avgBuyPrice) / avgBuyPrice) * 100.0;
            t.setRoiPercent(roiPct);
        }

        tradeRepo.save(t);
    }

    // =====================================================================
    // Status / Config
    // =====================================================================

    public BotStatus getStatus() {
        BotConfigEntity bc = getBotConfig();
        List<StockConfigEntity> stocks = stockConfigRepo.findAll();
        MarketType activeMarket = parseMarketType(bc.getMarketType());

        BotStatus s = new BotStatus();
        s.setRunning(running.get());
        s.setStartedAtEpochMillis(startedAt);
        s.setMode(bc.getMode());
        s.setMarketType(bc.getMarketType());
        s.setCandleUnitMin(bc.getCandleUnitMin());
        s.setCapitalKrw(bd(bc.getCapitalKrw()));
        s.setTakeProfitPct(bd(bc.getTakeProfitPct()));
        s.setStopLossPct(bd(bc.getStopLossPct()));
        s.setStrategyLock(bc.isStrategyLock());
        s.setMinConfidence(bc.getMinConfidence());
        s.setTimeStopMinutes(bc.getTimeStopMinutes());
        s.setStrategyIntervalsCsv(bc.getStrategyIntervalsCsv());
        s.setEmaFilterCsv(bc.getEmaFilterCsv());
        s.setMaxAddBuysGlobal(bc.getMaxAddBuysGlobal());
        s.setUsMode(bc.getUsMode());
        s.setUsCapitalKrw(bd(bc.getUsCapitalKrw()));
        s.setBaseOrderKrw(resolveBaseOrderKrw(bc));
        s.setStrategyType(bc.getStrategyType());

        List<StrategyType> active = parseActiveStrategyTypes(bc);
        List<String> activeNames = new ArrayList<String>();
        for (StrategyType t : active) activeNames.add(t.name());
        s.setStrategies(activeNames);

        // Market session status
        ZonedDateTime now = ZonedDateTime.now(activeMarket.timezone());
        boolean open = MarketCalendar.isMarketOpen(now, activeMarket);
        s.setMarketSessionStatus(open ? "OPEN" : "CLOSED");
        s.setActiveMarketType(activeMarket.name());

        // PnL
        double realized = calcRealizedPnl(bc.getMode());
        double unrealized = calcUnrealized();
        s.setRealizedPnlKrw(realized);
        s.setUnrealizedPnlKrw(unrealized);
        s.setTotalPnlKrw(realized + unrealized);
        if (bd(bc.getCapitalKrw()) > 0)
            s.setRoiPercent((s.getTotalPnlKrw() / bd(bc.getCapitalKrw())) * 100.0);

        // Sell count today
        s.setSellCountToday(countSellsToday());

        // Stock statuses
        Map<String, BotStatus.StockStatus> stockMap = new LinkedHashMap<String, BotStatus.StockStatus>();
        double usedCapital = 0.0;
        for (StockConfigEntity sc : stocks) {
            SymbolState st = states.get(sc.getSymbol());
            PositionEntity pe = positionRepo.findById(sc.getSymbol()).orElse(null);
            boolean posOpen = pe != null && pe.getQty() > 0;

            BotStatus.StockStatus ss = new BotStatus.StockStatus();
            ss.setSymbol(sc.getSymbol());
            ss.setMarketType(sc.getMarketType());
            ss.setDisplayName(sc.getDisplayName());
            ss.setEnabled(sc.isEnabled());
            ss.setBaseOrderKrw(bd(sc.getBaseOrderKrw()));
            ss.setPositionOpen(posOpen);
            ss.setAvgPrice(posOpen ? bd(pe.getAvgPrice()) : 0.0);
            ss.setQty(posOpen ? pe.getQty() : 0);
            ss.setAddBuys(posOpen ? pe.getAddBuys() : 0);
            ss.setEntryStrategy(posOpen ? pe.getEntryStrategy() : null);
            ss.setLastPrice(st != null ? st.lastPrice : 0.0);
            ss.setRealizedPnlKrw(calcSymbolRealizedPnl(sc.getSymbol(), bc.getMode()));

            stockMap.put(sc.getSymbol(), ss);

            if (posOpen) {
                usedCapital += bd(pe.getAvgPrice()) * pe.getQty();
            }
        }
        s.setStocks(stockMap);
        s.setUsedCapitalKrw(usedCapital);
        s.setAvailableCapitalKrw(bd(bc.getCapitalKrw()) - usedCapital);

        // Wins
        List<TradeEntity> allTrades = tradeRepo.findAll();
        int sellCount = 0;
        int winCount = 0;
        for (TradeEntity t : allTrades) {
            if (!"SELL".equals(t.getAction())) continue;
            sellCount++;
            if (bd(t.getPnlKrw()) > 0) winCount++;
        }
        s.setTotalTrades((int) tradeRepo.count());
        s.setWins(winCount);
        s.setWinRate(sellCount == 0 ? 0.0 : (winCount * 100.0 / sellCount));
        s.setAutoStartEnabled(bc.isAutoStartEnabled());

        return s;
    }

    public List<TradeEntity> recentTrades() {
        return tradeRepo.findAll();
    }

    public List<StockConfigEntity> getStockConfigs() {
        return stockConfigRepo.findAll();
    }

    public BotConfigEntity updateBotConfig(String mode, Integer candleUnitMin, Double capitalKrw,
                                            String strategyType, List<String> strategies,
                                            Integer maxAddBuysGlobal,
                                            Double takeProfitPct, Double stopLossPct,
                                            Boolean strategyLock, Double minConfidence,
                                            Integer timeStopMinutes) {
        BotConfigEntity bc = getBotConfig();
        if (mode != null && !mode.isEmpty()) bc.setMode(mode);
        if (candleUnitMin != null) bc.setCandleUnitMin(candleUnitMin);
        if (capitalKrw != null) bc.setCapitalKrw(BigDecimal.valueOf(capitalKrw));
        if (strategyType != null && !strategyType.isEmpty()) bc.setStrategyType(strategyType);
        if (strategies != null && !strategies.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String v : strategies) {
                if (v == null || v.trim().isEmpty()) continue;
                if (sb.length() > 0) sb.append(",");
                sb.append(v.trim());
            }
            bc.setStrategyTypesCsv(sb.toString());
        }
        if (maxAddBuysGlobal != null) bc.setMaxAddBuysGlobal(maxAddBuysGlobal);
        if (takeProfitPct != null) bc.setTakeProfitPct(BigDecimal.valueOf(takeProfitPct));
        if (stopLossPct != null) bc.setStopLossPct(BigDecimal.valueOf(stopLossPct));
        if (strategyLock != null) bc.setStrategyLock(strategyLock);
        if (minConfidence != null) bc.setMinConfidence(minConfidence);
        if (timeStopMinutes != null) bc.setTimeStopMinutes(timeStopMinutes);
        return botConfigRepo.save(bc);
    }

    public void updateUsConfig(String usMode, Double usCapitalKrw) {
        BotConfigEntity bc = getBotConfig();
        if (usMode != null && !usMode.isEmpty()) bc.setUsMode(usMode);
        if (usCapitalKrw != null) bc.setUsCapitalKrw(BigDecimal.valueOf(usCapitalKrw));
        botConfigRepo.save(bc);
    }

    public void updateStockConfigs(List<StockConfigEntity> incoming) {
        for (StockConfigEntity m : incoming) {
            if (m.getSymbol() == null || m.getSymbol().trim().isEmpty()) continue;
            StockConfigEntity sc = stockConfigRepo.findById(m.getSymbol()).orElse(new StockConfigEntity());
            sc.setSymbol(m.getSymbol());
            sc.setMarketType(m.getMarketType());
            sc.setDisplayName(m.getDisplayName());
            sc.setEnabled(m.isEnabled());
            if (m.getBaseOrderKrw() != null && bd(m.getBaseOrderKrw()) > 0)
                sc.setBaseOrderKrw(m.getBaseOrderKrw());
            stockConfigRepo.save(sc);
        }
        refreshSymbolStates();
    }

    // =====================================================================
    // Internal helpers
    // =====================================================================

    private BotConfigEntity getBotConfig() {
        List<BotConfigEntity> all = botConfigRepo.findAll();
        if (all == null || all.isEmpty()) {
            BotConfigEntity def = new BotConfigEntity();
            def.setMode("PAPER");
            def.setCandleUnitMin(5);
            def.setMaxAddBuysGlobal(2);
            return def;
        }
        return all.get(0);
    }

    private List<StrategyType> parseActiveStrategyTypes(BotConfigEntity bc) {
        List<StrategyType> out = new ArrayList<StrategyType>();
        if (bc == null) {
            out.add(StrategyType.REGIME_PULLBACK);
            return out;
        }
        String csv = bc.getStrategyTypesCsv();
        if (csv != null && !csv.trim().isEmpty()) {
            for (String p : csv.split(",")) {
                if (p == null || p.trim().isEmpty()) continue;
                try {
                    out.add(StrategyType.valueOf(p.trim()));
                } catch (Exception ignore) {
                }
            }
        }
        if (out.isEmpty()) {
            try {
                out.add(StrategyType.valueOf(bc.getStrategyType()));
            } catch (Exception ignore) {
            }
        }
        if (out.isEmpty()) out.add(StrategyType.REGIME_PULLBACK);
        return out;
    }

    private double resolveBaseOrderKrw(BotConfigEntity bc) {
        if (bc == null) return Math.max(tradeProps.getGlobalBaseOrderKrw(), tradeProps.getMinOrderKrw());
        double base = tradeProps.getGlobalBaseOrderKrw();
        if (base <= 0) base = tradeProps.getMinOrderKrw();
        return Math.max(base, tradeProps.getMinOrderKrw());
    }

    /**
     * 주어진 금액과 가격으로 살 수 있는 주식 수량을 계산한다 (정수, 내림).
     */
    private int computeQty(double amountKrw, double price) {
        if (price <= 0) return 0;
        return (int) (amountKrw / price);
    }

    private boolean isStaleForEntry(StockCandle candle, int unitMin) {
        if (candle == null || candle.candle_date_time_utc == null) return true;
        try {
            Instant candleTime = Instant.parse(candle.candle_date_time_utc.endsWith("Z")
                    ? candle.candle_date_time_utc
                    : candle.candle_date_time_utc + "Z");
            long elapsed = System.currentTimeMillis() - candleTime.toEpochMilli();
            long ttlMs = (long) staleEntryTtlSeconds * 1000L + (long) unitMin * 60_000L;
            return elapsed > ttlMs;
        } catch (Exception e) {
            return true;
        }
    }

    private double calcRealizedPnl(String mode) {
        double sum = 0.0;
        for (TradeEntity t : tradeRepo.findAll()) {
            if (!"SELL".equals(t.getAction())) continue;
            if (mode != null && !mode.equalsIgnoreCase(t.getMode())) continue;
            sum += bd(t.getPnlKrw());
        }
        return sum;
    }

    private double calcSymbolRealizedPnl(String symbol, String mode) {
        double sum = 0.0;
        for (TradeEntity t : tradeRepo.findAll()) {
            if (!symbol.equals(t.getSymbol())) continue;
            if (!"SELL".equals(t.getAction())) continue;
            if (mode != null && !mode.equalsIgnoreCase(t.getMode())) continue;
            sum += bd(t.getPnlKrw());
        }
        return sum;
    }

    private double calcUnrealized() {
        double sum = 0.0;
        for (PositionEntity pe : positionRepo.findAll()) {
            if (pe.getQty() <= 0) continue;
            SymbolState st = states.get(pe.getSymbol());
            if (st != null && st.lastPrice > 0) {
                sum += pe.getQty() * (st.lastPrice - bd(pe.getAvgPrice()));
            }
        }
        return sum;
    }

    private int countSellsToday() {
        ZoneId zone = ZoneId.systemDefault();
        long from = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli();
        long to = System.currentTimeMillis();
        List<TradeEntity> trades = tradeRepo.findByTsEpochMsBetween(from, to);
        int count = 0;
        for (TradeEntity t : trades) {
            if ("SELL".equals(t.getAction())) count++;
        }
        return count;
    }

    private MarketType parseMarketType(String type) {
        if (type == null) return MarketType.KRX;
        try {
            return MarketType.valueOf(type.toUpperCase());
        } catch (Exception e) {
            return MarketType.KRX;
        }
    }

    private String resolveExchangeCode(MarketType mt) {
        switch (mt) {
            case NYSE:
                return "NYSE";
            case NASDAQ:
                return "NASD";
            default:
                return "NASD";
        }
    }
}
