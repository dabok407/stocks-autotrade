package com.example.stocks.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Backtest response DTO.
 */
public class BacktestResponse {
    // legacy
    public double totalPnl;
    public double totalRoi;
    public int totalTrades;

    // v2
    public double totalReturn;
    public double roi;
    public int tradesCount;
    public int wins;
    public double winRate;
    public double finalCapital;

    // debug/info
    public int candleCount;
    public int candleUnitMin;
    public int periodDays;

    // multi symbols echo
    public List<String> symbols = new ArrayList<String>();

    // info/warn message (optional)
    public String note;

    // echo: strategies used
    public List<String> strategies = new ArrayList<String>();

    // echo: actual TP/SL values used
    public Double usedTpPct;
    public Double usedSlPct;

    // sell type counts
    public int tpSellCount;
    public int slSellCount;
    public int patternSellCount;
    public int tpMissCount;

    public List<BacktestTradeRow> trades = new ArrayList<BacktestTradeRow>();
}
