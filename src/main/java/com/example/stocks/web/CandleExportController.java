package com.example.stocks.web;

import com.example.stocks.exchange.ExchangeAdapter;
import com.example.stocks.exchange.kis.KisExchangeAdapter;
import com.example.stocks.kis.KisPublicClient;
import com.example.stocks.market.CandleService;
import com.example.stocks.market.MarketType;
import com.example.stocks.market.StockCandle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 캔들 데이터 CSV 다운로드 API.
 * 인증 불필요 (permitAll).
 */
@RestController
@RequestMapping("/api/candle-export")
public class CandleExportController {

    private static final Logger log = LoggerFactory.getLogger(CandleExportController.class);

    private final CandleService candleService;
    private final ExchangeAdapter exchangeAdapter;

    public CandleExportController(CandleService candleService, ExchangeAdapter exchangeAdapter) {
        this.candleService = candleService;
        this.exchangeAdapter = exchangeAdapter;
    }

    /**
     * 단일 종목 캔들 CSV 다운로드 (대량, 시간대 반복 호출).
     */
    @GetMapping
    public ResponseEntity<String> exportSingle(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "5") int interval,
            @RequestParam(defaultValue = "30") int days) {

        log.info("캔들 CSV: symbol={}, interval={}, days={}", symbol, interval, days);

        MarketType marketType = guessMarketType(symbol);
        List<StockCandle> candles;

        if (marketType == MarketType.KRX && interval < 1440) {
            candles = fetchDomesticMinuteCandlesDeep(symbol, interval, days);
        } else {
            candles = candleService.fetchLookback(symbol, marketType, interval, days);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("timestamp,open,high,low,close,volume\n");
        for (StockCandle c : candles) {
            sb.append(c.candle_date_time_utc).append(',')
              .append(fmtPrice(c.opening_price)).append(',')
              .append(fmtPrice(c.high_price)).append(',')
              .append(fmtPrice(c.low_price)).append(',')
              .append(fmtPrice(c.trade_price)).append(',')
              .append(fmtVol(c.candle_acc_trade_volume)).append('\n');
        }

        log.info("캔들 CSV 완료: {} {}건", symbol, candles.size());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + symbol + "_" + interval + "m_" + days + "d.csv\"")
                .body(sb.toString());
    }

    /**
     * 다중 종목 캔들 CSV 벌크 다운로드.
     */
    @GetMapping("/bulk")
    public ResponseEntity<String> exportBulk(
            @RequestParam String symbols,
            @RequestParam(defaultValue = "5") int interval,
            @RequestParam(defaultValue = "30") int days) {

        String[] symbolArr = symbols.split(",");
        log.info("벌크 CSV: symbols={}, interval={}, days={}", symbolArr.length, interval, days);

        StringBuilder sb = new StringBuilder();
        sb.append("symbol,timestamp,open,high,low,close,volume\n");

        for (int i = 0; i < symbolArr.length; i++) {
            String symbol = symbolArr[i].trim();
            if (symbol.isEmpty()) continue;

            MarketType marketType = guessMarketType(symbol);
            List<StockCandle> candles;

            if (marketType == MarketType.KRX && interval < 1440) {
                candles = fetchDomesticMinuteCandlesDeep(symbol, interval, days);
            } else {
                candles = candleService.fetchLookback(symbol, marketType, interval, days);
            }

            for (StockCandle c : candles) {
                sb.append(symbol).append(',')
                  .append(c.candle_date_time_utc).append(',')
                  .append(fmtPrice(c.opening_price)).append(',')
                  .append(fmtPrice(c.high_price)).append(',')
                  .append(fmtPrice(c.low_price)).append(',')
                  .append(fmtPrice(c.trade_price)).append(',')
                  .append(fmtVol(c.candle_acc_trade_volume)).append('\n');
            }

            log.info("벌크 CSV 진행: {}/{} {} {}건", i + 1, symbolArr.length, symbol, candles.size());

            if (i < symbolArr.length - 1) {
                try { Thread.sleep(500); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); break;
                }
            }
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"bulk_" + interval + "m_" + days + "d.csv\"")
                .body(sb.toString());
    }

    /**
     * 거래대금 TOP N 종목 코드 리스트 조회.
     */
    @GetMapping("/top-symbols")
    public ResponseEntity<List<String>> topSymbols(
            @RequestParam(defaultValue = "30") int topN) {
        List<String> symbols = exchangeAdapter.getTopSymbolsByVolume(topN, MarketType.KRX);
        return ResponseEntity.ok(symbols);
    }

    // =========================================================================
    // KRX 분봉 대량 다운로드 (시간대 역순 반복 호출)
    // =========================================================================

    /**
     * KIS 국내 분봉 API를 hourStr을 바꿔가며 반복 호출하여 대량 캔들 수집.
     * 1회 호출 = 최대 30개 캔들, hourStr = 해당 시간 이전 데이터 반환.
     */
    private List<StockCandle> fetchDomesticMinuteCandlesDeep(String symbol, int interval, int days) {
        KisPublicClient publicClient = ((KisExchangeAdapter) exchangeAdapter).getPublicClient();

        int tradingMinPerDay = 390; // 6.5h
        int candlesPerDay = tradingMinPerDay / Math.max(interval, 1);
        int totalNeed = candlesPerDay * days;

        TreeSet<String> seen = new TreeSet<String>();
        List<StockCandle> all = new ArrayList<StockCandle>();

        // 마지막으로 받은 캔들의 시간 (역순 페이지네이션용)
        String lastHourStr = "153000"; // 장 마감 시각부터 시작

        for (int page = 0; page < totalNeed / 25 + 10 && all.size() < totalNeed; page++) {
            try { Thread.sleep(350); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); break;
            }

            List<Map<String, Object>> rawList = publicClient.getDomesticMinuteCandles(symbol, lastHourStr);
            if (rawList == null || rawList.isEmpty()) break;

            int addedThisPage = 0;
            String oldestTime = null;

            for (Map<String, Object> raw : rawList) {
                String date = strSafe(raw.get("stck_bsop_date"));
                String time = strSafe(raw.get("stck_cntg_hour"));
                if (date.isEmpty() || time.isEmpty()) continue;

                String timestamp = date.substring(0, 4) + "-" + date.substring(4, 6) + "-"
                        + date.substring(6, 8) + "T" + time.substring(0, 2) + ":" + time.substring(2, 4)
                        + ":" + time.substring(4, 6);

                if (!seen.add(timestamp)) continue; // 중복 제거

                StockCandle c = new StockCandle();
                c.symbol = symbol;
                c.marketType = MarketType.KRX.name();
                c.candle_date_time_utc = timestamp;
                c.opening_price = parseDouble(raw.get("stck_oprc"));
                c.high_price = parseDouble(raw.get("stck_hgpr"));
                c.low_price = parseDouble(raw.get("stck_lwpr"));
                c.trade_price = parseDouble(raw.get("stck_prpr"));
                c.candle_acc_trade_volume = parseDouble(raw.get("cntg_vol"));

                if (c.trade_price > 0) {
                    all.add(c);
                    addedThisPage++;
                }

                // 가장 오래된 시간 추적 (KIS는 최신→과거 순 반환)
                oldestTime = time;
            }

            if (addedThisPage == 0) break; // 새 데이터 없으면 종료

            // 다음 호출: 가장 오래된 캔들 시간으로 hourStr 설정
            if (oldestTime != null) {
                lastHourStr = oldestTime;
            }

            if (rawList.size() < 25) break; // 마지막 페이지
        }

        // 시간순 정렬 (오래된→최신)
        Collections.sort(all, new Comparator<StockCandle>() {
            public int compare(StockCandle a, StockCandle b) {
                if (a.candle_date_time_utc == null) return -1;
                if (b.candle_date_time_utc == null) return 1;
                return a.candle_date_time_utc.compareTo(b.candle_date_time_utc);
            }
        });

        log.info("대량 분봉 다운로드 완료: {} {}건 (요청 {}일)", symbol, all.size(), days);
        return all;
    }

    // =========================================================================
    // 유틸
    // =========================================================================

    private MarketType guessMarketType(String symbol) {
        return (symbol != null && symbol.matches("\\d+")) ? MarketType.KRX : MarketType.NYSE;
    }

    private String fmtPrice(double p) {
        return (p == (long) p) ? String.valueOf((long) p) : String.valueOf(p);
    }

    private String fmtVol(double v) {
        return (v == (long) v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private double parseDouble(Object val) {
        if (val == null) return 0.0;
        try { return Double.parseDouble(val.toString().trim()); }
        catch (NumberFormatException e) { return 0.0; }
    }

    private String strSafe(Object val) {
        return val != null ? val.toString().trim() : "";
    }
}
