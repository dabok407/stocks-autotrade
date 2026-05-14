package com.example.stocks.bot;

import com.example.stocks.db.KrxMorningRushConfigEntity;
import com.example.stocks.db.KrxMorningRushConfigRepository;
import com.example.stocks.db.PositionEntity;
import com.example.stocks.db.PositionRepository;
import com.example.stocks.db.TradeEntity;
import com.example.stocks.db.TradeRepository;
import com.example.stocks.kis.KisAccount;
import com.example.stocks.kis.KisPrivateClient;
import com.example.stocks.market.MarketType;
import com.example.stocks.trade.LiveOrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P0-Fix#3 + P2-D (V41+V42 2026-05-06): 봇 DB ↔ KIS 실잔고 정합성 + stuck 자동 청산.
 *
 * 1분 주기로 KIS 실잔고와 봇 DB position 테이블을 비교하여 불일치를 감지한다.
 *
 * 감지 케이스:
 *   - ORPHAN_BROKER       : KIS 보유, DB 없음 → 사용자 본인 매수 (안전)
 *   - STUCK_BOT_POSITION  : KIS 보유, DB 없음, BUT trade_log 에 봇 BUY 이력 존재
 *                           → 봇 SELL 미체결 stuck. auto_cleanup_stuck_enabled=true 면 시장가 매도.
 *   - ORPHAN_DB           : DB 있고 KIS 없음 → SELL 체결됐는데 DB commit 실패
 *   - QTY_MISMATCH        : DB qty != KIS qty → 부분 체결 / 외부 거래
 *
 * STUCK_BOT_POSITION 자동 청산 안전장치:
 *   1) auto_cleanup_stuck_enabled (default true) — config 로 OFF 가능
 *   2) 시장 시간(09:00-15:30 KST) 내에서만 시도
 *   3) attemptedCleanupSymbols Set 으로 같은 세션 반복 시도 방지
 *   4) 시장가 매도 (P0-Fix#2 활용) — 체결 보장
 *   5) 사용자 본인 매수 (trade_log BUY 이력 없음) 는 절대 매도 안 함
 */
@Component
public class PositionReconciler {

    private static final Logger log = LoggerFactory.getLogger(PositionReconciler.class);
    private static final String ENTRY_STRATEGY = "KRX_MORNING_RUSH";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final LocalTime MARKET_OPEN = LocalTime.of(9, 0);
    static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    private final KisPrivateClient kisPrivateClient;
    private final PositionRepository positionRepo;
    private final TradeRepository tradeLogRepo;
    private final LiveOrderService liveOrders;
    private final KrxMorningRushConfigRepository configRepo;

    @Value("${reconciler.enabled:true}")
    private boolean enabled;

    /** 한 세션 내 cleanup 시도한 symbol — 반복 시도 방지. */
    private final Set<String> attemptedCleanupSymbols = ConcurrentHashMap.newKeySet();

    private volatile ReconcileReport lastReport;

    public PositionReconciler(KisPrivateClient kisPrivateClient,
                              PositionRepository positionRepo,
                              TradeRepository tradeLogRepo,
                              LiveOrderService liveOrders,
                              KrxMorningRushConfigRepository configRepo) {
        this.kisPrivateClient = kisPrivateClient;
        this.positionRepo = positionRepo;
        this.tradeLogRepo = tradeLogRepo;
        this.liveOrders = liveOrders;
        this.configRepo = configRepo;
    }

    @Scheduled(initialDelayString = "${reconciler.initialDelayMs:60000}",
               fixedDelayString = "${reconciler.fixedDelayMs:60000}")
    public void reconcile() {
        if (!enabled) return;
        if (!kisPrivateClient.isConfigured()) {
            log.debug("[Reconciler] KIS API not configured, skipping");
            return;
        }
        try {
            ReconcileReport report = doReconcile();
            this.lastReport = report;
            report.logSummary();

            // V42: stuck 자동 청산 시도
            if (!report.stuckBotPositions.isEmpty()) {
                attemptStuckCleanup(report);
            }
            // V45 (2026-05-14): QTY_MISMATCH 자동 동기화 (brokerQty > dbQty + hasBotBuyHistory)
            if (!report.qtyMismatches.isEmpty()) {
                attemptQtyMismatchSync(report);
            }
        } catch (Exception e) {
            log.error("[Reconciler] failed: {}", e.getMessage(), e);
        }
    }

