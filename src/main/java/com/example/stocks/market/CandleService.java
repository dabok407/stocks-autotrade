package com.example.stocks.market;

import com.example.stocks.exchange.ExchangeAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 주식 캔들 데이터 서비스.
 * ExchangeAdapter를 통해 증권사에 독립적으로 캔들 데이터를 조회한다.
 */
@Service
public class CandleService {

    private final ExchangeAdapter exchange;

    @Autowired
    public CandleService(ExchangeAdapter exchange) {
        this.exchange = exchange;
    }

    /**
     * 분봉 캔들 조회.
     *
     * @param symbol    종목 심볼 (예: "005930", "AAPL")
     * @param marketType 시장 유형
     * @param unitMin   분봉 단위 (1, 3, 5, 10, 15, 30, 60)
     * @param count     조회 개수
     * @param toIsoUtc  기준 시각 (UTC ISO, null이면 현재) - 현재 미사용, 향후 확장용
     * @return 오래된 -> 최신 순 정렬된 캔들 리스트
     */
    public List<StockCandle> getMinuteCandles(String symbol, MarketType marketType, int unitMin, int count, String toIsoUtc) {
        List<StockCandle> raw = exchange.getMinuteCandles(symbol, marketType, unitMin, count);
        sortAscending(raw);
        return raw;
    }

    /**
     * 일봉 캔들 조회.
     *
     * @param symbol    종목 심볼
     * @param marketType 시장 유형
     * @param count     조회 개수
     * @param toIsoUtc  기준 시각 (UTC ISO, null이면 현재)
     * @return 오래된 -> 최신 순 정렬된 캔들 리스트
     */
    public List<StockCandle> getDayCandles(String symbol, MarketType marketType, int count, String toIsoUtc) {
        List<StockCandle> raw = exchange.getDayCandles(symbol, marketType, count);
        sortAscending(raw);
        return raw;
    }

    /**
     * N일 과거 데이터를 페이지네이션으로 가져온다.
     *
     * @param symbol    종목 심볼
     * @param marketType 시장 유형
     * @param unitMin   분봉 단위 (일봉은 1440)
     * @param days      조회할 과거 일수
     * @return 오래된 -> 최신 순 정렬된 캔들 리스트 (중복 제거)
     */
    public List<StockCandle> fetchLookback(String symbol, MarketType marketType, int unitMin, int days) {
        if (unitMin >= 1440) {
            int need = Math.max(1, days);
            List<StockCandle> all = new ArrayList<StockCandle>(need);
            String to = null;

            while (all.size() < need) {
                List<StockCandle> chunk = getDayCandles(symbol, marketType, 200, to);
                if (chunk.isEmpty()) break;

                all.addAll(chunk);

                StockCandle last = chunk.get(chunk.size() - 1);
                to = last.candle_date_time_utc;

                if (chunk.size() < 200) break;
            }

            // KIS API는 최신->오래된 순 반환 가능 -> 오래된->최신 정렬
            sortAscending(all);

            if (all.size() > need) {
                return new ArrayList<StockCandle>(all.subList(all.size() - need, all.size()));
            }
            return all;
        }

        // 주식 시장은 하루 거래시간이 제한적 (KRX: 6.5h, NYSE: 6.5h)
        // 거래시간 기준으로 필요 캔들 수 계산
        int tradingMinutesPerDay = 390; // 6.5h * 60min (KRX, NYSE 모두 유사)
        int need = (int) ((days * (long) tradingMinutesPerDay) / unitMin);
        List<StockCandle> all = new ArrayList<StockCandle>(need);
        Set<String> seen = new HashSet<String>();
        String to = null;

        for (int page = 0; page < 10 && all.size() < need; page++) {
            int fetch = Math.min(need - all.size(), 200);
            List<StockCandle> chunk = getMinuteCandles(symbol, marketType, unitMin, fetch, to);
            if (chunk.isEmpty()) break;

            for (StockCandle c : chunk) {
                String key = c.candle_date_time_utc;
                if (key != null && seen.add(key)) {
                    all.add(c);
                }
            }

            StockCandle last = chunk.get(chunk.size() - 1);
            to = last.candle_date_time_utc;

            if (chunk.size() < fetch) break;
        }

        sortAscending(all);

        if (all.size() > need) {
            return new ArrayList<StockCandle>(all.subList(all.size() - need, all.size()));
        }
        return all;
    }

    private void sortAscending(List<StockCandle> list) {
        Collections.sort(list, new Comparator<StockCandle>() {
            public int compare(StockCandle a, StockCandle b) {
                if (a.candle_date_time_utc == null && b.candle_date_time_utc == null) return 0;
                if (a.candle_date_time_utc == null) return -1;
                if (b.candle_date_time_utc == null) return 1;
                return a.candle_date_time_utc.compareTo(b.candle_date_time_utc);
            }
        });
    }
}
