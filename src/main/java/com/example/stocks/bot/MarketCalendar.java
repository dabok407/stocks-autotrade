package com.example.stocks.bot;

import com.example.stocks.market.MarketType;

import java.time.*;
import java.util.*;

/**
 * 주식 시장 개장/폐장 판단 유틸리티.
 * 시장별 정규 거래시간, 공휴일을 관리한다.
 */
public final class MarketCalendar {

    private MarketCalendar() {}

    // KRX 정규장: 09:00~15:30 KST
    private static final LocalTime KRX_OPEN = LocalTime.of(9, 0);
    private static final LocalTime KRX_CLOSE = LocalTime.of(15, 30);

    // NYSE/NASDAQ 정규장: 09:30~16:00 ET (America/New_York → DST 자동 반영)
    private static final LocalTime US_OPEN = LocalTime.of(9, 30);
    private static final LocalTime US_CLOSE = LocalTime.of(16, 0);

    // 공휴일 목록 (기본적인 날짜만 하드코딩, 실 운영 시 외부 소스 연동 권장)
    private static final Set<LocalDate> KRX_HOLIDAYS = new HashSet<LocalDate>(Arrays.asList(
            // 2025
            LocalDate.of(2025, 1, 1),   // 신정
            LocalDate.of(2025, 1, 28),  // 설 연휴
            LocalDate.of(2025, 1, 29),  // 설
            LocalDate.of(2025, 1, 30),  // 설 연휴
            LocalDate.of(2025, 3, 1),   // 삼일절
            LocalDate.of(2025, 5, 1),   // 근로자의 날
            LocalDate.of(2025, 5, 5),   // 어린이날
            LocalDate.of(2025, 5, 6),   // 석가탄신일
            LocalDate.of(2025, 6, 6),   // 현충일
            LocalDate.of(2025, 8, 15),  // 광복절
            LocalDate.of(2025, 10, 3),  // 개천절
            LocalDate.of(2025, 10, 6),  // 추석 연휴
            LocalDate.of(2025, 10, 7),  // 추석
            LocalDate.of(2025, 10, 8),  // 추석 연휴
            LocalDate.of(2025, 10, 9),  // 한글날
            LocalDate.of(2025, 12, 25), // 크리스마스
            // 2026
            LocalDate.of(2026, 1, 1),   // 신정
            LocalDate.of(2026, 2, 16),  // 설 연휴
            LocalDate.of(2026, 2, 17),  // 설
            LocalDate.of(2026, 2, 18),  // 설 연휴
            LocalDate.of(2026, 3, 1),   // 삼일절 (일요일 → 3/2 대체공휴일)
            LocalDate.of(2026, 3, 2),   // 대체공휴일
            LocalDate.of(2026, 5, 1),   // 근로자의 날
            LocalDate.of(2026, 5, 5),   // 어린이날
            LocalDate.of(2026, 5, 24),  // 석가탄신일
            LocalDate.of(2026, 6, 6),   // 현충일
            LocalDate.of(2026, 8, 15),  // 광복절
            LocalDate.of(2026, 9, 24),  // 추석 연휴
            LocalDate.of(2026, 9, 25),  // 추석
            LocalDate.of(2026, 9, 26),  // 추석 연휴
            LocalDate.of(2026, 10, 3),  // 개천절
            LocalDate.of(2026, 10, 9),  // 한글날
            LocalDate.of(2026, 12, 25)  // 크리스마스
    ));

