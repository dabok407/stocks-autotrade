package com.example.stocks.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-Fix#2 (V41 2026-05-06): 청산 사유별 ordType 분기 검증.
 *
 * isMarketOrderReason → true 면 "01"(시장가), false 면 "00"(지정가).
 *
 * 분류 정책:
 *   시장가 (시간 critical): SL, SL_WIDE, SL_TIGHT, TIME_STOP, SESSION_END, FORCE_EXIT
 *   지정가 (익절 — 슬리피지 회피): TP_TRAIL, SPLIT_1ST
 */
class KrxMorningRushOrderTypeTest {

    @Test
    @DisplayName("SL 계열은 시장가 청산 (체결 보장 우선)")
    void slReasons_useMarketOrder() {
        // 04-16 에프알텍 케이스: "SL hit: pnl=-1.29% <= sl=-1.00% price=6120 avg=6200 (REST backup)"
        assertTrue(KrxMorningRushService.isMarketOrderReason(
                "SL hit: pnl=-1.29% <= sl=-1.00% price=6120 avg=6200 (REST backup)"));
        // 04-21 대우건설 케이스: "SL_WIDE pnl=-2.49% <= -2.40% price=29350 avg=30100 (realtime)"
        assertTrue(KrxMorningRushService.isMarketOrderReason(
                "SL_WIDE pnl=-2.49% <= -2.40% price=29350 avg=30100 (realtime)"));
        // SL_TIGHT 케이스
        assertTrue(KrxMorningRushService.isMarketOrderReason(
                "SL_TIGHT pnl=-1.77% <= -1.00% price=2220 avg=2260 elapsed=601s"));
    }

    @Test
    @DisplayName("TIME_STOP / SESSION_END / FORCE_EXIT 도 시장가")
    void timeBoundReasons_useMarketOrder() {
        assertTrue(KrxMorningRushService.isMarketOrderReason(
                "TIME_STOP: 30min elapsed (limit=30), pnl=-0.04% price=11465"));
        assertTrue(KrxMorningRushService.isMarketOrderReason(
                "SESSION_END: KRX morning rush session closing"));
        assertTrue(KrxMorningRushService.isMarketOrderReason(
                "SPLIT_SESSION_END: 2차 잔량 세션 종료 청산"));
        assertTrue(KrxMorningRushService.isMarketOrderReason(
                "FORCE_EXIT: stale position cleanup"));
    }

    @Test
    @DisplayName("TP_TRAIL / SPLIT_1ST 익절은 지정가 (슬리피지 회피)")
    void takeProfitReasons_useLimitOrder() {
        // 04-21 루멘스 TP_TRAIL: "TP_TRAIL avg=1679 peak=1736 now=1703 drop=1.90% pnl=1.43%"
        assertFalse(KrxMorningRushService.isMarketOrderReason(
                "TP_TRAIL avg=1679 peak=1736 now=1703 drop=1.90% pnl=1.43% (realtime)"));
        // 04-17 010170 SPLIT_1ST: "SPLIT_1ST pnl=+2.32% >= 1.60% avg=15950 now=16320"
        assertFalse(KrxMorningRushService.isMarketOrderReason(
                "SPLIT_1ST pnl=+2.32% >= 1.60% avg=15950 now=16320 (REST backup)"));
        // SPLIT_2ND_TRAIL
        assertFalse(KrxMorningRushService.isMarketOrderReason(
                "SPLIT_2ND_TRAIL avg=15950 peak=17310 now=16080 drop=7.11% >= 1.50% pnl=0.82%"));
    }

    @Test
    @DisplayName("null / 빈 문자열은 안전 처리 (기본 지정가)")
    void edgeCases_returnFalse() {
        assertFalse(KrxMorningRushService.isMarketOrderReason(null));
        assertFalse(KrxMorningRushService.isMarketOrderReason(""));
        assertFalse(KrxMorningRushService.isMarketOrderReason("UNKNOWN_REASON"));
    }

    @Test
    @DisplayName("대소문자 무관하게 동작")
    void caseInsensitive() {
        assertTrue(KrxMorningRushService.isMarketOrderReason("sl hit: ..."));
        assertTrue(KrxMorningRushService.isMarketOrderReason("Time_Stop: ..."));
        assertFalse(KrxMorningRushService.isMarketOrderReason("tp_trail ..."));
    }
}
