package com.example.stocks.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KRX 모닝러쉬 phase 타이밍 시나리오 테스트.
 *
 * 운영 사고 (2026-04-08): E2E 12:13~12:30 하드코딩이 commit 안 되고 운영서버에 그대로 배포됨.
 * 09:00~09:10 KRX 장 시작 시점에 모닝러쉬 동작 안 함.
 *
 * 이 테스트는 운영 코드의 phase 판정 로직을 그대로 복제하여 시간대별 phase가 정확한지 검증.
 *
 * 검증:
 *  1. Range phase (08:50~09:00)
 *  2. Entry phase (09:00 + entryDelaySec ~ 09:10)
 *  3. Hold phase (09:10 ~ session_end)
 *  4. Session end (session_end ~)
 *  5. Outside hours (08:50 이전, session_end 이후)
 *  6. 5분 → 10분 entry window 확장 검증
 */
public class KrxMorningRushTimingScenarioTest {

    public enum Phase {
        OUTSIDE,
        RANGE,
        WAITING,  // V109: 09:00:00~09:00+entryDelay — 데이터 유지 대기
        ENTRY,
        HOLD,
        SESSION_END
    }

    /**
     * KrxMorningRushService.tick()의 phase 판정 로직 그대로 복제.
     * V109 수정: 09:00:00~09:00+entryDelaySec 구간 = WAITING (데이터 유지)
     */
    private Phase evaluatePhase(int hour, int min, int second, int entryDelaySec,
                                int sessionEndHour, int sessionEndMin,
                                boolean entryPhaseComplete) {
        int nowMinOfDay = hour * 60 + min;
        int sessionEndMinTotal = sessionEndHour * 60 + sessionEndMin;

        boolean isRangePhase = (nowMinOfDay >= 8 * 60 + 50) && (nowMinOfDay < 9 * 60);
        boolean isEntryPhase;
        if (nowMinOfDay == 9 * 60) {
            isEntryPhase = second >= entryDelaySec;
        } else {
            isEntryPhase = (nowMinOfDay > 9 * 60) && (nowMinOfDay < sessionEndMinTotal);
        }
        boolean isSessionEnd = (nowMinOfDay >= sessionEndMinTotal);

        // V109: 진입 대기 구간 (레인지 수집 후 ~ 진입 시작 전)
        boolean isWaitingForEntry = (nowMinOfDay == 9 * 60) && (second < entryDelaySec);

        if (isSessionEnd) return Phase.SESSION_END;
        if (isRangePhase) return Phase.RANGE;
        if (isWaitingForEntry) return Phase.WAITING;
        if (isEntryPhase) {
            if (entryPhaseComplete || nowMinOfDay >= 9 * 60 + 10) {
                return Phase.HOLD;
            }
            return Phase.ENTRY;
        }
        return Phase.OUTSIDE;
    }

    private static final int ENTRY_DELAY_SEC = 30;
    private static final int SESSION_END_HOUR = 10;
    private static final int SESSION_END_MIN = 0;

    // ═══════════════════════════════════════════════════════════
    //  Range phase (08:50 ~ 08:59)
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("시나리오 1: 08:50:00 → RANGE phase 시작")
    public void scenario1_range_start_0850() {
        assertEquals(Phase.RANGE,
                evaluatePhase(8, 50, 0, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false));
    }

    @Test
    @DisplayName("시나리오 2: 08:55:30 → RANGE phase 중간")
    public void scenario2_range_middle() {
        assertEquals(Phase.RANGE,
                evaluatePhase(8, 55, 30, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false));
    }

    @Test
    @DisplayName("시나리오 3: 08:59:59 → RANGE phase 마지막 1초")
    public void scenario3_range_last_second() {
        assertEquals(Phase.RANGE,
                evaluatePhase(8, 59, 59, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false));
    }

    // ═══════════════════════════════════════════════════════════
    //  Entry delay (09:00:00 ~ 09:00:29) — 30초 대기
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("시나리오 4: 09:00:00 → WAITING (entry delay 대기, 데이터 유지)")
    public void scenario4_entry_delay_start() {
        Phase r = evaluatePhase(9, 0, 0, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false);
        assertNotEquals(Phase.ENTRY, r, "delay 30초 전이라 ENTRY 진입 전");
        assertEquals(Phase.WAITING, r, "OUTSIDE가 아닌 WAITING — prevCloseMap clear 방지");
    }