    private static final Set<LocalDate> US_HOLIDAYS = new HashSet<LocalDate>(Arrays.asList(
            // 2025
            LocalDate.of(2025, 1, 1),   // New Year's Day
            LocalDate.of(2025, 1, 20),  // MLK Day
            LocalDate.of(2025, 2, 17),  // Presidents' Day
            LocalDate.of(2025, 4, 18),  // Good Friday
            LocalDate.of(2025, 5, 26),  // Memorial Day
            LocalDate.of(2025, 6, 19),  // Juneteenth
            LocalDate.of(2025, 7, 4),   // Independence Day
            LocalDate.of(2025, 9, 1),   // Labor Day
            LocalDate.of(2025, 11, 27), // Thanksgiving
            LocalDate.of(2025, 12, 25), // Christmas
            // 2026
            LocalDate.of(2026, 1, 1),   // New Year's Day
            LocalDate.of(2026, 1, 19),  // MLK Day
            LocalDate.of(2026, 2, 16),  // Presidents' Day
            LocalDate.of(2026, 4, 3),   // Good Friday
            LocalDate.of(2026, 5, 25),  // Memorial Day
            LocalDate.of(2026, 6, 19),  // Juneteenth
            LocalDate.of(2026, 7, 3),   // Independence Day (observed)
            LocalDate.of(2026, 9, 7),   // Labor Day
            LocalDate.of(2026, 11, 26), // Thanksgiving
            LocalDate.of(2026, 12, 25)  // Christmas
    ));

    /**
     * 주어진 시각에 해당 시장이 개장 중인지 판단한다.
     * 평일 + 정규 거래시간 + 공휴일 아님.
     *
     * @param now  현재 시각 (ZonedDateTime, 시간대 무관 — 내부에서 변환)
     * @param type 시장 유형
     * @return 개장 중이면 true
     */
    public static boolean isMarketOpen(ZonedDateTime now, MarketType type) {
        ZonedDateTime local = now.withZoneSameInstant(type.timezone());
        DayOfWeek dow = local.getDayOfWeek();

        // 주말 체크
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }

        // 공휴일 체크
        if (isHoliday(local.toLocalDate(), type)) {
            return false;
        }

        LocalTime time = local.toLocalTime();
        switch (type) {
            case KRX:
                return !time.isBefore(KRX_OPEN) && time.isBefore(KRX_CLOSE);
            case NYSE:
            case NASDAQ:
                return !time.isBefore(US_OPEN) && time.isBefore(US_CLOSE);
            default:
                return false;
        }
    }

    /**
     * 폐장까지 남은 시간(분)을 반환한다.
     * 개장 중이 아니면 0을 반환한다.
     *
     * @param now  현재 시각
     * @param type 시장 유형
     * @return 폐장까지 남은 분 (개장 중이 아니면 0)
     */
    public static long minutesToClose(ZonedDateTime now, MarketType type) {
        if (!isMarketOpen(now, type)) {
            return 0;
        }

        ZonedDateTime local = now.withZoneSameInstant(type.timezone());
        LocalTime time = local.toLocalTime();
        LocalTime closeTime;

        switch (type) {
            case KRX:
                closeTime = KRX_CLOSE;
                break;
            case NYSE:
            case NASDAQ:
                closeTime = US_CLOSE;
                break;
            default:
                return 0;
        }

        Duration remaining = Duration.between(time, closeTime);
        return remaining.toMinutes();
    }

    /**
     * 주어진 시각이 지정된 시간 윈도우 안에 있는지 판단한다.
     * 시장 시간대로 변환 후 비교한다.
     *
     * @param now    현재 시각 (ZonedDateTime)
     * @param type   시장 유형
     * @param startH 윈도우 시작 시
     * @param startM 윈도우 시작 분
     * @param endH   윈도우 종료 시
     * @param endM   윈도우 종료 분
     * @return 윈도우 내에 있으면 true
     */
    public static boolean isInWindow(ZonedDateTime now, MarketType type,
                                     int startH, int startM, int endH, int endM) {
        ZonedDateTime local = now.withZoneSameInstant(type.timezone());
        LocalTime time = local.toLocalTime();
        LocalTime start = LocalTime.of(startH, startM);
        LocalTime end = LocalTime.of(endH, endM);
        return !time.isBefore(start) && time.isBefore(end);
    }

    /**
     * 주어진 날짜가 공휴일인지 확인한다.
     *
     * @param date 확인할 날짜 (시장 로컬 날짜)
     * @param type 시장 유형
     * @return 공휴일이면 true
     */
    public static boolean isHoliday(LocalDate date, MarketType type) {
        switch (type) {
            case KRX:
                return KRX_HOLIDAYS.contains(date);
            case NYSE:
            case NASDAQ:
                return US_HOLIDAYS.contains(date);
            default:
                return false;
        }
    }
}