    public ReconcileReport doReconcile() {
        // 1. KIS 실잔고
        List<KisAccount> brokerHoldings = kisPrivateClient.getDomesticBalance();
        Map<String, KisAccount> brokerBySymbol = new HashMap<>();
        for (KisAccount a : brokerHoldings) {
            if (a.getSymbol() == null || a.getQty() <= 0) continue;
            brokerBySymbol.put(a.getSymbol(), a);
        }

        // 2. DB 포지션 (KrxMorningRush 만, qty>0)
        List<PositionEntity> dbPositions = positionRepo.findAll();
        Map<String, PositionEntity> dbBySymbol = new HashMap<>();
        for (PositionEntity p : dbPositions) {
            if (!ENTRY_STRATEGY.equals(p.getEntryStrategy())) continue;
            if (p.getQty() <= 0) continue;
            dbBySymbol.put(p.getSymbol(), p);
        }

        ReconcileReport report = new ReconcileReport();
        report.brokerCount = brokerBySymbol.size();
        report.dbCount = dbBySymbol.size();

        // 3. ORPHAN_DB
        for (Map.Entry<String, PositionEntity> e : dbBySymbol.entrySet()) {
            String sym = e.getKey();
            if (!brokerBySymbol.containsKey(sym)) {
                report.orphanDb.add(sym);
                log.warn("[Reconciler] ORPHAN_DB symbol={} dbQty={} avg={} — SELL likely filled but DB not updated",
                        sym, e.getValue().getQty(), e.getValue().getAvgPrice());
            }
        }

        // 4. QTY_MISMATCH
        Set<String> intersect = new HashSet<>(dbBySymbol.keySet());
        intersect.retainAll(brokerBySymbol.keySet());
        for (String sym : intersect) {
            int dbQty = dbBySymbol.get(sym).getQty();
            int brokerQty = brokerBySymbol.get(sym).getQty();
            if (dbQty != brokerQty) {
                report.qtyMismatches.put(sym, new int[]{dbQty, brokerQty});
                log.warn("[Reconciler] QTY_MISMATCH symbol={} dbQty={} brokerQty={} — partial fill or external trade",
                        sym, dbQty, brokerQty);
            }
        }

        // 5. ORPHAN_BROKER vs STUCK_BOT_POSITION 분류 (V42 원본 정책 유지)
        // V45 (2026-05-14): 분류는 hasBotBuyHistory 단순 OR 유지. botNet 검사는 cleanup 시점에 활용.
        //   분류 단계에서 net=0 으로 거르면 매도 실패 trade 가 SELL 로 기록된 케이스도 함께 거르는
        //   false negative 발생 (184230 등 진짜 stuck 인데 ORPHAN_BROKER 로 빠짐).
        for (Map.Entry<String, KisAccount> e : brokerBySymbol.entrySet()) {
            String sym = e.getKey();
            if (dbBySymbol.containsKey(sym)) continue;

            if (hasBotBuyHistory(sym)) {
                report.stuckBotPositions.put(sym, e.getValue());
                log.warn("[Reconciler] STUCK_BOT_POSITION symbol={} brokerQty={} avg={} — bot BUY history found, cleanup candidate",
                        sym, e.getValue().getQty(), e.getValue().getAvgPrice());
            } else {
                report.orphanBroker.add(sym);
                log.info("[Reconciler] ORPHAN_BROKER symbol={} brokerQty={} avg={} — outside bot scope",
                        sym, e.getValue().getQty(), e.getValue().getAvgPrice());
            }
        }

        return report;
    }

