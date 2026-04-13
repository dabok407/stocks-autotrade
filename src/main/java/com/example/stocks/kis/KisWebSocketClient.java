package com.example.stocks.kis;

import com.example.stocks.config.KisProperties;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PreDestroy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * KIS(한국투자증권) WebSocket 실시간 시세 클라이언트.
 * <p>
 * - KRX 실시간 체결가: tr_id = H0STCNT0, tr_key = 종목코드 (예: "005930")
 * - US 실시간 체결가:  tr_id = HDFSCNT0, tr_key = "{거래소코드}.{심볼}" (예: "NASD.AAPL")
 * <p>
 * 연결 전 POST /oauth2/Approval 으로 approval_key를 발급받아야 한다.
 * 끊어질 경우 지수 백오프로 최대 5회 자동 재연결을 시도한다.
 */
@Component
public class KisWebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(KisWebSocketClient.class);

    // WebSocket endpoint
    // KIS WebSocket: ws:// (not wss://) - KIS 실시간 시세 서버는 평문 WebSocket
    private static final String WS_URL_LIVE = "ws://ops.koreainvestment.com:21000/tryitout";
    private static final String WS_URL_PAPER = "ws://ops.koreainvestment.com:31000/tryitout";

    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long BASE_BACKOFF_MS = 1000L;

    private final KisProperties props;
    private final RestTemplate restTemplate;
    private final OkHttpClient httpClient;

    // 최신 체결가 저장소: key = symbol (KRX: "005930", US: "NASD.AAPL")
    private final ConcurrentHashMap<String, Double> latestPrices = new ConcurrentHashMap<String, Double>();

    // 가격 업데이트 리스너 (실시간 TP/SL 등)
    public interface PriceListener {
        void onPrice(String symbol, double price);
    }
    private final java.util.List<PriceListener> priceListeners = new java.util.concurrent.CopyOnWriteArrayList<PriceListener>();

    public void addPriceListener(PriceListener listener) {
        if (listener != null) priceListeners.add(listener);
    }
    public void removePriceListener(PriceListener listener) {
        if (listener != null) priceListeners.remove(listener);
    }

    // 구독 중인 종목 목록 (재연결 시 재구독용)
    private final Set<String> subscribedSymbols = ConcurrentHashMap.newKeySet();
    // 각 심볼의 tr_id 기록 (재구독용)
    private final ConcurrentHashMap<String, String> symbolTrIds = new ConcurrentHashMap<String, String>();

    private volatile WebSocket webSocket;
    private volatile String approvalKey;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    public KisWebSocketClient(KisProperties props, RestTemplate restTemplate) {
        this.props = props;
        this.restTemplate = restTemplate;
        this.httpClient = new OkHttpClient.Builder()
                .pingInterval(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)  // WebSocket: no read timeout
                .build();
    }

    // =====================================================================
    // Public API
    // =====================================================================

    /**
     * WebSocket 연결을 시작한다.
     * 이미 연결 중이면 무시한다.
     */
    public void connect() {
        if (connected.get()) {
            log.debug("WebSocket already connected");
            return;
        }
        shuttingDown.set(false);
        reconnectAttempts.set(0);
        doConnect();
    }

    /**
     * WebSocket 연결을 종료한다.
     */
    public void disconnect() {
        shuttingDown.set(true);
        if (webSocket != null) {
            try {
                webSocket.close(1000, "Client disconnect");
            } catch (Exception e) {
                log.debug("Error closing WebSocket: {}", e.getMessage());
            }
            webSocket = null;
        }
        connected.set(false);
        log.info("[WS] Disconnected");
    }

    /**
     * 종목을 실시간 시세 구독에 추가한다.
     *
     * @param symbol KRX 종목코드 (예: "005930") 또는 US "{거래소}.{심볼}" (예: "NASD.AAPL")
     * @param isOverseas true면 해외주식 (HDFSCNT0), false면 국내주식 (H0STCNT0)
     */
    public void subscribe(String symbol, boolean isOverseas) {
        String trId = isOverseas ? "HDFSCNT0" : "H0STCNT0";
        subscribedSymbols.add(symbol);
        symbolTrIds.put(symbol, trId);

        if (connected.get() && webSocket != null) {
            sendSubscribe(symbol, trId, "1");
        }
    }

    /**
     * 종목의 실시간 시세 구독을 해제한다.
     */
    public void unsubscribe(String symbol) {
        subscribedSymbols.remove(symbol);
        String trId = symbolTrIds.remove(symbol);
        if (trId == null) trId = "H0STCNT0";

        if (connected.get() && webSocket != null) {
            sendSubscribe(symbol, trId, "2"); // tr_type "2" = unsubscribe
        }
    }

    /**
     * 종목의 최신 체결가를 반환한다.
     *
     * @param symbol 종목 키 (subscribe 시 사용한 것과 동일)
     * @return 최신 체결가. 데이터 없으면 0.0
     */
    public double getLatestPrice(String symbol) {
        Double price = latestPrices.get(symbol);
        return price != null ? price : 0.0;
    }

    /**
     * 전체 최신 시세 맵을 반환한다.
     */
    public Map<String, Double> getAllLatestPrices() {
        return new HashMap<String, Double>(latestPrices);
    }

    /**
     * WebSocket 연결 상태를 반환한다.
     */
    public boolean isConnected() {
        return connected.get();
    }

    @PreDestroy
    public void onDestroy() {
        disconnect();
        httpClient.dispatcher().executorService().shutdown();
    }

    // =====================================================================
    // Internal: connect / reconnect
    // =====================================================================

    private void doConnect() {
        try {
            // Step 1: approval key 발급
            if (approvalKey == null) {
                approvalKey = fetchApprovalKey();
            }
            if (approvalKey == null) {
                log.error("[WS] Failed to obtain approval key, cannot connect");
                return;
            }

            // Step 2: WebSocket 연결
            String wsUrl = props.getIsPaper() ? WS_URL_PAPER : WS_URL_LIVE;
            Request request = new Request.Builder().url(wsUrl).build();

            webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket ws, Response response) {
                    connected.set(true);
                    reconnectAttempts.set(0);
                    log.info("[WS] Connected to {}", wsUrl);

                    // 재연결 시 기존 구독 복원
                    for (String sym : subscribedSymbols) {
                        String trId = symbolTrIds.get(sym);
                        if (trId == null) trId = "H0STCNT0";
                        sendSubscribe(sym, trId, "1");
                    }
                }

                @Override
                public void onMessage(WebSocket ws, String text) {
                    handleMessage(text);
                }

                @Override
                public void onClosing(WebSocket ws, int code, String reason) {
                    log.info("[WS] Server closing: code={}, reason={}", code, reason);
                    connected.set(false);
                }

                @Override
                public void onClosed(WebSocket ws, int code, String reason) {
                    log.info("[WS] Closed: code={}, reason={}", code, reason);
                    connected.set(false);
                    attemptReconnect();
                }

                @Override
                public void onFailure(WebSocket ws, Throwable t, Response response) {
                    log.warn("[WS] Failure: {}", t.getMessage());
                    connected.set(false);
                    attemptReconnect();
                }
            });

        } catch (Exception e) {
            log.error("[WS] Connection error: {}", e.getMessage(), e);
            attemptReconnect();
        }
    }

    private void attemptReconnect() {
        if (shuttingDown.get()) return;

        int attempt = reconnectAttempts.incrementAndGet();
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            log.error("[WS] Max reconnect attempts ({}) exhausted, giving up", MAX_RECONNECT_ATTEMPTS);
            return;
        }

        long delay = BASE_BACKOFF_MS * (1L << (attempt - 1));
        log.info("[WS] Reconnecting in {}ms (attempt {}/{})", delay, attempt, MAX_RECONNECT_ATTEMPTS);

        // 비동기 재연결 (별도 스레드)
        Thread reconnectThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!shuttingDown.get()) {
                    approvalKey = null; // 재발급
                    doConnect();
                }
            }
        }, "ws-reconnect-" + attempt);
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    // =====================================================================
    // Approval key
    // =====================================================================

    /**
     * POST /oauth2/Approval 으로 WebSocket 접속키를 발급받는다.
     */
    @SuppressWarnings("unchecked")
    private String fetchApprovalKey() {
        String url = props.getEffectiveBaseUrl() + "/oauth2/Approval";

        Map<String, String> body = new HashMap<String, String>();
        body.put("grant_type", "client_credentials");
        body.put("appkey", props.getAppKey());
        body.put("secretkey", props.getAppSecret());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), Map.class);
            Map<String, Object> result = resp.getBody();
            if (result == null) {
                log.error("[WS] Approval key response is null");
                return null;
            }
            String key = (String) result.get("approval_key");
            log.info("[WS] Approval key obtained");
            return key;
        } catch (Exception e) {
            log.error("[WS] Failed to get approval key: {}", e.getMessage());
            return null;
        }
    }

    // =====================================================================
    // Subscribe message
    // =====================================================================

    /**
     * 구독/해제 메시지를 WebSocket으로 전송한다.
     *
     * @param trKey  종목키 (KRX: "005930", US: "NASD.AAPL")
     * @param trId   거래 ID (H0STCNT0 or HDFSCNT0)
     * @param trType "1"=구독, "2"=해제
     */
    private void sendSubscribe(String trKey, String trId, String trType) {
        if (webSocket == null || approvalKey == null) return;

        String json = "{" +
                "\"header\":{" +
                "\"approval_key\":\"" + approvalKey + "\"," +
                "\"custtype\":\"P\"," +
                "\"tr_type\":\"" + trType + "\"," +
                "\"content-type\":\"utf-8\"" +
                "}," +
                "\"body\":{" +
                "\"input\":{" +
                "\"tr_id\":\"" + trId + "\"," +
                "\"tr_key\":\"" + trKey + "\"" +
                "}" +
                "}" +
                "}";

        boolean sent = webSocket.send(json);
        log.info("[WS] {} {} (tr_id={}, sent={})",
                "1".equals(trType) ? "Subscribe" : "Unsubscribe", trKey, trId, sent);
    }

    // =====================================================================
    // Message parsing
    // =====================================================================

    /**
     * WebSocket 수신 메시지를 파싱하여 latestPrices를 갱신한다.
     * <p>
     * KIS 실시간 체결 데이터 형식:
     * - JSON 응답 (구독 확인): {"header":{"tr_id":"...", "tr_key":"...", "encrypt":"N"}, ...}
     * - 파이프(|) 구분 데이터: "0|H0STCNT0|004|005930^...^현재가^..."
     *   (필드 구분: '^', 블록 구분: '|')
     *   국내 체결가는 3번째 블록의 2번째 필드(인덱스 1)
     *   해외 체결가는 3번째 블록의 12번째 필드(인덱스 11)
     */
    private void handleMessage(String text) {
        if (text == null || text.isEmpty()) return;

        // JSON 응답 (구독 확인/에러 등)은 무시
        if (text.startsWith("{")) {
            log.info("[WS] JSON response: {}", text.length() > 200 ? text.substring(0, 200) : text);
            return;
        }

        try {
            // 파이프 구분: "암호화여부|tr_id|데이터건수|데이터"
            String[] blocks = text.split("\\|");
            if (blocks.length < 4) return;

            String trId = blocks[1];
            String data = blocks[3];

            // 캐럿(^) 구분 필드
            String[] fields = data.split("\\^");

            if ("H0STCNT0".equals(trId)) {
                // 국내 실시간 체결: fields[0]=종목코드, fields[2]=체결가
                if (fields.length >= 3) {
                    String stockCode = fields[0];
                    double price = parseDoubleSafe(fields[2]);
                    if (price > 0) {
                        latestPrices.put(stockCode, price);
                        for (PriceListener l : priceListeners) {
                            try { l.onPrice(stockCode, price); } catch (Exception ex) { /* ignore */ }
                        }
                    }
                }
            } else if ("HDFSCNT0".equals(trId)) {
                // 해외 실시간 체결: fields[0]=종목코드, fields[11]=체결가(현재가)
                if (fields.length >= 12) {
                    String symbol = fields[0];
                    double price = parseDoubleSafe(fields[11]);
                    if (price > 0) {
                        // 해외 종목은 "거래소.심볼" 키로 저장 (subscribe 시 사용한 키와 일치)
                        // KIS는 종목코드만 돌려주므로 subscribedSymbols에서 매칭
                        for (String subKey : subscribedSymbols) {
                            if (subKey.endsWith("." + symbol) || subKey.equals(symbol)) {
                                latestPrices.put(subKey, price);
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[WS] Parse error: {}", e.getMessage());
        }
    }

    private double parseDoubleSafe(String val) {
        if (val == null || val.isEmpty()) return 0.0;
        try {
            return Double.parseDouble(val.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