    @Test
    @DisplayName("시나리오 5: 09:00:29 → WAITING (entry delay 1초 전, 데이터 유지)")
    public void scenario5_entry_delay_just_before() {
        assertEquals(Phase.WAITING,
                evaluatePhase(9, 0, 29, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false));
    }

    @Test
    @DisplayName("시나리오 6: ⭐ 09:00:30 → ENTRY phase 시작 (delay 만료)")
    public void scenario6_entry_start_after_delay() {
        assertEquals(Phase.ENTRY,
                evaluatePhase(9, 0, 30, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false));
    }

    // ═══════════════════════════════════════════════════════════
    //  Entry window 09:00:30 ~ 09:09:59 (10분 = 5분 → 10분 확장)
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("시나리오 7: 09:01:00 → ENTRY phase")
    public void scenario7_entry_minute_1() {
        assertEquals(Phase.ENTRY,
                evaluatePhase(9, 1, 0, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false));
    }

    @Test
    @DisplayName("시나리오 8: 09:05:00 → ENTRY phase (5분 시점, 기존 5분 윈도우 끝났던 시간)")
    public void scenario8_entry_minute_5_old_end() {
        assertEquals(Phase.ENTRY,
                evaluatePhase(9, 5, 0, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false));
    }

    @Test
    @DisplayName("시나리오 9: ⭐ 09:09:59 → ENTRY phase (10분 윈도우 마지막 1초)")
    public void scenario9_entry_last_second() {
        assertEquals(Phase.ENTRY,
                evaluatePhase(9, 9, 59, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false));
    }

    @Test
    @DisplayName("시나리오 10: ⭐ 09:10:00 → HOLD phase (entry complete)")
    public void scenario10_entry_complete_at_0910() {
        assertEquals(Phase.HOLD,
                evaluatePhase(9, 10, 0, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false));
    }

    @Test
    @DisplayName("시나리오 11: 09:30:00 → HOLD phase")
    public void scenario11_hold_minute_30() {
        assertEquals(Phase.HOLD,
                evaluatePhase(9, 30, 0, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false));
    }

    @Test
    @DisplayName("시나리오 12: 09:59:59 → HOLD phase (session end 직전)")
    public void scenario12_hold_just_before_session_end() {
        assertEquals(Phase.HOLD,
                evaluatePhase(9, 59, 59, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false));
    }

    // ═══════════════════════════════════════════════════════════
    //  Session end (10:00 이후 강제 청산)
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("시나리오 13: 10:00:00 정확 → SESSION_END")
    public void scenario13_session_end_exact() {
        assertEquals(Phase.SESSION_END,
                evaluatePhase(10, 0, 0, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false));
    }

    @Test
    @DisplayName("시나리오 14: 10:30:00 → SESSION_END (이후)")
    public void scenario14_after_session_end() {
        assertEquals(Phase.SESSION_END,
                evaluatePhase(10, 30, 0, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false));
    }

    @Test
    @DisplayName("시나리오 15: 15:30:00 → SESSION_END (오후)")
    public void scenario15_afternoon() {
        assertEquals(Phase.SESSION_END,
                evaluatePhase(15, 30, 0, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false));
    }

    // ═══════════════════════════════════════════════════════════
    //  Outside hours
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("시나리오 16: 08:00:00 → OUTSIDE (range 시작 전)")
    public void scenario16_before_range() {
        assertEquals(Phase.OUTSIDE,
                evaluatePhase(8, 0, 0, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false));
    }

    @Test
    @DisplayName("시나리오 17: 08:49:59 → OUTSIDE (range 1초 전)")
    public void scenario17_just_before_range() {
        assertEquals(Phase.OUTSIDE,
                evaluatePhase(8, 49, 59, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false));
    }

    @Test
    @DisplayName("시나리오 18: 00:00:00 (자정) → OUTSIDE")
    public void scenario18_midnight() {
        assertEquals(Phase.OUTSIDE,
                evaluatePhase(0, 0, 0, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false));
    }

