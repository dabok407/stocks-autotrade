package com.example.stocks.strategy;

import com.example.stocks.config.StrategyProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 전략 구현체를 관리/조회하는 팩토리.
 * - UI/백테스트에서 strategyType을 선택하면 여기서 구현체를 반환합니다.
 *
 * 전략 확장 방법:
 * 1) StrategyType enum에 항목 추가
 * 2) TradingStrategy 구현체 추가
 * 3) 여기 StrategyFactory에 등록
 *
 * 그러면 대시보드/백테스트/로그(매매유형 컬럼)에 자동으로 노출됩니다.
 */
@Component
public class StrategyFactory {

    private final Map<StrategyType, TradingStrategy> strategies = new EnumMap<>(StrategyType.class);

    @Autowired
    public StrategyFactory(StrategyProperties cfg) {
        // 캔들 패턴 기반 전략
        strategies.put(StrategyType.BULLISH_ENGULFING_CONFIRM, new BullishEngulfingConfirmStrategy());
        strategies.put(StrategyType.BEARISH_ENGULFING, new BearishEngulfingStrategy());
        strategies.put(StrategyType.MORNING_STAR, new MorningStarStrategy());
        strategies.put(StrategyType.EVENING_STAR_SELL, new EveningStarSellStrategy());
        strategies.put(StrategyType.BULLISH_PINBAR_ORDERBLOCK, new BullishPinbarOrderblockStrategy());
        strategies.put(StrategyType.INSIDE_BAR_BREAKOUT, new InsideBarBreakoutStrategy());
        strategies.put(StrategyType.MOMENTUM_FVG_PULLBACK, new MomentumFvgPullbackStrategy());
        strategies.put(StrategyType.THREE_BLACK_CROWS_SELL, new ThreeBlackCrowsSellStrategy());
        strategies.put(StrategyType.THREE_METHODS_BEARISH, new ThreeMethodsBearishStrategy());

        // 지표 기반 전략
        strategies.put(StrategyType.REGIME_PULLBACK, new RegimeFilteredPullbackStrategy());
        strategies.put(StrategyType.ADAPTIVE_TREND_MOMENTUM, new AdaptiveTrendMomentumStrategy());
        strategies.put(StrategyType.EMA_RSI_TREND, new EmaRsiTrendStrategy());
        strategies.put(StrategyType.THREE_MARKET_PATTERN, new ThreeMarketPatternStrategy());
        strategies.put(StrategyType.BOLLINGER_SQUEEZE_BREAKOUT, new BollingerSqueezeBreakoutStrategy());
        strategies.put(StrategyType.TRIANGLE_CONVERGENCE, new TriangleConvergenceStrategy());
        strategies.put(StrategyType.MULTI_CONFIRM_MOMENTUM, new MultiConfirmMomentumStrategy());
    }

    public TradingStrategy get(StrategyType type) {
        TradingStrategy s = strategies.get(type);
        if (s == null) throw new IllegalArgumentException("Unknown strategyType: " + type);
        return s;
    }

    /**
     * 팩토리에 등록된(활성) 전략인지 확인.
     */
    public boolean isRegistered(StrategyType type) {
        return strategies.containsKey(type);
    }

    /**
     * 특정 전략을 오버라이드한 새 팩토리를 반환.
     * 원본은 변경하지 않음 (백테스트 파라미터 오버라이드용).
     */
    public StrategyFactory withOverride(StrategyType type, TradingStrategy override) {
        EnumMap<StrategyType, TradingStrategy> copy = new EnumMap<>(this.strategies);
        copy.put(type, override);
        return new StrategyFactory(copy);
    }

    /** 내부 복사용 — Spring이 사용할 수 없도록 EnumMap 파라미터 필수 */
    private StrategyFactory(EnumMap<StrategyType, TradingStrategy> source) {
        this.strategies.putAll(source);
    }
}
