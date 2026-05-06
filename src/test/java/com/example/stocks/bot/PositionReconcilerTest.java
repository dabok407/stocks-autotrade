package com.example.stocks.bot;

import com.example.stocks.db.PositionEntity;
import com.example.stocks.db.PositionRepository;
import com.example.stocks.kis.KisAccount;
import com.example.stocks.kis.KisPrivateClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0-Fix#3 (V41 2026-05-06): 포지션 정합성 검사 단위 테스트.
 *
 * 시나리오 회귀:
 *   - 04-16 에프알텍 stuck: 봇 SELL EXECUTED 로그, 실제 KIS 보유 → ORPHAN_BROKER 또는 QTY_MISMATCH 감지
 *   - DB commit 실패 시: ORPHAN_DB 감지
 *   - 사용자 본인 매수 (삼성전자 등): ORPHAN_BROKER (warn 아닌 info 로 처리)
 */
class PositionReconcilerTest {

    private KisPrivateClient kisClient;
    private PositionRepository positionRepo;
    private PositionReconciler reconciler;

    @BeforeEach
    void setUp() {
        kisClient = mock(KisPrivateClient.class);
        positionRepo = mock(PositionRepository.class);
        reconciler = new PositionReconciler(kisClient, positionRepo);
        reconciler.setEnabled(true);
        when(kisClient.isConfigured()).thenReturn(true);
    }

    private KisAccount kisHolding(String symbol, int qty) {
        KisAccount a = new KisAccount();
        a.setSymbol(symbol);
        a.setName(symbol);
        a.setQty(qty);
        a.setAvgPrice(1000);
        a.setCurrency("KRW");
        return a;
    }

    private PositionEntity dbPos(String symbol, int qty, String strategy) {
        PositionEntity p = new PositionEntity();
        p.setSymbol(symbol);
        p.setQty(qty);
        p.setAvgPrice(BigDecimal.valueOf(1000));
        p.setEntryStrategy(strategy);
        return p;
    }

    @Test
    @DisplayName("정상 케이스: 봇 매수 종목이 KIS 와 DB 모두 있고 수량 일치 → 이슈 없음")
    void cleanState_noIssues() {
        when(kisClient.getDomesticBalance()).thenReturn(Arrays.asList(
                kisHolding("005930", 100),
                kisHolding("073540", 10)));
        when(positionRepo.findAll()).thenReturn(Arrays.asList(
                dbPos("073540", 10, "KRX_MORNING_RUSH")));

        PositionReconciler.ReconcileReport r = reconciler.doReconcile();

        assertFalse(r.hasIssues(), "이슈 없어야 함");
        assertEquals(0, r.orphanDb.size());
        assertEquals(0, r.qtyMismatches.size());
        assertEquals(1, r.orphanBroker.size(), "삼성전자(사용자 본인)는 ORPHAN_BROKER로 분류");
        assertTrue(r.orphanBroker.contains("005930"));
    }

    @Test
    @DisplayName("ORPHAN_DB 회귀: SELL 체결됐는데 DB commit 실패 → KIS 잔고에 없음")
    void orphanDb_dbCommitFailedAfterSell() {
        when(kisClient.getDomesticBalance()).thenReturn(new ArrayList<>());
        when(positionRepo.findAll()).thenReturn(Arrays.asList(
                dbPos("073540", 10, "KRX_MORNING_RUSH")));

        PositionReconciler.ReconcileReport r = reconciler.doReconcile();

        assertTrue(r.hasIssues());
        assertEquals(1, r.orphanDb.size());
        assertTrue(r.orphanDb.contains("073540"));
        assertEquals(0, r.qtyMismatches.size());
    }

    @Test
    @DisplayName("ORPHAN_BROKER 회귀: KIS 보유인데 DB 없음 (사용자 본인 매수) → 이슈 아님")
    void orphanBroker_userOwnPosition_noIssue() {
        when(kisClient.getDomesticBalance()).thenReturn(Arrays.asList(
                kisHolding("005930", 12),    // 삼성전자 — 사용자 본인
                kisHolding("012330", 1)));   // 현대모비스 — 사용자 본인
        when(positionRepo.findAll()).thenReturn(new ArrayList<>());

        PositionReconciler.ReconcileReport r = reconciler.doReconcile();

        assertFalse(r.hasIssues(), "ORPHAN_BROKER 만 있을 때는 이슈 아님 (사용자 본인 매수)");
        assertEquals(2, r.orphanBroker.size());
        assertTrue(r.orphanBroker.contains("005930"));
        assertTrue(r.orphanBroker.contains("012330"));
    }

