package com.example.stocks.strategy;

/**
 * 주식 매매 전략 유형.
 *
 * 암호화폐 전용 전략(스캘핑, 오프닝 돌파 등)과 폐기된 전략을 제외하고
 * 주식 시장에 적합한 11개 전략만 포함.
 */
public enum StrategyType {

    // === 지표 기반 (매수+매도 통합, self-contained) ===

    /** [1] 추세 필터 + 눌림 매수 + ATR 손절/익절 (60분) */
    REGIME_PULLBACK,

    /** [2] 5중 확인 추세 모멘텀 + Chandelier Exit (60분) */
    ADAPTIVE_TREND_MOMENTUM,

    /** [3] EMA-RSI 추세 추종 (BUY-ONLY: RSI 눌림 + 돌파 매수, 60분) */
    EMA_RSI_TREND,

    /** [4] 쓰리 마켓 패턴: 이중 가짜돌파 -> 신고가 돌파 매수 (60분) */
    THREE_MARKET_PATTERN,

    /** [5] 볼린저 밴드 스퀴즈 돌파 (60분) */
    BOLLINGER_SQUEEZE_BREAKOUT,

    /** [6] 삼각수렴 돌파 (60분) */
    TRIANGLE_CONVERGENCE,

    /** [7] 다중 확인 고확신 모멘텀 (30~60분) */
    MULTI_CONFIRM_MOMENTUM,

    // === 캔들 패턴 기반 ===

    /** [8] 상승 장악형 + 3번째 양봉 확인 (240분) */
    BULLISH_ENGULFING_CONFIRM,

    /** [9] 모닝스타 반전 (240분) */
    MORNING_STAR,

    /** [10] 하락 장악형 - 매도 전용 (240분) */
    BEARISH_ENGULFING,

    /** [11] 이브닝스타 - 매도 전용 (240분) */
    EVENING_STAR_SELL,

    // === 추가 캔들 패턴 기반 ===

    /** [12] 강세 핀바 + 지지영역 (240분) */
    BULLISH_PINBAR_ORDERBLOCK,

    /** [13] 인사이드바 돌파 + 거래량 (240분) */
    INSIDE_BAR_BREAKOUT,

    /** [14] 모멘텀 FVG 되돌림 매수 (240분) */
    MOMENTUM_FVG_PULLBACK,

    /** [15] 흑삼병 - 매도 전용 (60분) */
    THREE_BLACK_CROWS_SELL,

    /** [16] 하락 삼법형 - 매도 전용 (60분) */
    THREE_METHODS_BEARISH;

    /**
     * 매도 전용 전략인지 판별.
     */
    public boolean isSellOnly() {
        switch (this) {
            case BEARISH_ENGULFING:
            case EVENING_STAR_SELL:
            case THREE_BLACK_CROWS_SELL:
            case THREE_METHODS_BEARISH:
                return true;
            default:
                return false;
        }
    }

    /**
     * 자급자족(매수+매도 통합) 전략인지 판별.
     */
    public boolean isSelfContained() {
        switch (this) {
            case REGIME_PULLBACK:
            case ADAPTIVE_TREND_MOMENTUM:
            case THREE_MARKET_PATTERN:
            case BOLLINGER_SQUEEZE_BREAKOUT:
            case TRIANGLE_CONVERGENCE:
            case MULTI_CONFIRM_MOMENTUM:
                return true;
            default:
                return false;
        }
    }

    /**
     * 매수 전용 전략인지 판별.
     */
    public boolean isBuyOnly() {
        return !isSellOnly() && !isSelfContained();
    }

    /**
     * EMA 트렌드 필터 설정 모드.
     * CONFIGURABLE: 사용자가 EMA 기간을 선택 가능 (기본 50)
     * INTERNAL: 전략 내부에서 다중 EMA를 자체 관리 (설정 불가)
     * NONE: EMA 트렌드 필터 미사용 (매도 전용 등)
     */
    public String emaTrendFilterMode() {
        switch (this) {
            case BULLISH_ENGULFING_CONFIRM:
            case MORNING_STAR:
            case BULLISH_PINBAR_ORDERBLOCK:
            case INSIDE_BAR_BREAKOUT:
            case MOMENTUM_FVG_PULLBACK:
                return "CONFIGURABLE";
            case REGIME_PULLBACK:
            case ADAPTIVE_TREND_MOMENTUM:
            case EMA_RSI_TREND:
            case THREE_MARKET_PATTERN:
            case BOLLINGER_SQUEEZE_BREAKOUT:
            case TRIANGLE_CONVERGENCE:
            case MULTI_CONFIRM_MOMENTUM:
                return "INTERNAL";
            default:
                return "NONE";
        }
    }

    /**
     * EMA 트렌드 필터 권장 기간 (CONFIGURABLE 전략 전용).
     */
    public int recommendedEmaPeriod() {
        return "CONFIGURABLE".equals(emaTrendFilterMode()) ? 50 : 0;
    }

    /**
     * 한글 전략명.
     */
    public String displayName() {
        switch (this) {
            case REGIME_PULLBACK:           return "추세 필터 풀백";
            case ADAPTIVE_TREND_MOMENTUM:   return "적응형 추세 모멘텀";
            case EMA_RSI_TREND:             return "EMA-RSI 추세추종";
            case THREE_MARKET_PATTERN:      return "쓰리마켓 패턴";
            case BOLLINGER_SQUEEZE_BREAKOUT: return "볼린저 스퀴즈 돌파";
            case TRIANGLE_CONVERGENCE:      return "삼각수렴 돌파";
            case MULTI_CONFIRM_MOMENTUM:    return "다중확인 모멘텀";
            case BULLISH_ENGULFING_CONFIRM: return "상승 장악형";
            case MORNING_STAR:              return "모닝스타";
            case BEARISH_ENGULFING:         return "하락 장악형";
            case EVENING_STAR_SELL:         return "이브닝스타";
            case BULLISH_PINBAR_ORDERBLOCK: return "강세 핀바";
            case INSIDE_BAR_BREAKOUT:       return "인사이드바 돌파";
            case MOMENTUM_FVG_PULLBACK:     return "모멘텀 FVG 풀백";
            case THREE_BLACK_CROWS_SELL:    return "흑삼병";
            case THREE_METHODS_BEARISH:     return "하락 삼법";
            default:                        return name();
        }
    }

    /**
     * 전략별 권장 캔들 인터벌(분).
     * - 캔들 패턴 전략: 4시간(240분)
     * - 지표 기반 전략: 60분
     * - 다중 확인 모멘텀: 30분
     */
    public int recommendedIntervalMin() {
        switch (this) {
            // 캔들 패턴 전략: 4시간봉 권장
            case BULLISH_ENGULFING_CONFIRM:
            case BEARISH_ENGULFING:
            case MORNING_STAR:
            case EVENING_STAR_SELL:
            case BULLISH_PINBAR_ORDERBLOCK:
            case INSIDE_BAR_BREAKOUT:
            case MOMENTUM_FVG_PULLBACK:
                return 240;

            // 다중 확인 고확신 모멘텀
            case MULTI_CONFIRM_MOMENTUM:
                return 30;

            // 지표 기반 전략: 60분봉 권장
            case REGIME_PULLBACK:
            case ADAPTIVE_TREND_MOMENTUM:
            case EMA_RSI_TREND:
            case THREE_MARKET_PATTERN:
            case BOLLINGER_SQUEEZE_BREAKOUT:
            case TRIANGLE_CONVERGENCE:
            default:
                return 60;
        }
    }
}