    // ═══════════════════════════════════════════════════════════
    //  10분 확장 검증 (5분 → 10분)
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("⭐ 시나리오 19: 09:05:30 → ENTRY (5분 → 10분 확장 검증)")
    public void scenario19_extended_window_at_5min() {
        // 기존 5분 윈도우였다면 09:05 시점은 OUT 됐을 것
        // 10분 확장으로 ENTRY 유지
        assertEquals(Phase.ENTRY,
                evaluatePhase(9, 5, 30, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false));
    }

    @Test
    @DisplayName("⭐ 시나리오 20: 09:08:00 → ENTRY (10분 확장 후반)")
    public void scenario20_extended_window_late() {
        assertEquals(Phase.ENTRY,
                evaluatePhase(9, 8, 0, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false));
    }

    // ═══════════════════════════════════════════════════════════
    //  E2E 하드코딩 사고 재현 - 12:13/12:15/12:30 절대 작동 안 함
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("⭐ 시나리오 21: 12:13:00 → OUTSIDE (E2E 하드코딩 사고 재현 방지)")
    public void scenario21_e2e_hardcode_1213() {
        // E2E 코드가 살아있었다면 12:13 RANGE였음. 원복 후에는 OUTSIDE.
        Phase r = evaluatePhase(12, 13, 0, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false);
        assertEquals(Phase.SESSION_END, r,
                "12:13은 session_end(10:00) 이후라 SESSION_END (E2E 흔적 없음)");
    }

    @Test
    @DisplayName("⭐ 시나리오 22: 12:15:00 → SESSION_END (E2E 하드코딩 사고 재현 방지)")
    public void scenario22_e2e_hardcode_1215() {
        assertEquals(Phase.SESSION_END,
                evaluatePhase(12, 15, 0, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false));
    }

    @Test
    @DisplayName("⭐ 시나리오 23: 12:30:00 → SESSION_END (E2E 하드코딩 사고 재현 방지)")
    public void scenario23_e2e_hardcode_1230() {
        assertEquals(Phase.SESSION_END,
                evaluatePhase(12, 30, 0, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false));
    }

    // ═══════════════════════════════════════════════════════════
    //  V109 NO_RANGE 버그 재현 + 수정 검증 (2026-04-13)
    //  버그: 09:00:00~09:00:29가 OUTSIDE 판정 → prevCloseMap.clear()
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("⭐ 시나리오 24: NO_RANGE 버그 — 09:00:00은 WAITING이어야 함 (OUTSIDE면 데이터 삭제)")
    public void scenario24_norange_bug_0900_00() {
        Phase r = evaluatePhase(9, 0, 0, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false);
        assertNotEquals(Phase.OUTSIDE, r, "OUTSIDE면 prevCloseMap.clear() 실행됨 — NO_RANGE 버그 재현!");
        assertEquals(Phase.WAITING, r);
    }

    @Test
    @DisplayName("⭐ 시나리오 25: NO_RANGE 버그 — 09:00:15도 WAITING (데이터 유지)")
    public void scenario25_norange_bug_0900_15() {
        assertEquals(Phase.WAITING,
                evaluatePhase(9, 0, 15, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false));
    }

    @Test
    @DisplayName("⭐ 시나리오 26: 09:00:30 → ENTRY 시작 (WAITING 종료, 데이터 정상 사용)")
    public void scenario26_entry_after_waiting() {
        assertEquals(Phase.ENTRY,
                evaluatePhase(9, 0, 30, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false));
    }

    @Test
    @DisplayName("⭐ 시나리오 27: 전체 흐름 — RANGE → WAITING → ENTRY 연속 전환 데이터 유지")
    public void scenario27_full_flow_data_preserved() {
        // 08:55 → RANGE (데이터 수집)
        assertEquals(Phase.RANGE,
                evaluatePhase(8, 55, 0, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false));

        // 09:00:00 → WAITING (데이터 유지, clear 하면 안 됨)
        assertEquals(Phase.WAITING,
                evaluatePhase(9, 0, 0, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false));

        // 09:00:29 → 여전히 WAITING
        assertEquals(Phase.WAITING,
                evaluatePhase(9, 0, 29, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false));

        // 09:00:30 → ENTRY (데이터로 매수 판단)
        assertEquals(Phase.ENTRY,
                evaluatePhase(9, 0, 30, ENTRY_DELAY_SEC, SESSION_END_HOUR, SESSION_END_MIN, false));
    }
}
