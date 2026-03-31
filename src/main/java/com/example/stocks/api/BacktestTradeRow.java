package com.example.stocks.api;

public class BacktestTradeRow {
    /**
     * Standard keys for FE:
     * - ts: timestamp string
     * - orderType: order type (e.g., pattern name)
     * - pnlKrw: P&L in KRW
     */
    public String ts;
    public String orderType;
    public double pnlKrw;

    /** Legacy/internal compatibility fields */
    public long tsEpochMs;
    public String patternType;
    public double pnl;

    public String symbol;
    public String action;
    public double price;
    public double qty;
    public String note;

    /** Average buy price at sell time (0 for buy entries) */
    public double avgBuyPrice;
    /** ROI percentage */
    public double roiPercent;
    /** Pattern confidence score (1~10) */
    public double confidence;
    /** Candle interval used for this trade */
    public int candleUnitMin;
}
