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

        // 5. ORPHAN_BROKER vs STUCK_BOT_POSITION 분류
        for (Map.Entry<String, KisAccount> e : brokerBySymbol.entrySet()) {
            String sym = e.getKey();
            if (dbBySymbol.containsKey(sym)) continue;

            // V42: trade_log 의 patternType=KRX_MORNING_RUSH BUY 이력 검사
            if (hasBotBuyHistory(sym)) {
                report.stuckBotPositions.put(sym, e.getValue());
                log.warn("[Reconciler] STUCK_BOT_POSITION symbol={} brokerQty={} avg={} — bot BUY history found, cleanup candidate",
                        sym, e.getValue().getQty(), e.getValue().getAvgPrice());
            } else {
                report.orphanBroker.add(sym);
                log.info("[Reconciler] ORPHAN_BROKER symbol={} brokerQty={} avg={} — held outside bot scope",
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
     * V42: STUCK_BOT_POSITION 자동 시장가 매도.
     * 안전장치 다중 적용.
     */
    void attemptStuckCleanup(ReconcileReport report) {
        // config 체크
        KrxMorningRushConfigEntity cfg = loadCfgSafe();
        if (cfg == null || !cfg.isAutoCleanupStuckEnabled()) {
            log.debug("[Reconciler] auto cleanup stuck DISABLED");
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

        for (Map.Entry<String, KisAccount> e : report.stuckBotPositions.entrySet()) {
            String sym = e.getKey();
            // 같은 세션 반복 시도 방지
            if (attemptedCleanupSymbols.contains(sym)) {
                log.debug("[Reconciler] stuck cleanup already attempted this session: {}", sym);
                continue;
            }
            attemptedCleanupSymbols.add(sym);

            int qty = e.getValue().getQty();
            double avg = e.getValue().getAvgPrice();
            log.warn("[Reconciler] STUCK_CLEANUP TRY symbol={} qty={} avg={}", sym, qty, avg);

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
