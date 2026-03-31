package com.example.stocks.kis;

import com.example.stocks.config.KisProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * KIS public (market data) API client.
 * Provides domestic/overseas candle data and current price quotes.
 */
@Component
public class KisPublicClient {

    private static final Logger log = LoggerFactory.getLogger(KisPublicClient.class);

    private static final int MAX_RETRIES = 3;
    private static final long BASE_BACKOFF_MS = 250L;

    // tr_id constants
    private static final String TR_DOMESTIC_MINUTE_CANDLE = "FHKST03010200";
    private static final String TR_OVERSEAS_MINUTE_CANDLE = "HHDFS76950200";
    private static final String TR_DOMESTIC_PRICE = "FHKST01010100";
    private static final String TR_OVERSEAS_PRICE = "HHDFS76200200";
    private static final String TR_DOMESTIC_VOLUME_RANK = "FHPST01710000";
    private static final String TR_DOMESTIC_DAY_CANDLE = "FHKST03010100";
    private static final String TR_OVERSEAS_DAY_CANDLE = "HHDFS76240000";

    private final KisAuth auth;
    private final RestTemplate restTemplate;
    private final KisProperties props;

    public KisPublicClient(KisAuth auth, RestTemplate restTemplate, KisProperties props) {
        this.auth = auth;
        this.restTemplate = restTemplate;
        this.props = props;
    }

    // =====================================================================
    // Domestic (KRX) minute candles
    // =====================================================================

