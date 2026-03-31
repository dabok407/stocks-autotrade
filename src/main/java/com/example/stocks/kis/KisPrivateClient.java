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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KIS private (trading / account) API client.
 * Handles domestic and overseas stock orders and balance inquiries.
 */
@Component
public class KisPrivateClient {

    private static final Logger log = LoggerFactory.getLogger(KisPrivateClient.class);

    private static final int MAX_RETRIES = 3;
    private static final long BASE_BACKOFF_MS = 250L;

    // Domestic order tr_id
    private static final String TR_DOMESTIC_BUY_LIVE = "TTTC0802U";
    private static final String TR_DOMESTIC_SELL_LIVE = "TTTC0801U";
    private static final String TR_DOMESTIC_BUY_PAPER = "VTTC0802U";
    private static final String TR_DOMESTIC_SELL_PAPER = "VTTC0801U";

    // Overseas order tr_id
    private static final String TR_OVERSEAS_BUY_LIVE = "TTTT1002U";
    private static final String TR_OVERSEAS_SELL_LIVE = "TTTT1006U";
    private static final String TR_OVERSEAS_BUY_PAPER = "VTTT1002U";
    private static final String TR_OVERSEAS_SELL_PAPER = "VTTT1006U";

    // Balance inquiry tr_id
    private static final String TR_DOMESTIC_BALANCE_LIVE = "TTTC8434R";
    private static final String TR_DOMESTIC_BALANCE_PAPER = "VTTC8434R";
    private static final String TR_OVERSEAS_BALANCE_LIVE = "TTTS3012R";
    private static final String TR_OVERSEAS_BALANCE_PAPER = "VTTS3012R";

    // Order status inquiry tr_id
    private static final String TR_DOMESTIC_ORDER_STATUS_LIVE = "TTTC8001R";
    private static final String TR_DOMESTIC_ORDER_STATUS_PAPER = "VTTC8001R";

    private final KisAuth auth;
    private final RestTemplate restTemplate;
    private final KisProperties props;

    public KisPrivateClient(KisAuth auth, RestTemplate restTemplate, KisProperties props) {
        this.auth = auth;
        this.restTemplate = restTemplate;
        this.props = props;
    }

    // =====================================================================
    // Configuration check
    // =====================================================================

    /**
     * Returns true if KIS API credentials are configured.
     */
    public boolean isConfigured() {
        return props.getAppKey() != null && !props.getAppKey().isEmpty()
                && props.getAppSecret() != null && !props.getAppSecret().isEmpty()
                && props.getAccountNo() != null && !props.getAccountNo().isEmpty();
    }

    // =====================================================================
    // Domestic stock order
    // =====================================================================

