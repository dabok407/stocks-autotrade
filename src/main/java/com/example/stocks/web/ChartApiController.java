package com.example.stocks.web;

import com.example.stocks.market.CandleService;
import com.example.stocks.market.MarketType;
import com.example.stocks.market.StockCandle;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 거래 이벤트 시점의 캔들 데이터를 차트에 그리기 위한 API.
 * FE에서 trade row 클릭 시 호출하여 해당 시점 전후 캔들을 반환한다.
 */
@RestController
public class ChartApiController {

    private final CandleService candleService;
    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    public ChartApiController(CandleService candleService) {
        this.candleService = candleService;
    }

    /**
     * 특정 시각 전후의 캔들을 반환한다.
     *
     * @param symbol    종목 심볼 (예: "005930")
     * @param marketType 시장 유형 (KRX, NYSE 등). 기본 KRX
     * @param unit      분봉 단위 (5, 15, 30, 60 등)
     * @param tsEpochMs 기준 시각 (밀리초 epoch) — 거래 이벤트 발생 시각
     * @param count     총 캔들 수 (기본 80). 기준 시각 전후로 분배됨.
     * @return 캔들 배열 (오래된 → 최신 순)
     */
    @GetMapping("/api/chart/candles")
    public List<Map<String, Object>> candlesAround(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "KRX") String marketType,
            @RequestParam(defaultValue = "5") int unit,
            @RequestParam long tsEpochMs,
            @RequestParam(defaultValue = "80") int count) {

        if (unit <= 0) unit = 5;
        if (count <= 0) count = 80;
        count = Math.min(count, 200);

        MarketType mt;
        try {
            mt = MarketType.valueOf(marketType);
        } catch (Exception e) {
            mt = MarketType.KRX;
        }

        // 기준 시각 + 캔들 30개분 뒤까지를 'to'로 잡아서 기준 전후 캔들을 확보
        long afterCandles = 30;
        long toEpochMs = tsEpochMs + (afterCandles * unit * 60_000L);
        String toIso = ISO_FMT.format(Instant.ofEpochMilli(toEpochMs));

        List<StockCandle> raw = candleService.getMinuteCandles(symbol, mt, unit, count, toIso);
        if (raw == null) raw = Collections.emptyList();

        // 오래된 → 최신 순 정렬
        List<StockCandle> sorted = new ArrayList<StockCandle>(raw);
        Collections.sort(sorted, new Comparator<StockCandle>() {
            public int compare(StockCandle a, StockCandle b) {
                if (a.candle_date_time_utc == null && b.candle_date_time_utc == null) return 0;
                if (a.candle_date_time_utc == null) return -1;
                if (b.candle_date_time_utc == null) return 1;
                return a.candle_date_time_utc.compareTo(b.candle_date_time_utc);
            }
        });

        // lightweight-charts가 기대하는 형태로 변환
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (StockCandle c : sorted) {
            if (c.candle_date_time_utc == null) continue;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            // epoch seconds (lightweight-charts는 time을 unix timestamp 초 단위로 받음)
            try {
                Instant inst = Instant.parse(c.candle_date_time_utc + "Z");
                m.put("time", inst.getEpochSecond());
            } catch (Exception e) {
                // 파싱 실패 시 스킵
                continue;
            }
            m.put("open", c.opening_price);
            m.put("high", c.high_price);
            m.put("low", c.low_price);
            m.put("close", c.trade_price);
            m.put("volume", c.candle_acc_trade_volume);
            out.add(m);
        }

        return out;
    }
}
