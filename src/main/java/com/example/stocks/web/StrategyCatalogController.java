package com.example.stocks.web;

import com.example.stocks.strategy.StrategyType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Strategy catalog endpoint for FE to dynamically load strategy options.
 */
@RestController
public class StrategyCatalogController {

    @GetMapping("/api/strategies")
    public List<StrategyInfo> strategies() {
        List<StrategyInfo> out = new ArrayList<StrategyInfo>();
        for (StrategyType t : StrategyType.values()) {
            String role = t.isSellOnly() ? "SELL_ONLY" : (t.isSelfContained() ? "SELF_CONTAINED" : "BUY_ONLY");
            String label = displayName(t);
            out.add(new StrategyInfo(t.name(), label, description(t), role,
                    t.recommendedIntervalMin(), t.emaTrendFilterMode(), t.recommendedEmaPeriod()));
        }
        return out;
    }

    private String displayName(StrategyType t) {
        switch (t) {
            case BULLISH_ENGULFING_CONFIRM:
                return "상승 장악형 (Bullish Engulfing)";
            case BEARISH_ENGULFING:
                return "하락 장악형 (Sell)";
            case MORNING_STAR:
                return "모닝스타 (Morning Star)";
            case EVENING_STAR_SELL:
                return "이브닝스타 (Sell)";
            case REGIME_PULLBACK:
                return "추세 필터 풀백 (ATR)";
            case ADAPTIVE_TREND_MOMENTUM:
                return "적응형 추세 모멘텀 (ATM)";
            case EMA_RSI_TREND:
                return "EMA-RSI 추세추종 (ERT)";
            case THREE_MARKET_PATTERN:
                return "쓰리마켓 패턴 (3MKT)";
            case BOLLINGER_SQUEEZE_BREAKOUT:
                return "볼린저 스퀴즈 돌파 (BSB)";
            case TRIANGLE_CONVERGENCE:
                return "삼각수렴 돌파 (TRI)";
            case MULTI_CONFIRM_MOMENTUM:
                return "다중확인 모멘텀 (MCM)";
            case BULLISH_PINBAR_ORDERBLOCK:
                return "강세 핀바 (Pinbar OB)";
            case INSIDE_BAR_BREAKOUT:
                return "인사이드바 돌파 (IB)";
            case MOMENTUM_FVG_PULLBACK:
                return "모멘텀 FVG 풀백";
            case THREE_BLACK_CROWS_SELL:
                return "흑삼병 (Sell)";
            case THREE_METHODS_BEARISH:
                return "하락 삼법 (Sell)";
        }
        return t.name();
    }

    private String description(StrategyType t) {
        switch (t) {
            case BULLISH_ENGULFING_CONFIRM:
                return "상승 장악형 3캔들 패턴. 하락->상승 반전 신호. EMA 트렌드 필터 지원.";
            case BEARISH_ENGULFING:
                return "하락 장악형. 매도 전용: 강한 반전 시 보유 포지션 청산.";
            case MORNING_STAR:
                return "모닝스타 3캔들 반전 (장대음봉->도지->장대양봉). 하락 추세 끝 진입.";
            case EVENING_STAR_SELL:
                return "이브닝스타 반전. 매도 전용: 상승 끝 반전 시 청산.";
            case REGIME_PULLBACK:
                return "EMA200/50 추세 정렬 + ADX 강도 + RSI/BB 눌림 진입. ATR 기반 TP/SL + 트레일링.";
            case ADAPTIVE_TREND_MOMENTUM:
                return "5중 확인 (EMA 정렬 + MACD + 거래량 + 눌림 + 반등). Chandelier Exit 청산.";
            case EMA_RSI_TREND:
                return "EMA 다중 정렬 + RSI 풀백(35~55) + 돌파 매수. 높은 승률 추세추종. BUY-ONLY.";
            case THREE_MARKET_PATTERN:
                return "박스권(~10%) + 이중 가짜돌파 감지 -> 신고가 돌파 매수. 주식 변동성 최적화.";
            case BOLLINGER_SQUEEZE_BREAKOUT:
                return "BB 스퀴즈(에너지 축적) -> 확장 + 상단 돌파 + 거래량 급증. 10SMA 이탈 청산.";
            case TRIANGLE_CONVERGENCE:
                return "삼각수렴 (하강 고점 + 상승 저점) -> 상단 추세선 돌파. 목표가=밑변 높이.";
            case MULTI_CONFIRM_MOMENTUM:
                return "3필수 (EMA+RSI+MACD) + 5보너스 = 고확신 스코어 >= 6.5. RSI 상한 75.";
            case BULLISH_PINBAR_ORDERBLOCK:
                return "강세 핀바 + 지역 저점 지지. 긴 아래꼬리의 강한 반등 신호.";
            case INSIDE_BAR_BREAKOUT:
                return "인사이드바 압축 -> 상단 돌파 + 거래량 확인. 에너지 축적 후 돌파.";
            case MOMENTUM_FVG_PULLBACK:
                return "장대양봉이 만든 FVG(가격 갭) -> 갭 되돌림 구간에서 진입. ATR 모멘텀 검증.";
            case THREE_BLACK_CROWS_SELL:
                return "3연속 음봉 + 거래량 필터. 매도 전용: 보유 포지션 청산.";
            case THREE_METHODS_BEARISH:
                return "하락 삼법 (장대음봉 + 조정 + 하락 지속). ATR 모멘텀 + EMA20 필터. 매도 전용.";
        }
        return "";
    }

    public static class StrategyInfo {
        public String key;
        public String label;
        public String desc;
        public String role;
        public int recommendedInterval;
        public String emaFilterMode;
        public int recommendedEma;

        public StrategyInfo(String key, String label, String desc, String role,
                            int recommendedInterval, String emaFilterMode, int recommendedEma) {
            this.key = key;
            this.label = label;
            this.desc = desc;
            this.role = role;
            this.recommendedInterval = recommendedInterval;
            this.emaFilterMode = emaFilterMode;
            this.recommendedEma = recommendedEma;
        }
    }
}