    /**
     * Places a domestic (KRX) stock order.
     *
     * @param stockCode KRX stock code (e.g. "005930")
     * @param side      "BUY" or "SELL"
     * @param qty       order quantity
     * @param price     order price (0 for market order)
     * @param ordType   order division: "00"=limit, "01"=market, etc.
     * @return API response map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> placeDomesticOrder(String stockCode, String side,
                                                   int qty, long price, String ordType) {
        if (!isConfigured()) {
            log.warn("KIS API is not configured, skipping domestic order");
            return Collections.emptyMap();
        }

        String trId;
        if ("BUY".equalsIgnoreCase(side)) {
            trId = props.getIsPaper() ? TR_DOMESTIC_BUY_PAPER : TR_DOMESTIC_BUY_LIVE;
        } else {
            trId = props.getIsPaper() ? TR_DOMESTIC_SELL_PAPER : TR_DOMESTIC_SELL_LIVE;
        }

        Map<String, String> body = new HashMap<>();
        body.put("CANO", props.getAccountNoPrefix());
        body.put("ACNT_PRDT_CD", props.getAccountNoSuffix());
        body.put("PDNO", stockCode);
        body.put("ORD_DVSN", ordType);
        body.put("ORD_QTY", String.valueOf(qty));
        body.put("ORD_UNPR", String.valueOf(price));

        String url = props.getEffectiveBaseUrl() + "/uapi/domestic-stock/v1/trading/order-cash";
        HttpHeaders headers = auth.buildHeaders(trId);

        log.info("Domestic {} order: stock={}, qty={}, price={}, ordType={}, paper={}",
                side, stockCode, qty, price, ordType, props.getIsPaper());

        return postWithRetry(url, new HttpEntity<>(body, headers));
    }

    // =====================================================================
    // Overseas stock order
    // =====================================================================

    /**
     * Places an overseas stock order.
     *
     * @param exchangeCode exchange code (NASD, NYSE, AMEX, etc.)
     * @param symbol       ticker symbol (e.g. "AAPL")
     * @param side         "BUY" or "SELL"
     * @param qty          order quantity
     * @param price        order price (0 for market order)
     * @param ordType      order division: "00"=limit, "32"=market(US), etc.
     * @return API response map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> placeOverseasOrder(String exchangeCode, String symbol, String side,
                                                   int qty, double price, String ordType) {
        if (!isConfigured()) {
            log.warn("KIS API is not configured, skipping overseas order");
            return Collections.emptyMap();
        }

        String trId;
        if ("BUY".equalsIgnoreCase(side)) {
            trId = props.getIsPaper() ? TR_OVERSEAS_BUY_PAPER : TR_OVERSEAS_BUY_LIVE;
        } else {
            trId = props.getIsPaper() ? TR_OVERSEAS_SELL_PAPER : TR_OVERSEAS_SELL_LIVE;
        }

        Map<String, String> body = new HashMap<>();
        body.put("CANO", props.getAccountNoPrefix());
        body.put("ACNT_PRDT_CD", props.getAccountNoSuffix());
        body.put("OVRS_EXCG_CD", exchangeCode);
        body.put("PDNO", symbol);
        body.put("ORD_QTY", String.valueOf(qty));
        body.put("OVRS_ORD_UNPR", String.valueOf(price));
        body.put("ORD_DVSN", ordType);

        String url = props.getEffectiveBaseUrl() + "/uapi/overseas-stock/v1/trading/order";
        HttpHeaders headers = auth.buildHeaders(trId);

        log.info("Overseas {} order: exchange={}, symbol={}, qty={}, price={}, ordType={}, paper={}",
                side, exchangeCode, symbol, qty, price, ordType, props.getIsPaper());

        return postWithRetry(url, new HttpEntity<>(body, headers));
    }

    // =====================================================================
    // Domestic order status inquiry
    // =====================================================================

    /**
     * Retrieves domestic order execution status.
     *
     * @param orderNo KIS order number (ODNO)
     * @return response map with order status info
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getDomesticOrderStatus(String orderNo) {
        if (!isConfigured() || orderNo == null || orderNo.isEmpty()) {
            return Collections.emptyMap();
        }

        String trId = props.getIsPaper() ? TR_DOMESTIC_ORDER_STATUS_PAPER : TR_DOMESTIC_ORDER_STATUS_LIVE;

        String url = UriComponentsBuilder.fromHttpUrl(props.getEffectiveBaseUrl())
                .path("/uapi/domestic-stock/v1/trading/inquire-daily-ccld")
                .queryParam("CANO", props.getAccountNoPrefix())
                .queryParam("ACNT_PRDT_CD", props.getAccountNoSuffix())
                .queryParam("INQR_STRT_DT", todayKst())
                .queryParam("INQR_END_DT", todayKst())
                .queryParam("SLL_BUY_DVSN_CD", "00")  // 전체 (매수+매도)
                .queryParam("INQR_DVSN", "00")
                .queryParam("PDNO", "")
                .queryParam("CCLD_DVSN", "00")         // 전체 (체결+미체결)
                .queryParam("ORD_GNO_BRNO", "")
                .queryParam("ODNO", orderNo)
                .queryParam("INQR_DVSN_3", "00")
                .queryParam("INQR_DVSN_1", "")
                .queryParam("CTX_AREA_FK100", "")
                .queryParam("CTX_AREA_NK100", "")
                .build().toUriString();

        HttpHeaders headers = auth.buildHeaders(trId);
        Map<String, Object> body = getWithRetry(url, new HttpEntity<>(headers));
        if (body == null) {
            return Collections.emptyMap();
        }

        // output1 contains the list of matching orders
        List<Map<String, Object>> output1 = (List<Map<String, Object>>) body.get("output1");
        if (output1 != null && !output1.isEmpty()) {
            return output1.get(0);
        }
        return Collections.emptyMap();
    }

    private String todayKst() {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        return String.format("%04d%02d%02d", today.getYear(), today.getMonthValue(), today.getDayOfMonth());
    }

    // =====================================================================
    // Domestic balance inquiry
    // =====================================================================

    /**
     * Retrieves domestic stock holdings.
     *
     * @return list of KisAccount holdings
     */
    @SuppressWarnings("unchecked")
    public List<KisAccount> getDomesticBalance() {
        if (!isConfigured()) {
            return Collections.emptyList();
        }

        String trId = props.getIsPaper() ? TR_DOMESTIC_BALANCE_PAPER : TR_DOMESTIC_BALANCE_LIVE;

        String url = UriComponentsBuilder.fromHttpUrl(props.getEffectiveBaseUrl())
                .path("/uapi/domestic-stock/v1/trading/inquire-balance")
                .queryParam("CANO", props.getAccountNoPrefix())
                .queryParam("ACNT_PRDT_CD", props.getAccountNoSuffix())
                .queryParam("AFHR_FLPR_YN", "N")
                .queryParam("OFL_YN", "")
                .queryParam("INQR_DVSN", "02")
                .queryParam("UNPR_DVSN", "01")
                .queryParam("FUND_STTL_ICLD_YN", "N")
                .queryParam("FNCG_AMT_AUTO_RDPT_YN", "N")
                .queryParam("PRCS_DVSN", "01")
                .queryParam("CTX_AREA_FK100", "")
                .queryParam("CTX_AREA_NK100", "")
                .build().toUriString();

        HttpHeaders headers = auth.buildHeaders(trId);
        Map<String, Object> body = getWithRetry(url, new HttpEntity<>(headers));
        if (body == null) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> output1 = (List<Map<String, Object>>) body.get("output1");
        if (output1 == null) {
            return Collections.emptyList();
        }

        List<KisAccount> accounts = new ArrayList<>();
        for (Map<String, Object> item : output1) {
            int hldgQty = parseIntSafe(item.get("hldg_qty"));
            if (hldgQty <= 0) continue;

            KisAccount acc = new KisAccount();
            acc.setSymbol((String) item.get("pdno"));
            acc.setName((String) item.get("prdt_name"));
            acc.setQty(hldgQty);
            acc.setAvgPrice(parseDoubleSafe(item.get("pchs_avg_pric")));
            acc.setCurrentPrice(parseDoubleSafe(item.get("prpr")));
            acc.setPnl(parseDoubleSafe(item.get("evlu_pfls_amt")));
            acc.setCurrency("KRW");
            accounts.add(acc);
        }
        return accounts;
    }