    @Test
    @DisplayName("ORPHAN_BROKER 회귀 (stuck): 04-16 에프알텍 — 봇 SELL EXECUTED 로그인데 실제 미체결")
    void orphanBroker_stuckPosition_oldBug() {
        // 시나리오: 봇이 SELL EXECUTED 로그 후 DB 에서 position 삭제 — 실제로는 KIS 미체결
        // 결과: KIS 에 보유, DB 에 없음 → ORPHAN_BROKER
        // P0-Fix#1 으로 새로 발생 안 하지만 과거 stuck 잔재 감지 가능
        when(kisClient.getDomesticBalance()).thenReturn(Arrays.asList(
                kisHolding("073540", 10),
                kisHolding("184230", 46),
                kisHolding("047040", 1)));
        when(positionRepo.findAll()).thenReturn(new ArrayList<>());

        PositionReconciler.ReconcileReport r = reconciler.doReconcile();

        // 과거 stuck 은 사용자 본인 매수와 구분 불가 — info 레벨로 알람만
        assertFalse(r.hasIssues());
        assertEquals(3, r.orphanBroker.size());
    }

    @Test
    @DisplayName("QTY_MISMATCH: 부분 체결 또는 외부 거래로 수량 불일치")
    void qtyMismatch_partialFill() {
        when(kisClient.getDomesticBalance()).thenReturn(Arrays.asList(
                kisHolding("184230", 20)));  // KIS 에는 20주
        when(positionRepo.findAll()).thenReturn(Arrays.asList(
                dbPos("184230", 46, "KRX_MORNING_RUSH")));  // DB 는 46주

        PositionReconciler.ReconcileReport r = reconciler.doReconcile();

        assertTrue(r.hasIssues());
        assertEquals(1, r.qtyMismatches.size());
        int[] mismatch = r.qtyMismatches.get("184230");
        assertEquals(46, mismatch[0]);
        assertEquals(20, mismatch[1]);
    }

    @Test
    @DisplayName("다른 전략(SAFETY_GUARD 등) DB 포지션은 reconciler 대상 아님")
    void otherStrategy_skipped() {
        when(kisClient.getDomesticBalance()).thenReturn(new ArrayList<>());
        when(positionRepo.findAll()).thenReturn(Arrays.asList(
                dbPos("OTHER1", 5, "SAFETY_GUARD"),  // KrxMorningRush 가 아님 — 무시
                dbPos("OTHER2", 5, "MAIN")));        // 무시

        PositionReconciler.ReconcileReport r = reconciler.doReconcile();

        assertFalse(r.hasIssues(), "다른 전략 포지션은 reconciler 대상 아님");
        assertEquals(0, r.dbCount);
    }

    @Test
    @DisplayName("DB qty=0 인 포지션은 skip")
    void zeroQtyPosition_skipped() {
        when(kisClient.getDomesticBalance()).thenReturn(new ArrayList<>());
        when(positionRepo.findAll()).thenReturn(Arrays.asList(
                dbPos("ZEROQTY", 0, "KRX_MORNING_RUSH")));

        PositionReconciler.ReconcileReport r = reconciler.doReconcile();

        assertFalse(r.hasIssues());
        assertEquals(0, r.dbCount);
    }

    @Test
    @DisplayName("KIS 미설정 시 reconcile 스킵")
    void kisNotConfigured_skipped() {
        when(kisClient.isConfigured()).thenReturn(false);

        reconciler.reconcile();

        assertNull(reconciler.getLastReport(), "스킵 시 lastReport 갱신 안 됨");
    }

    @Test
    @DisplayName("enabled=false 시 reconcile 스킵")
    void disabled_skipped() {
        reconciler.setEnabled(false);

        reconciler.reconcile();

        assertNull(reconciler.getLastReport());
    }

    @Test
    @DisplayName("실행 후 lastReport 갱신")
    void afterRun_lastReportSet() {
        when(kisClient.getDomesticBalance()).thenReturn(Arrays.asList(kisHolding("005930", 100)));
        when(positionRepo.findAll()).thenReturn(new ArrayList<>());

        reconciler.reconcile();

        assertNotNull(reconciler.getLastReport());
        assertEquals(1, reconciler.getLastReport().brokerCount);
    }
}
