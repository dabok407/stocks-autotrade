package com.example.stocks.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "kis")
public class KisProperties {

    private String appKey = "";
    private String appSecret = "";
    private String accountNo = "";
    private boolean isPaper = true;
    private String baseUrl = "";

    // ---------- helpers ----------

    /**
     * Resolves the effective base URL.
     * If baseUrl is explicitly set, use it; otherwise derive from isPaper flag.
     */
    public String getEffectiveBaseUrl() {
        if (baseUrl != null && !baseUrl.isEmpty()) {
            return baseUrl;
        }
        return isPaper
                ? "https://openapivts.koreainvestment.com:29443"
                : "https://openapi.koreainvestment.com:9443";
    }

    /**
     * CANO: first 8 characters of account number.
     */
    public String getAccountNoPrefix() {
        if (accountNo == null || accountNo.length() < 8) {
            return accountNo;
        }
        return accountNo.substring(0, 8);
    }

    /**
     * ACNT_PRDT_CD: last 2 characters of account number.
     */
    public String getAccountNoSuffix() {
        if (accountNo == null || accountNo.length() < 10) {
            String suffix = accountNo != null && accountNo.length() > 8
                    ? accountNo.substring(8)
                    : "";
            // KIS API requires ACNT_PRDT_CD; default to "01" for stock accounts
            return suffix.isEmpty() ? "01" : suffix;
        }
        return accountNo.substring(8, 10);
    }

    // ---------- getters / setters ----------

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public String getAccountNo() {
        return accountNo;
    }

    public void setAccountNo(String accountNo) {
        this.accountNo = accountNo;
    }

    public boolean getIsPaper() {
        return isPaper;
    }

    public void setIsPaper(boolean isPaper) {
        this.isPaper = isPaper;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