    /**
     * Retrieves domestic account cash balance (예수금).
     * Uses output2 from the same balance inquiry API.
     *
     * @return map with dnca_tot_amt (예수금총액), nass_amt (순자산), etc.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Double> getDomesticCashBalance() {
        Map<String, Double> result = new LinkedHashMap<String, Double>();
        if (!isConfigured()) {
            return result;
        }

        String trId = props.getIsPaper() ? TR_DOMESTIC_BALANCE_PAPER : TR_DOMESTIC_BALANCE_LIVE;

        String url = UriComponentsBuilder.fromHttpUrl(props.getEffectiveBaseUrl())
                .path("/uapi/domestic-stock/v1/trading/inquire-balance")
                .queryParam("CANO", props.getAccountNoPrefix())
                .queryParam("ACNT_PRDT_CD", props.getAccountNoSuffix())
                .queryParam("AFHR_FLPR_YN", "N")
                .queryParam("OFL_YN", "")
                .queryParam("INQR_DVSN", "02")
                .queryParam("UNPR_DVSN", "01")
                .queryParam("FUND_STTL_ICLD_YN", "N")
                .queryParam("FNCG_AMT_AUTO_RDPT_YN", "N")
                .queryParam("PRCS_DVSN", "01")
                .queryParam("CTX_AREA_FK100", "")
                .queryParam("CTX_AREA_NK100", "")
                .build().toUriString();

        HttpHeaders headers = auth.buildHeaders(trId);
        Map<String, Object> body = getWithRetry(url, new HttpEntity<>(headers));
        if (body == null) {
            return result;
        }

        List<Map<String, Object>> output2 = (List<Map<String, Object>>) body.get("output2");
        if (output2 != null && !output2.isEmpty()) {
            Map<String, Object> summary = output2.get(0);
            result.put("dnca_tot_amt", parseDoubleSafe(summary.get("dnca_tot_amt")));     // 예수금총액
            result.put("scts_evlu_amt", parseDoubleSafe(summary.get("scts_evlu_amt")));   // 유가평가금액
            result.put("tot_evlu_amt", parseDoubleSafe(summary.get("tot_evlu_amt")));     // 총평가금액
            result.put("nass_amt", parseDoubleSafe(summary.get("nass_amt")));             // 순자산금액
            result.put("pchs_amt_smtl_amt", parseDoubleSafe(summary.get("pchs_amt_smtl_amt"))); // 매입금액합계
        }
        return result;
    }

    /**
     * Retrieves overseas total deposit (해외주식 체결기준현재잔고).
     * output3 contains tot_asst_amt (총자산), frcr_evlu_amt2 (외화평가), etc.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Double> getOverseasCashBalance() {
        Map<String, Double> result = new LinkedHashMap<String, Double>();
        if (!isConfigured()) {
            return result;
        }

        // 해외주식 체결기준현재잔고
        String trId = props.getIsPaper() ? "VTRP6504R" : "CTRP6504R";

        String url = UriComponentsBuilder.fromHttpUrl(props.getEffectiveBaseUrl())
                .path("/uapi/overseas-stock/v1/trading/inquire-present-balance")
                .queryParam("CANO", props.getAccountNoPrefix())
                .queryParam("ACNT_PRDT_CD", props.getAccountNoSuffix())
                .queryParam("WCRC_FRCR_DVSN_CD", "01")
                .queryParam("NATN_CD", "840")
                .queryParam("TR_MKET_CD", "00")
                .queryParam("INQR_DVSN_CD", "00")
                .build().toUriString();

        HttpHeaders headers = auth.buildHeaders(trId);
        Map<String, Object> body = getWithRetry(url, new HttpEntity<>(headers));
        if (body == null) {
            return result;
        }

        // output2: 통화별 외화예수금
        Object out2 = body.get("output2");
        if (out2 instanceof Map) {
            Map<String, Object> o2 = (Map<String, Object>) out2;
            result.put("frcr_dncl_amt_2", parseDoubleSafe(o2.get("frcr_dncl_amt_2")));
        } else if (out2 instanceof List) {
            List<Map<String, Object>> list = (List<Map<String, Object>>) out2;
            for (Map<String, Object> item : list) {
                // USD 항목의 외화예수금
                String crcy = (String) item.get("crcy_cd");
                if ("USD".equals(crcy) || list.size() == 1) {
                    result.put("frcr_dncl_amt_2", parseDoubleSafe(item.get("frcr_dncl_amt_2")));
                    break;
                }
            }
        }

        // output3: 계좌 요약 (총예수금, 원화예수금, 총자산)
        Object out3 = body.get("output3");
        Map<String, Object> summary = null;
        if (out3 instanceof Map) {
            summary = (Map<String, Object>) out3;
        } else if (out3 instanceof List) {
            List<Map<String, Object>> list = (List<Map<String, Object>>) out3;
            if (!list.isEmpty()) summary = list.get(0);
        }

        if (summary != null) {
            log.info("[KIS Overseas] output3 tot_dncl_amt={}, dncl_amt={}, tot_asst_amt={}, evlu_amt_smtl={}, frcr_use_psbl_amt={}",
                    summary.get("tot_dncl_amt"), summary.get("dncl_amt"),
                    summary.get("tot_asst_amt"), summary.get("evlu_amt_smtl"),
                    summary.get("frcr_use_psbl_amt"));
            result.put("tot_dncl_amt", parseDoubleSafe(summary.get("tot_dncl_amt")));       // 총예수금
            result.put("dncl_amt", parseDoubleSafe(summary.get("dncl_amt")));               // 원화예수금
            result.put("tot_asst_amt", parseDoubleSafe(summary.get("tot_asst_amt")));       // 총자산금액
            result.put("evlu_amt_smtl", parseDoubleSafe(summary.get("evlu_amt_smtl")));     // 평가금액합계
            result.put("frcr_use_psbl_amt", parseDoubleSafe(summary.get("frcr_use_psbl_amt"))); // 외화사용가능금액
        } else {
            log.warn("[KIS Overseas] output3 missing. response keys={}", body.keySet());
        }

        return result;
    }

    // =====================================================================
    // Overseas balance inquiry
    // =====================================================================

    /**
     * Retrieves overseas stock holdings.
     *
     * @return list of KisAccount holdings
     */
    @SuppressWarnings("unchecked")
    public List<KisAccount> getOverseasBalance() {
        if (!isConfigured()) {
            return Collections.emptyList();
        }

        String trId = props.getIsPaper() ? TR_OVERSEAS_BALANCE_PAPER : TR_OVERSEAS_BALANCE_LIVE;

        String url = UriComponentsBuilder.fromHttpUrl(props.getEffectiveBaseUrl())
                .path("/uapi/overseas-stock/v1/trading/inquire-balance")
                .queryParam("CANO", props.getAccountNoPrefix())
                .queryParam("ACNT_PRDT_CD", props.getAccountNoSuffix())
                .queryParam("OVRS_EXCG_CD", "NASD")
                .queryParam("TR_CRCY_CD", "USD")
                .queryParam("CTX_AREA_FK200", "")
                .queryParam("CTX_AREA_NK200", "")
                .build().toUriString();

        HttpHeaders headers = auth.buildHeaders(trId);
        Map<String, Object> body = getWithRetry(url, new HttpEntity<>(headers));
        if (body == null) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> output1 = (List<Map<String, Object>>) body.get("output1");
        if (output1 == null) {
            return Collections.emptyList();
        }

        List<KisAccount> accounts = new ArrayList<>();
        for (Map<String, Object> item : output1) {
            int hldgQty = parseIntSafe(item.get("ovrs_cblc_qty"));
            if (hldgQty <= 0) continue;

            KisAccount acc = new KisAccount();
            acc.setSymbol((String) item.get("ovrs_pdno"));
            acc.setName((String) item.get("ovrs_item_name"));
            acc.setQty(hldgQty);
            acc.setAvgPrice(parseDoubleSafe(item.get("pchs_avg_pric")));
            acc.setCurrentPrice(parseDoubleSafe(item.get("now_pric2")));
            acc.setPnl(parseDoubleSafe(item.get("frcr_evlu_pfls_amt")));
            acc.setCurrency("USD");
            accounts.add(acc);
        }
        return accounts;
    }

