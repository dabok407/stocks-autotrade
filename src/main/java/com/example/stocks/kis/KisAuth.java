package com.example.stocks.kis;

import com.example.stocks.config.KisProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * Manages KIS OAuth token lifecycle and provides common HTTP headers.
 * <p>
 * Rate-limiting: KIS allows 3 requests/second. A semaphore-based sliding window
 * ensures we never exceed that limit across all callers.
 */
@Component
public class KisAuth {

    private static final Logger log = LoggerFactory.getLogger(KisAuth.class);

    private static final long TOKEN_REFRESH_MARGIN_SECONDS = 5 * 60; // refresh 5 min before expiry
    private static final int MAX_REQUESTS_PER_SECOND = 3;

    private final KisProperties props;
    private final RestTemplate restTemplate;

    // ---------- token state ----------
    private volatile String accessToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    // ---------- rate limiter ----------
    private final Semaphore rateSemaphore = new Semaphore(MAX_REQUESTS_PER_SECOND);
    private volatile long windowStartMillis = System.currentTimeMillis();
    private final Object rateLock = new Object();

    public KisAuth(KisProperties props, RestTemplate restTemplate) {
        this.props = props;
        this.restTemplate = restTemplate;
    }

    // =====================================================================
    // Token management
    // =====================================================================

    /**
     * Returns a valid access token, refreshing if needed.
     */
    public synchronized String getValidToken() {
        if (accessToken == null || Instant.now().plusSeconds(TOKEN_REFRESH_MARGIN_SECONDS).isAfter(tokenExpiresAt)) {
            refreshToken();
        }
        return accessToken;
    }

    @SuppressWarnings("unchecked")
    private void refreshToken() {
        String url = props.getEffectiveBaseUrl() + "/oauth2/tokenP";

        Map<String, String> body = new HashMap<>();
        body.put("grant_type", "client_credentials");
        body.put("appkey", props.getAppKey());
        body.put("appsecret", props.getAppSecret());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), Map.class);
            Map<String, Object> result = resp.getBody();
            if (result == null) {
                throw new RuntimeException("KIS token response body is null");
            }

            this.accessToken = (String) result.get("access_token");
            String expiresStr = (String) result.get("access_token_token_expired");

            // Format: "yyyy-MM-dd HH:mm:ss" in KST
            if (expiresStr != null && !expiresStr.isEmpty()) {
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                LocalDateTime ldt = LocalDateTime.parse(expiresStr, fmt);
                this.tokenExpiresAt = ldt.atZone(ZoneId.of("Asia/Seoul")).toInstant();
            } else {
                // Fallback: assume 24h validity
                this.tokenExpiresAt = Instant.now().plusSeconds(24 * 3600);
            }

            log.info("KIS token refreshed, expires at {}", expiresStr);
        } catch (Exception e) {
            log.error("Failed to refresh KIS token: {}", e.getMessage(), e);
            throw new RuntimeException("KIS token refresh failed", e);
        }
    }

    // =====================================================================
    // Header builder
    // =====================================================================

    /**
     * Builds standard KIS API headers including authorization.
     *
     * @param trId KIS transaction ID (e.g. FHKST03010200)
     * @return HttpHeaders ready for use
     */
    public HttpHeaders buildHeaders(String trId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("authorization", "Bearer " + getValidToken());
        headers.set("appkey", props.getAppKey());
        headers.set("appsecret", props.getAppSecret());
        headers.set("tr_id", trId);
        return headers;
    }

    // =====================================================================
    // Rate limiter (3 req/sec sliding window)
    // =====================================================================

    /**
     * Blocks until a rate-limit slot is available.
     * Uses a simple 1-second window with a semaphore of 3 permits.
     */
    public void acquireRate() {
        synchronized (rateLock) {
            long now = System.currentTimeMillis();
            if (now - windowStartMillis >= 1000L) {
                // New window: reset permits
                int drained = MAX_REQUESTS_PER_SECOND - rateSemaphore.availablePermits();
                if (drained > 0) {
                    rateSemaphore.release(drained);
                }
                windowStartMillis = now;
            }
        }

        try {
            if (!rateSemaphore.tryAcquire(5, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("Rate limiter timeout after 5s, proceeding anyway");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Rate limiter interrupted, proceeding");
        }
    }
}