    /**
     * Fetches domestic stock minute candles.
     *
     * @param stockCode KRX stock code (e.g. "005930")
     * @param hourStr   time string in HHMMSS format (e.g. "153000")
     * @return list of candle data maps (max 30 per call)
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getDomesticMinuteCandles(String stockCode, String hourStr) {
        String url = UriComponentsBuilder.fromHttpUrl(props.getEffectiveBaseUrl())
                .path("/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice")
                .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                .queryParam("FID_INPUT_ISCD", stockCode)
                .queryParam("FID_INPUT_HOUR_1", hourStr)
                .queryParam("FID_PW_DATA_INCU_YN", "Y")
                .queryParam("FID_ETC_CLS_CODE", "")
                .build().toUriString();

        HttpHeaders headers = auth.buildHeaders(TR_DOMESTIC_MINUTE_CANDLE);
        Map<String, Object> body = callWithRetry(url, HttpMethod.GET, new HttpEntity<>(headers));
        if (body == null) {
            return Collections.emptyList();
        }

        Object output2 = body.get("output2");
        if (output2 instanceof List) {
            return (List<Map<String, Object>>) output2;
        }
        return Collections.emptyList();
    }

    // =====================================================================
    // Overseas minute candles
    // =====================================================================

    /**
     * Fetches overseas stock minute candles.
     *
     * @param exchangeCode exchange code (NAS, NYS, AMS, etc.)
     * @param symbol       ticker symbol (e.g. "AAPL")
     * @param minutes      candle interval in minutes
     * @param count        number of records (max 120)
     * @return list of candle data maps
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getOverseasMinuteCandles(String exchangeCode, String symbol,
                                                              int minutes, int count) {
        String url = UriComponentsBuilder.fromHttpUrl(props.getEffectiveBaseUrl())
                .path("/uapi/overseas-price/v1/quotations/inquire-time-itemchartprice")
                .queryParam("AUTH", "")
                .queryParam("EXCD", exchangeCode)
                .queryParam("SYMB", symbol)
                .queryParam("NMIN", String.valueOf(minutes))
                .queryParam("PINC", "1")
                .queryParam("NEXT", "")
                .queryParam("NREC", String.valueOf(Math.min(count, 120)))
                .queryParam("FILL", "")
                .queryParam("KEYB", "")
                .build().toUriString();

        HttpHeaders headers = auth.buildHeaders(TR_OVERSEAS_MINUTE_CANDLE);
        Map<String, Object> body = callWithRetry(url, HttpMethod.GET, new HttpEntity<>(headers));
        if (body == null) {
            return Collections.emptyList();
        }

        Object output2 = body.get("output2");
        if (output2 instanceof List) {
            return (List<Map<String, Object>>) output2;
        }
        return Collections.emptyList();
    }

    // =====================================================================
    // Domestic current price
    // =====================================================================

    /**
     * Fetches current price for a domestic stock.
     *
     * @param stockCode KRX stock code (e.g. "005930")
     * @return price data map, or empty map on failure
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getDomesticCurrentPrice(String stockCode) {
        String url = UriComponentsBuilder.fromHttpUrl(props.getEffectiveBaseUrl())
                .path("/uapi/domestic-stock/v1/quotations/inquire-price")
                .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                .queryParam("FID_INPUT_ISCD", stockCode)
                .build().toUriString();

        HttpHeaders headers = auth.buildHeaders(TR_DOMESTIC_PRICE);
        Map<String, Object> body = callWithRetry(url, HttpMethod.GET, new HttpEntity<>(headers));
        if (body == null) {
            return Collections.emptyMap();
        }

        Object output = body.get("output");
        if (output instanceof Map) {
            return (Map<String, Object>) output;
        }
        return Collections.emptyMap();
    }

    // =====================================================================
    // Overseas current price
    // =====================================================================

    /**
     * Fetches current price for an overseas stock.
     *
     * @param exchangeCode exchange code (NAS, NYS, etc.)
     * @param symbol       ticker symbol (e.g. "AAPL")
     * @return price data map, or empty map on failure
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getOverseasCurrentPrice(String exchangeCode, String symbol) {
        String url = UriComponentsBuilder.fromHttpUrl(props.getEffectiveBaseUrl())
                .path("/uapi/overseas-price/v1/quotations/price")
                .queryParam("AUTH", "")
                .queryParam("EXCD", exchangeCode)
                .queryParam("SYMB", symbol)
                .build().toUriString();

        HttpHeaders headers = auth.buildHeaders(TR_OVERSEAS_PRICE);
        Map<String, Object> body = callWithRetry(url, HttpMethod.GET, new HttpEntity<>(headers));
        if (body == null) {
            return Collections.emptyMap();
        }

        Object output = body.get("output");
        if (output instanceof Map) {
            return (Map<String, Object>) output;
        }
        return Collections.emptyMap();
    }

    // =====================================================================
    // Domestic day candles (일봉)
    // =====================================================================

    /**
     * Fetches domestic stock daily candles.
     *
     * @param stockCode KRX stock code (e.g. "005930")
     * @param startDate start date (yyyyMMdd)
     * @param endDate   end date (yyyyMMdd)
     * @return list of daily candle data maps (max 100 per call)
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getDomesticDayCandles(String stockCode, String startDate, String endDate) {
        String url = UriComponentsBuilder.fromHttpUrl(props.getEffectiveBaseUrl())
                .path("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice")
                .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                .queryParam("FID_INPUT_ISCD", stockCode)
                .queryParam("FID_INPUT_DATE_1", startDate)
                .queryParam("FID_INPUT_DATE_2", endDate)
                .queryParam("FID_PERIOD_DIV_CODE", "D")  // D=일
                .queryParam("FID_ORG_ADJ_PRC", "0")       // 수정주가 반영
                .build().toUriString();

        HttpHeaders headers = auth.buildHeaders(TR_DOMESTIC_DAY_CANDLE);
        Map<String, Object> body = callWithRetry(url, HttpMethod.GET, new HttpEntity<>(headers));
        if (body == null) {
            return Collections.emptyList();
        }

        Object output2 = body.get("output2");
        if (output2 instanceof List) {
            return (List<Map<String, Object>>) output2;
        }
        return Collections.emptyList();
    }

    /**
     * Fetches overseas stock daily candles.
     *
     * @param exchangeCode exchange code (NAS, NYS, etc.)
     * @param symbol       ticker symbol (e.g. "AAPL")
     * @param startDate    start date (yyyyMMdd)
     * @param endDate      end date (yyyyMMdd)
     * @return list of daily candle data maps
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getOverseasDayCandles(String exchangeCode, String symbol,
                                                           String startDate, String endDate) {
        String url = UriComponentsBuilder.fromHttpUrl(props.getEffectiveBaseUrl())
                .path("/uapi/overseas-price/v1/quotations/dailyprice")
                .queryParam("AUTH", "")
                .queryParam("EXCD", exchangeCode)
                .queryParam("SYMB", symbol)
                .queryParam("GUBN", "0")    // 0=일, 1=주, 2=월
                .queryParam("BYMD", endDate)
                .queryParam("MODP", "0")    // 수정주가
                .build().toUriString();

        HttpHeaders headers = auth.buildHeaders(TR_OVERSEAS_DAY_CANDLE);
        Map<String, Object> body = callWithRetry(url, HttpMethod.GET, new HttpEntity<>(headers));
        if (body == null) {
            return Collections.emptyList();
        }

        Object output2 = body.get("output2");
        if (output2 instanceof List) {
            return (List<Map<String, Object>>) output2;
        }
        return Collections.emptyList();
    }

    // =====================================================================
    // Domestic volume ranking (거래대금 순위)
    // =====================================================================

    /**
     * Fetches domestic stock volume ranking (거래대금 상위 종목).
     * Uses KIS API FHPST01710000 (국내주식 거래량순위).
     *
     * @param count max number of symbols to return
     * @return list of volume ranking data maps
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getDomesticVolumeRanking(int count) {
        String url = UriComponentsBuilder.fromHttpUrl(props.getEffectiveBaseUrl())
                .path("/uapi/domestic-stock/v1/quotations/volume-rank")
                .queryParam("FID_COND_MRKT_DIV_CODE", "J")       // J=전체(코스피+코스닥)
                .queryParam("FID_COND_SCR_DIV_CODE", "20171")     // 거래량순위 (반드시 20171)
                .queryParam("FID_INPUT_ISCD", "0000")             // 전종목
                .queryParam("FID_DIV_CLS_CODE", "0")              // 전체
                .queryParam("FID_BLNG_CLS_CODE", "3")             // 3=거래금액순 (거래대금 기준)
                .queryParam("FID_TRGT_CLS_CODE", "111111111")     // 전체 대상
                .queryParam("FID_TRGT_EXLS_CLS_CODE", "000000")   // 제외 없음
                .queryParam("FID_INPUT_PRICE_1", "0")             // 가격 하한 (0=제한없음)
                .queryParam("FID_INPUT_PRICE_2", "0")             // 가격 상한 (0=제한없음)
                .queryParam("FID_VOL_CNT", "0")                   // 거래량 하한 (0=제한없음)
                .queryParam("FID_INPUT_DATE_1", "0")              // 날짜 (0=당일)
                .build().toUriString();

        HttpHeaders headers = auth.buildHeaders(TR_DOMESTIC_VOLUME_RANK);
        Map<String, Object> body = callWithRetry(url, HttpMethod.GET, new HttpEntity<>(headers));
        if (body == null) {
            log.warn("[VolumeRank] API returned null");
            return Collections.emptyList();
        }

        // 에러 응답 로깅
        String rtCd = (String) body.get("rt_cd");
        if (rtCd != null && !"0".equals(rtCd)) {
            log.warn("[VolumeRank] API error: rt_cd={}, msg={}", rtCd, body.get("msg1"));
        }

        Object output = body.get("output");
        if (output instanceof List) {
            List<Map<String, Object>> list = (List<Map<String, Object>>) output;
            log.info("[VolumeRank] Raw output size: {}", list.size());
            if (list.size() > count) {
                return new ArrayList<Map<String, Object>>(list.subList(0, count));
            }
            return list;
        }
        log.warn("[VolumeRank] No output field or unexpected type: {}", body.keySet());
        return Collections.emptyList();
    }

    // =====================================================================
    // Retry helper
    // =====================================================================

    @SuppressWarnings("unchecked")
    private Map<String, Object> callWithRetry(String url, HttpMethod method, HttpEntity<?> entity) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                auth.acquireRate();
                ResponseEntity<Map> resp = restTemplate.exchange(url, method, entity, Map.class);
                Map<String, Object> body = resp.getBody();

                // Check KIS-level error
                if (body != null) {
                    String rtCd = (String) body.get("rt_cd");
                    if (rtCd != null && !"0".equals(rtCd)) {
                        String msg1 = (String) body.get("msg1");
                        log.warn("KIS API error rt_cd={}, msg={}, url={}", rtCd, msg1, url);
                        if (attempt < MAX_RETRIES) {
                            sleepBackoff(attempt);
                            continue;
                        }
                    }
                }
                return body;
            } catch (HttpStatusCodeException e) {
                log.warn("KIS API HTTP {} on attempt {}/{}: {}", e.getStatusCode(), attempt + 1,
                        MAX_RETRIES + 1, e.getResponseBodyAsString());
                if (attempt < MAX_RETRIES) {
                    sleepBackoff(attempt);
                }
            } catch (Exception e) {
                log.error("KIS API call failed on attempt {}/{}: {}", attempt + 1, MAX_RETRIES + 1,
                        e.getMessage());
                if (attempt < MAX_RETRIES) {
                    sleepBackoff(attempt);
                }
            }
        }
        log.error("KIS API call exhausted all retries: {}", url);
        return null;
    }

    private void sleepBackoff(int attempt) {
        long delay = BASE_BACKOFF_MS * (1L << attempt); // 250, 500, 1000
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