    // =====================================================================
    // HTTP helpers with retry
    // =====================================================================

    @SuppressWarnings("unchecked")
    private Map<String, Object> postWithRetry(String url, HttpEntity<?> entity) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                auth.acquireRate();
                ResponseEntity<Map> resp = restTemplate.postForEntity(url, entity, Map.class);
                Map<String, Object> body = resp.getBody();
                if (body != null) {
                    String rtCd = (String) body.get("rt_cd");
                    if (!"0".equals(rtCd)) {
                        log.warn("KIS order error rt_cd={}, msg={}", rtCd, body.get("msg1"));
                        if (attempt < MAX_RETRIES) {
                            sleepBackoff(attempt);
                            continue;
                        }
                    }
                }
                return body;
            } catch (HttpStatusCodeException e) {
                log.warn("KIS order HTTP {} on attempt {}/{}: {}", e.getStatusCode(),
                        attempt + 1, MAX_RETRIES + 1, e.getResponseBodyAsString());
                if (attempt < MAX_RETRIES) {
                    sleepBackoff(attempt);
                }
            } catch (Exception e) {
                log.error("KIS order failed on attempt {}/{}: {}", attempt + 1, MAX_RETRIES + 1,
                        e.getMessage());
                if (attempt < MAX_RETRIES) {
                    sleepBackoff(attempt);
                }
            }
        }
        log.error("KIS order exhausted all retries: {}", url);
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getWithRetry(String url, HttpEntity<?> entity) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                auth.acquireRate();
                ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
                Map<String, Object> body = resp.getBody();
                if (body != null) {
                    String rtCd = (String) body.get("rt_cd");
                    if (rtCd != null && !"0".equals(rtCd)) {
                        log.warn("KIS balance error rt_cd={}, msg={}", rtCd, body.get("msg1"));
                        if (attempt < MAX_RETRIES) {
                            sleepBackoff(attempt);
                            continue;
                        }
                    }
                }
                return body;
            } catch (HttpStatusCodeException e) {
                log.warn("KIS balance HTTP {} on attempt {}/{}: {}", e.getStatusCode(),
                        attempt + 1, MAX_RETRIES + 1, e.getResponseBodyAsString());
                if (attempt < MAX_RETRIES) {
                    sleepBackoff(attempt);
                }
            } catch (Exception e) {
                log.error("KIS balance failed on attempt {}/{}: {}", attempt + 1, MAX_RETRIES + 1,
                        e.getMessage());
                if (attempt < MAX_RETRIES) {
                    sleepBackoff(attempt);
                }
            }
        }
        log.error("KIS balance exhausted all retries: {}", url);
        return null;
    }

    // ---------- parsing helpers ----------

    private int parseIntSafe(Object val) {
        if (val == null) return 0;
        try {
            return Integer.parseInt(val.toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double parseDoubleSafe(Object val) {
        if (val == null) return 0.0;
        try {
            return Double.parseDouble(val.toString().trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private void sleepBackoff(int attempt) {
        long delay = BASE_BACKOFF_MS * (1L << attempt);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
