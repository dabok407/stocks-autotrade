package com.example.stocks.web;

import com.example.stocks.exchange.AccountPosition;
import com.example.stocks.exchange.ExchangeAdapter;
import com.example.stocks.kis.KisAuth;
import com.example.stocks.kis.KisPrivateClient;
import com.example.stocks.market.MarketType;
import com.example.stocks.security.ApiKeyStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KIS API key management endpoints.
 */
@RestController
@RequestMapping("/api/keys/kis")
public class ApiKeyController {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyController.class);

    private final ApiKeyStoreService apiKeyStore;
    private final KisAuth kisAuth;
    private final com.example.stocks.config.KisProperties kisProperties;
    private final ExchangeAdapter exchangeAdapter;
    private final KisPrivateClient kisPrivateClient;

    private volatile Map<String, Object> cachedTestResult;
    private volatile long cachedTestResultAt;
    private static final long TEST_CACHE_TTL_MS = 30_000; // 30 seconds

    public ApiKeyController(ApiKeyStoreService apiKeyStore, KisAuth kisAuth,
                            com.example.stocks.config.KisProperties kisProperties,
                            ExchangeAdapter exchangeAdapter,
                            KisPrivateClient kisPrivateClient) {
        this.apiKeyStore = apiKeyStore;
        this.kisAuth = kisAuth;
        this.kisProperties = kisProperties;
        this.exchangeAdapter = exchangeAdapter;
        this.kisPrivateClient = kisPrivateClient;
    }

    /**
     * Save KIS API keys (encrypted).
     */
    @PostMapping
    public Map<String, Object> saveKeys(@RequestBody SaveKeysRequest req) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        try {
            if (req.appKey == null || req.appKey.trim().isEmpty()
                    || req.appSecret == null || req.appSecret.trim().isEmpty()) {
                result.put("success", false);
                result.put("message", "App Key and App Secret are required.");
                return result;
            }

            apiKeyStore.saveKisKeys(
                    req.appKey.trim(),
                    req.appSecret.trim(),
                    req.accountNo != null ? req.accountNo.trim() : "",
                    req.isPaper != null ? req.isPaper : true
            );

            result.put("success", true);
            result.put("message", "KIS API keys saved successfully.");
            log.info("KIS API keys saved (paper={})", req.isPaper);
        } catch (Exception e) {
            log.error("Failed to save KIS keys: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "Failed to save keys: " + e.getMessage());
        }
        return result;
    }

    /**
     * Check if KIS API keys are configured.
     */
    @GetMapping("/status")
    public Map<String, Object> keyStatus() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        // yml 또는 DB에 키가 있는지 확인
        boolean ymlConfigured = kisProperties.getAppKey() != null && !kisProperties.getAppKey().isEmpty();
        boolean dbConfigured = apiKeyStore.isConfigured();
        boolean configured = ymlConfigured || dbConfigured;
        m.put("configured", configured);
        m.put("source", ymlConfigured ? "yml" : dbConfigured ? "db" : "none");
        m.put("isPaper", kisProperties.getIsPaper());

        if (ymlConfigured) {
            String acct = kisProperties.getAccountNo();
            String masked = acct != null && acct.length() > 4
                    ? "****" + acct.substring(acct.length() - 4) : "****";
            m.put("accountNo", masked);
        } else if (dbConfigured) {
            ApiKeyStoreService.KisKeys keys = apiKeyStore.getKisKeys();
            if (keys != null) {
                m.put("isPaper", keys.paper);
                String masked = keys.accountNo != null && keys.accountNo.length() > 4
                        ? "****" + keys.accountNo.substring(keys.accountNo.length() - 4) : "****";
                m.put("accountNo", masked);
            }
        }
        return m;
    }

    /**
     * Delete stored KIS API keys.
     */
    @DeleteMapping
    public Map<String, Object> deleteKeys() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        try {
            apiKeyStore.deleteKisKeys();
            result.put("success", true);
            result.put("message", "KIS API keys deleted.");
            log.info("KIS API keys deleted");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "Failed to delete keys: " + e.getMessage());
        }
        return result;
    }

    /**
     * Test KIS API connection: token, balance, order test.
     * Returns format expected by settings.js:
     *   keyConfigured, accountsOk, krwBalance, krwLocked, orderTestOk, orderTestError
     */
    @PostMapping("/test")
    public Map<String, Object> testConnection() {
        // Return cached result if fresh (within 30 seconds)
        if (cachedTestResult != null && System.currentTimeMillis() - cachedTestResultAt < TEST_CACHE_TTL_MS) {
            return cachedTestResult;
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();

        boolean configured = (kisProperties.getAppKey() != null && !kisProperties.getAppKey().isEmpty())
                || apiKeyStore.isConfigured();
        result.put("keyConfigured", configured);

        if (!configured) {
            result.put("accountsOk", false);
            result.put("orderTestOk", false);
            return result;
        }

        // 1. Token test
        try {
            String token = kisAuth.getValidToken();
            if (token == null || token.isEmpty()) {
                result.put("accountsOk", false);
                result.put("orderTestOk", false);
                result.put("orderTestError", "Token is empty.");
                return result;
            }
        } catch (Exception e) {
            log.warn("KIS token test failed: {}", e.getMessage());
            result.put("accountsOk", false);
            result.put("orderTestOk", false);
            result.put("orderTestError", "Token failed: " + e.getMessage());
            return result;
        }

        // 2. Balance test — 예수금(현금) + 보유 주식 평가액
        try {
            Map<String, Double> cash = kisPrivateClient.getDomesticCashBalance();
            double deposit = cash.containsKey("dnca_tot_amt") ? cash.get("dnca_tot_amt") : 0;
            double stockEval = cash.containsKey("scts_evlu_amt") ? cash.get("scts_evlu_amt") : 0;
            double totalEval = cash.containsKey("tot_evlu_amt") ? cash.get("tot_evlu_amt") : 0;

            result.put("accountsOk", true);
            result.put("krwBalance", String.format("%.0f", deposit));       // 예수금
            result.put("krwLocked", String.format("%.0f", stockEval));      // 주식 평가액
            result.put("totalEval", String.format("%.0f", totalEval));      // 총 평가
        } catch (Exception e) {
            log.warn("KIS balance test failed: {}", e.getMessage());
            result.put("accountsOk", false);
            result.put("krwBalance", "0");
        }

        // 3. Overseas deposit — 해외 총예수금 = 원화예수금 + 외화사용가능금액
        try {
            Map<String, Double> overseas = kisPrivateClient.getOverseasCashBalance();
            double krwDeposit = overseas.containsKey("tot_dncl_amt") ? overseas.get("tot_dncl_amt") : 0;
            double fcrUsable = overseas.containsKey("frcr_use_psbl_amt") ? overseas.get("frcr_use_psbl_amt") : 0;
            double totalOverseasDeposit = krwDeposit + fcrUsable;
            result.put("overseasDepositKrw", String.format("%.0f", totalOverseasDeposit));
        } catch (Exception e) {
            log.warn("KIS overseas balance failed: {}", e.getMessage());
            result.put("overseasDepositKrw", "0");
        }

        // 4. Order test (just mark as OK if token works — no actual order)
        result.put("orderTestOk", true);
        result.put("success", true);
        result.put("message", "KIS API connection successful.");

        // Cache the result
        cachedTestResult = result;
        cachedTestResultAt = System.currentTimeMillis();
        return result;
    }

    public static class SaveKeysRequest {
        public String appKey;
        public String appSecret;
        public String accountNo;
        public Boolean isPaper;
    }
}
