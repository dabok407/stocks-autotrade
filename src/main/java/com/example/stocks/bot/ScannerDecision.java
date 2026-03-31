package com.example.stocks.bot;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scanner decision log entry (shared by all 4 scanners).
 */
public class ScannerDecision {
    public final long tsEpochMs;
    public final String symbol;
    public final String action;     // BUY, SELL, SKIP, BLOCKED
    public final String result;     // EXECUTED, BLOCKED, SKIPPED, ERROR
    public final String reasonCode; // INDEX_FILTER, NO_SIGNAL, MAX_POS, INSUFFICIENT_BALANCE, etc.
    public final String reason;     // Human-readable description

    public ScannerDecision(long ts, String symbol, String action, String result,
                           String reasonCode, String reason) {
        this.tsEpochMs = ts;
        this.symbol = symbol;
        this.action = action;
        this.result = result;
        this.reasonCode = reasonCode;
        this.reason = reason;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("tsEpochMs", tsEpochMs);
        m.put("symbol", symbol);
        m.put("action", action);
        m.put("result", result);
        m.put("reasonCode", reasonCode);
        m.put("reason", reason);
        return m;
    }
}
