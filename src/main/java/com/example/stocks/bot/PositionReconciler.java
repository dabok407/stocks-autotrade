package com.example.stocks.bot;

import com.example.stocks.db.PositionEntity;
import com.example.stocks.db.PositionRepository;
import com.example.stocks.kis.KisAccount;
import com.example.stocks.kis.KisPrivateClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P0-Fix#3 (V41 2026-05-06): 봇 DB 포지션 ↔ KIS 실잔고 정합성 검사.
 *
 * 1분 주기로 KIS 실잔고와 봇 DB position 테이블을 비교하여 불일치를 감지한다.
 *
 * 감지 케이스:
 *   - ORPHAN_BROKER : KIS 에 보유 중이지만 DB 에 없음 → 봇 SELL 미체결 또는 사용자 본인 매수
 *                     (사용자 본인 매수일 가능성 → 자동매매 절대 금지, 알람만)
 *   - ORPHAN_DB     : DB 에 있지만 KIS 에 없음 → SELL 은 사실 체결됐는데 DB commit 실패
 *                     → 다음 매수 사이클에서 재시도 / 재시작 시 정리
 *   - QTY_MISMATCH  : KIS 수량 < DB 수량 → 부분 체결 발생, 잔량 sell 미완
 *
 * 안전 원칙:
 *   - 자동 거래 절대 금지 (사용자 본인 매수와 봇 매수 구분 불가)
 *   - 로그/decision 기록만 — 사용자가 daily-report 에서 확인 후 수동 조치
 *   - KrxMorningRushService 와 직접 결합 안 함 (decision 로깅은 별도 채널)
 */
@Component
public class PositionReconciler {

    private static final Logger log = LoggerFactory.getLogger(PositionReconciler.class);
    private static final String ENTRY_STRATEGY = "KRX_MORNING_RUSH";

    private final KisPrivateClient kisPrivateClient;
    private final PositionRepository positionRepo;

    @Value("${reconciler.enabled:true}")
    private boolean enabled;

    /** 실행 결과를 외부에서 조회 가능하도록 보관 (디버깅/테스트용). */
    private volatile ReconcileReport lastReport;

    public PositionReconciler(KisPrivateClient kisPrivateClient, PositionRepository positionRepo) {
        this.kisPrivateClient = kisPrivateClient;
        this.positionRepo = positionRepo;
    }

    /**
     * 1분 주기 정합성 검사.
     * initialDelay 60s — 부팅 안정화 대기
     * fixedDelay 60s — 이전 실행 완료 후 60초 뒤 재실행 (오버랩 없음)
     */
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
        } catch (Exception e) {
            log.error("[Reconciler] failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 정합성 검사 본체 — 테스트 가능하도록 분리.
     */
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

        // 3. ORPHAN_DB — DB 있지만 broker 없음
        for (Map.Entry<String, PositionEntity> e : dbBySymbol.entrySet()) {
            String sym = e.getKey();
            if (!brokerBySymbol.containsKey(sym)) {
                report.orphanDb.add(sym);
                log.warn("[Reconciler] ORPHAN_DB symbol={} dbQty={} avg={} — SELL likely filled but DB not updated, position should be closed",
                        sym, e.getValue().getQty(), e.getValue().getAvgPrice());
            }
        }

        // 4. QTY_MISMATCH — 수량 불일치
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

        // 5. ORPHAN_BROKER — broker 있지만 DB 없음 (사용자 본인 매수 가능성 — 자동매매 금지)
        for (String sym : brokerBySymbol.keySet()) {
            if (!dbBySymbol.containsKey(sym)) {
                report.orphanBroker.add(sym);
                // info 레벨 — 사용자 본인 매수가 자연스러운 케이스라 warn 까진 아님
                log.info("[Reconciler] ORPHAN_BROKER symbol={} brokerQty={} avg={} — held outside bot scope (user position or pre-existing)",
                        sym, brokerBySymbol.get(sym).getQty(), brokerBySymbol.get(sym).getAvgPrice());
            }
        }

        return report;
    }

    public ReconcileReport getLastReport() { return lastReport; }

    /** 외부에서 enabled 토글 (테스트). */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    // =====================================================================
    // 결과 DTO
    // =====================================================================

    public static class ReconcileReport {
        public int brokerCount;
        public int dbCount;
        public final Set<String> orphanDb = new HashSet<>();
        public final Set<String> orphanBroker = new HashSet<>();
        public final Map<String, int[]> qtyMismatches = new HashMap<>();

        public boolean hasIssues() {
            return !orphanDb.isEmpty() || !qtyMismatches.isEmpty();
        }

        public void logSummary() {
            if (hasIssues()) {
                log.warn("[Reconciler] SUMMARY broker={} db={} orphanDb={} qtyMismatch={} orphanBroker={} — INVESTIGATE",
                        brokerCount, dbCount, orphanDb.size(), qtyMismatches.size(), orphanBroker.size());
            } else {
                log.debug("[Reconciler] SUMMARY broker={} db={} orphanBroker={} (clean)",
                        brokerCount, dbCount, orphanBroker.size());
            }
        }
    }
}