    /**
     * V42: trade_log 에 해당 symbol 의 patternType=KRX_MORNING_RUSH BUY 이력이 있는지.
     * 사용자 본인 매수와 봇 stuck 구분의 핵심.
     */
    boolean hasBotBuyHistory(String symbol) {
        try {
            List<TradeEntity> trades = tradeLogRepo.findBySymbol(symbol);
            for (TradeEntity t : trades) {
                if ("BUY".equalsIgnoreCase(t.getAction())
                        && ENTRY_STRATEGY.equals(t.getPatternType())) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("[Reconciler] hasBotBuyHistory failed for {}: {}", symbol, e.getMessage());
        }
        return false;
    }

    /**
     * V45 (2026-05-14): 봇이 매수한 총량 (SELL 무관).
     *
     * 안전 분류 기준:
     *   - broker_qty <= botBuyTotal: 봇이 매수한 양 이내 → STUCK 후보 (봇 책임)
     *   - broker_qty >  botBuyTotal: 봇 BUY 보다 많은 잔량 → 사용자 외부 추가 매수 → 보호
     *
     * 봇이 매도 실패해도 trade_log 에 SELL 이 기록되는 케이스가 있어, net=buy-sell 보다
     * 단순 buy 합산이 안전. SELL 실패가 net 0 으로 잘못 잡히는 false negative 방지.
     */
    int calculateBotBuyTotal(String symbol) {
        try {
            List<TradeEntity> trades = tradeLogRepo.findBySymbol(symbol);
            int buy = 0;
            for (TradeEntity t : trades) {
                if (!ENTRY_STRATEGY.equals(t.getPatternType())) continue;
                if ("BUY".equalsIgnoreCase(t.getAction())) buy += t.getQty();
            }
            return buy;
        } catch (Exception e) {
            log.warn("[Reconciler] calculateBotBuyTotal failed for {}: {}", symbol, e.getMessage());
            return 0;
        }
    }

    /** V45 (2026-05-14): 봇 SELL 총량 — net=0 검사용 (사용자 외부 매수 보호) */
    int calculateBotSellTotal(String symbol) {
        try {
            List<TradeEntity> trades = tradeLogRepo.findBySymbol(symbol);
            int sell = 0;
            for (TradeEntity t : trades) {
                if (!ENTRY_STRATEGY.equals(t.getPatternType())) continue;
                if ("SELL".equalsIgnoreCase(t.getAction())) sell += t.getQty();
            }
            return sell;
        } catch (Exception e) {
            log.warn("[Reconciler] calculateBotSellTotal failed for {}: {}", symbol, e.getMessage());
            return 0;
        }
    }

    /**
     * V42: STUCK_BOT_POSITION 자동 시장가 매도.
     * 안전장치 다중 적용.
     */
    void attemptStuckCleanup(ReconcileReport report) {
        // config 체크
        KrxMorningRushConfigEntity cfg = loadCfgSafe();
        if (cfg == null || !cfg.isAutoCleanupStuckEnabled()) {
            log.debug("[Reconciler] auto cleanup stuck DISABLED (master switch)");
            return;
        }
        // V43 (2026-05-06): 화이트리스트 — 명시 등록 종목은 무한도 매도
        // V45 (2026-05-14): 화이트리스트 비어있어도 stuck_cleanup_max_value_krw 이하면 매도
        Set<String> whitelist = cfg.getStuckCleanupWhitelistSet();
        long maxValueLimit = cfg.getStuckCleanupMaxValueKrw();
        if (whitelist.isEmpty() && maxValueLimit <= 0) {
            log.info("[Reconciler] stuck cleanup whitelist EMPTY AND max_value_krw=0 — no auto-sell. " +
                    "stuck candidates: {}", report.stuckBotPositions.keySet());
            return;
        }
        // 시장 시간 체크
        LocalTime nowKst = LocalTime.now(KST);
        if (nowKst.isBefore(MARKET_OPEN) || nowKst.isAfter(MARKET_CLOSE)) {
            log.info("[Reconciler] stuck cleanup deferred — market closed (now={}, open={}-close={})",
                    nowKst, MARKET_OPEN, MARKET_CLOSE);
            return;
        }
        // LIVE 모드 + API 설정 체크
        if (!"LIVE".equalsIgnoreCase(cfg.getMode())) {
            log.debug("[Reconciler] stuck cleanup skipped — mode={}", cfg.getMode());
            return;
        }
        if (!liveOrders.isConfigured()) {
            log.warn("[Reconciler] stuck cleanup skipped — LIVE API not configured");
            return;
        }

        // V45 (2026-05-14): 화이트리스트 + 금액 한도 자동 매도 병행.
        //   - 화이트리스트 명시: 무한도 자동 매도 (사용자 명시 동의)
        //   - 화이트리스트 외: stuck_cleanup_max_value_krw 이하만 자동 매도 (큰 자산 보호)
        for (Map.Entry<String, KisAccount> e : report.stuckBotPositions.entrySet()) {
            String sym = e.getKey();
            int qty = e.getValue().getQty();
            double avg = e.getValue().getAvgPrice();
            long totalValue = (long) (qty * avg);

            // V45 안전장치 강화 (2026-05-14): 사용자 외부 매수 다중 검사.
            //   (1) botBuyTotal < brokerQty: 봇 BUY 보다 많은 잔량 → 외부 매수 명백.
            //   (2) botBuy == botSell > 0 (net=0): 봇 거래는 정상 완결됨 → broker 잔량은 외부.
            //       005880 케이스 (BUY 24/SELL 24, broker 24 별도 평단 = 외부 매수).
            //   둘 중 하나라도 의심되면 화이트리스트 없는 한 SKIP.
            int botBuyTotal = calculateBotBuyTotal(sym);
            int botSellTotal = calculateBotSellTotal(sym);
            boolean externalBuySuspected = (botBuyTotal < qty)
                    || (botBuyTotal > 0 && botBuyTotal == botSellTotal);
            // 화이트리스트 명시 종목은 사용자 의도 → 외부 매수 검사 우회 가능
            boolean inWhitelist = whitelist.contains(sym);
            if (externalBuySuspected && !inWhitelist) {
                log.warn("[Reconciler] STUCK_CLEANUP SKIP symbol={} qty={} avg={} — external buy suspected (botBuy={} botSell={} broker={})",
                        sym, qty, avg, botBuyTotal, botSellTotal, qty);
                continue;
            }

            // V45 변경: 화이트리스트 또는 금액 한도 이하 → 매도
            boolean underValueLimit = (maxValueLimit > 0 && totalValue <= maxValueLimit);
            if (!inWhitelist && !underValueLimit) {
                log.info("[Reconciler] STUCK_CLEANUP SKIP symbol={} qty={} avg={} value={} — not in whitelist AND value > {} (limit)",
                        sym, qty, avg, totalValue, maxValueLimit);
                continue;
            }
            // 같은 세션 반복 시도 방지
            if (attemptedCleanupSymbols.contains(sym)) {
                log.debug("[Reconciler] stuck cleanup already attempted this session: {}", sym);
                continue;
            }
            attemptedCleanupSymbols.add(sym);

            log.warn("[Reconciler] STUCK_CLEANUP TRY symbol={} qty={} avg={} value={} reason={}",
                    sym, qty, avg, totalValue, inWhitelist ? "whitelist" : "under-value-limit");

            try {
                // 시장가 매도 — P0-Fix#2 활용 (ordType="01")
                LiveOrderService.LiveOrderResult r = liveOrders.placeSellOrder(
                        sym, MarketType.KRX, qty, 0.0, "01");

                if (r != null && r.isFilled()) {
                    log.warn("[Reconciler] STUCK_CLEANUP SUCCESS symbol={} qty={} fillPrice={} avg={}",
                            sym, r.executedQty, r.avgPrice, avg);
                    // trade_log 에 SELL 기록
                    recordCleanupTrade(sym, r.executedQty, r.avgPrice, avg, cfg.getMode());
                    report.cleanupSuccess.add(sym);
                } else {
                    log.warn("[Reconciler] STUCK_CLEANUP FAIL symbol={} state={} qty={}",
                            sym, r != null ? r.state : "null", r != null ? r.executedQty : 0);
                    report.cleanupFailed.add(sym);
                }
            } catch (Exception ex) {
                log.error("[Reconciler] STUCK_CLEANUP EXCEPTION symbol={}: {}", sym, ex.getMessage(), ex);
                report.cleanupFailed.add(sym);
            }
        }
    }

    /**
     * V45 (2026-05-14): QTY_MISMATCH 자동 동기화.
     *
     * brokerQty > dbQty 이고 hasBotBuyHistory==true 일 때 봇 DB qty 를 broker 기준으로 업데이트.
     *
     * 시나리오:
     *   - 봇 BUY 78주 주문 → broker 분할체결 156주 (외부 매수 X, 봇 응답 파싱 실패)
     *   - 봇 DB 78주, broker 156주 — QTY_MISMATCH 영구 누적
     *
     * 해결:
     *   - hasBotBuyHistory==true 검증 (사용자 외부 매수 차단)
     *   - 봇 DB qty → broker qty 로 sync (자산 가치 변화 없음, 단지 봇 시야 확대)
     *   - 이후 정상 SL/TP·SESSION_END 시 전량 청산 가능
     *
     * 안전장치:
     *   - brokerQty < dbQty 인 경우는 sync 안 함 (broker 가 봇보다 적으면 broker 진실)
     *   - hasBotBuyHistory == false 인 경우 sync 안 함 (사용자 본인 매수)
     *   - config.qty_mismatch_auto_sync_enabled = false 면 sync 안 함
     */
    void attemptQtyMismatchSync(ReconcileReport report) {
        KrxMorningRushConfigEntity cfg = loadCfgSafe();
        if (cfg == null || !cfg.isQtyMismatchAutoSyncEnabled()) {
            log.debug("[Reconciler] qty mismatch auto sync DISABLED");
            return;
        }
        for (Map.Entry<String, int[]> e : report.qtyMismatches.entrySet()) {
            String sym = e.getKey();
            int dbQty = e.getValue()[0];
            int brokerQty = e.getValue()[1];

            // broker 가 봇보다 적으면 sync 안 함 (broker 가 SELL 후 정산 중일 수 있음)
            if (brokerQty <= dbQty) {
                log.info("[Reconciler] QTY_MISMATCH_SYNC SKIP symbol={} dbQty={} brokerQty={} — broker not larger",
                        sym, dbQty, brokerQty);
                continue;
            }
            // 봇 BUY 이력 없는 사용자 외부 매수 차단
            if (!hasBotBuyHistory(sym)) {
                log.info("[Reconciler] QTY_MISMATCH_SYNC SKIP symbol={} — no bot BUY history (user external buy)",
                        sym);
                continue;
            }

            try {
                PositionEntity p = positionRepo.findById(sym).orElse(null);
                if (p == null) continue;
                int oldQty = p.getQty();
                p.setQty(brokerQty);
                p.setUpdatedAt(java.time.Instant.now());
                positionRepo.save(p);
                log.warn("[Reconciler] QTY_MISMATCH_SYNC OK symbol={} dbQty={} → brokerQty={} (synced, bot BUY history confirmed)",
                        sym, oldQty, brokerQty);
            } catch (Exception ex) {
                log.error("[Reconciler] QTY_MISMATCH_SYNC FAIL symbol={}: {}", sym, ex.getMessage(), ex);
            }
        }
    }

    private void recordCleanupTrade(String symbol, int qty, double fillPrice, double avgPrice, String mode) {
        try {
            TradeEntity t = new TradeEntity();
            t.setTsEpochMs(System.currentTimeMillis());
            t.setSymbol(symbol);
            t.setMarketType("KRX");
            t.setAction("SELL");
            t.setPrice(fillPrice);
            t.setQty(qty);
            t.setAvgBuyPrice(avgPrice);
            double pnl = (fillPrice - avgPrice) * qty;
            t.setPnlKrw(pnl);
            double roi = avgPrice > 0 ? ((fillPrice - avgPrice) / avgPrice) * 100.0 : 0;
            t.setRoiPercent(roi);
            t.setMode(mode);
            t.setPatternType(ENTRY_STRATEGY);
            t.setPatternReason("RECONCILE_STUCK_CLEANUP");
            t.setCurrency("KRW");
            t.setScannerSource("KRX_MORNING_RUSH");
            tradeLogRepo.save(t);
        } catch (Exception e) {
            log.error("[Reconciler] recordCleanupTrade failed: {}", e.getMessage());
        }
    }

    private KrxMorningRushConfigEntity loadCfgSafe() {
        try {
            return configRepo.loadOrCreate();
        } catch (Exception e) {
            log.warn("[Reconciler] config load failed: {}", e.getMessage());
            return null;
        }
    }

    public ReconcileReport getLastReport() { return lastReport; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Set<String> getAttemptedCleanupSymbols() { return attemptedCleanupSymbols; }

    // ==========================================================
    public static class ReconcileReport {
        public int brokerCount;
        public int dbCount;
        public final Set<String> orphanDb = new HashSet<>();
        public final Set<String> orphanBroker = new HashSet<>();
        public final Map<String, int[]> qtyMismatches = new HashMap<>();
        // V42
        public final Map<String, KisAccount> stuckBotPositions = new HashMap<>();
        public final Set<String> cleanupSuccess = new HashSet<>();
        public final Set<String> cleanupFailed = new HashSet<>();

        public boolean hasIssues() {
            return !orphanDb.isEmpty() || !qtyMismatches.isEmpty() || !stuckBotPositions.isEmpty();
        }

        public void logSummary() {
            if (hasIssues()) {
                log.warn("[Reconciler] SUMMARY broker={} db={} orphanDb={} qtyMismatch={} stuckBot={} orphanBroker={} — INVESTIGATE",
                        brokerCount, dbCount, orphanDb.size(), qtyMismatches.size(),
                        stuckBotPositions.size(), orphanBroker.size());
            } else {
                log.debug("[Reconciler] SUMMARY broker={} db={} orphanBroker={} (clean)",
                        brokerCount, dbCount, orphanBroker.size());
            }
        }
    }
}
