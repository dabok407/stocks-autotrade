package com.example.stocks.strategy;

import com.example.stocks.market.StockCandle;

import java.util.List;

/**
 * [5] 하락 삼법형(Three Methods Bearish) + EMA20 필터 + ATR 모멘텀 검증
 *
 * 패턴:
 * - 1번: 장대 음봉(모멘텀, ATR 대비 충분한 크기)
 * - 2~k: 작은 캔들(2~4개)로, 모두 1번 캔들의 고가/저가 범위 안
 * - 마지막: 장대 음봉(모멘텀) + 종가가 1번 종가보다 낮음
 * - 추가 필터: 현재 종가 < EMA20 (하락 추세 확인)
 *
 * 현물 롱 only -> 포지션 보유 중이면 SELL(청산) 신호.
 */
public class ThreeMethodsBearishStrategy implements TradingStrategy {

    private static final double MIN_MOMENTUM_ATR_MULT = 1.0;

    @Override public StrategyType type() { return StrategyType.THREE_METHODS_BEARISH; }

    @Override
    public Signal evaluate(StrategyContext ctx) {
        if (ctx.candles == null || ctx.candles.size() < 30) return Signal.none();
        if (ctx.position == null || ctx.position.getQty() <= 0) return Signal.none();

        List<StockCandle> cs = ctx.candles;
        double atr = Indicators.atr(cs, 14);

        // 조정봉 2~4개 탐색
        for (int pull = 3; pull <= 5; pull++) {
            int total = pull + 2;
            if (cs.size() < total) continue;

            int base = cs.size() - total;
            StockCandle first = cs.get(base);
            StockCandle last = cs.get(cs.size()-1);

            // 첫 봉: 장대 음봉 (ATR 기반 모멘텀 검증)
            if (!CandlePatterns.isBearish(first) || !CandlePatterns.isMomentum(first, atr, MIN_MOMENTUM_ATR_MULT)) continue;
            // 마지막 봉: 장대 음봉 (ATR 기반 모멘텀 검증)
            if (!CandlePatterns.isBearish(last) || !CandlePatterns.isMomentum(last, atr, MIN_MOMENTUM_ATR_MULT)) continue;
            // 마지막 종가가 첫 봉 종가보다 낮아야 함 (하락 지속)
            if (last.trade_price >= first.trade_price) continue;

            // 중간 봉들: 첫 봉의 고가/저가 범위 내
            boolean insideAll = true;
            for (int i = base+1; i < cs.size()-1; i++) {
                StockCandle mid = cs.get(i);
                if (mid.high_price > first.high_price || mid.low_price < first.low_price) {
                    insideAll = false; break;
                }
            }
            if (!insideAll) continue;

            // EMA20 필터: 종가가 EMA20 아래에 있어야 함 (하락 추세 확인)
            double ema20 = Indicators.ema(cs, 20);
            if (!Double.isNaN(ema20) && last.trade_price >= ema20) continue;

            double score = 4.0;
            double firstBodyR = CandlePatterns.body(first) / CandlePatterns.range(first);
            if (firstBodyR >= 0.85) score += 1.5;
            else score += 0.5;
            double breakPct = first.trade_price > 0 ? (first.trade_price - last.trade_price) / first.trade_price * 100 : 0;
            if (breakPct >= 1.0) score += 2.0;
            else if (breakPct >= 0.3) score += 1.5;
            else score += 0.5;
            double lastBodyR = CandlePatterns.body(last) / CandlePatterns.range(last);
            if (lastBodyR >= 0.85) score += 1.5;
            else score += 0.5;
            return Signal.of(SignalAction.SELL, type(), "Three methods bearish + ATR momentum -> exit", Math.min(10.0, score));
        }

        return Signal.none();
    }
}
