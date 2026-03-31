package com.example.stocks.bot;

import java.util.List;
import java.util.Map;

/**
 * UI에 내려주는 "봇 상태" DTO.
 * (DB Entity와 분리하여 프론트 요구사항 변경에 유연하게 대응)
 */
public class BotStatus {

    private boolean running;
    private long startedAtEpochMillis;

    // UI 설정값
    private String mode; // PAPER/LIVE
    private String marketType; // KRX/NYSE/NASDAQ
    private int candleUnitMin;
    private double capitalKrw;

    // 시장 세션 상태
    private String marketSessionStatus; // OPEN / CLOSED / PRE_MARKET
    private String activeMarketType;    // 현재 활성 시장 유형

    // Risk limit (global)
    private int maxAddBuysGlobal;
    private double takeProfitPct;
    private double stopLossPct;
    private boolean strategyLock;
    private double minConfidence;
    private int timeStopMinutes;
    private String strategyIntervalsCsv;
    private String emaFilterCsv;

    private String strategyType;
    private List<String> strategies;

    // Order sizing (global)
    private double baseOrderKrw;

    // 자본 현황
    private double usedCapitalKrw;
    private double availableCapitalKrw;

    // 손익/KPI
    private double realizedPnlKrw;
    private double unrealizedPnlKrw;
    private double totalPnlKrw;
    private double roiPercent;

    private int sellCountToday;
    private int totalTrades;

    // 승/승률(SELL 기준)
    private int wins;
    private double winRate;

    private Map<String, StockStatus> stocks;

    // Market session status
    private String krxSessionStatus;   // OPEN / CLOSED / PRE_MARKET
    private long krxMinutesToClose;
    private String nyseSessionStatus;  // OPEN / CLOSED / PRE_MARKET
    private long nyseMinutesToClose;

    // 미장 기본설정
    private String usMode;
    private double usCapitalKrw;

    // 4-scanner status DTOs
    private ScannerStatusDto krxOpening;
    private ScannerStatusDto krxAllday;
    private ScannerStatusDto nyseOpening;
    private ScannerStatusDto nyseAllday;

    // 서버 재시작 시 자동 시작 여부
    private boolean autoStartEnabled;

    public boolean isRunning() { return running; }
    public void setRunning(boolean running) { this.running = running; }

    public long getStartedAtEpochMillis() { return startedAtEpochMillis; }
    public void setStartedAtEpochMillis(long startedAtEpochMillis) { this.startedAtEpochMillis = startedAtEpochMillis; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getMarketType() { return marketType; }
    public void setMarketType(String marketType) { this.marketType = marketType; }

    public int getCandleUnitMin() { return candleUnitMin; }
    public void setCandleUnitMin(int candleUnitMin) { this.candleUnitMin = candleUnitMin; }

    public double getCapitalKrw() { return capitalKrw; }
    public void setCapitalKrw(double capitalKrw) { this.capitalKrw = capitalKrw; }

    public String getUsMode() { return usMode; }
    public void setUsMode(String usMode) { this.usMode = usMode; }

    public double getUsCapitalKrw() { return usCapitalKrw; }
    public void setUsCapitalKrw(double usCapitalKrw) { this.usCapitalKrw = usCapitalKrw; }

    public String getMarketSessionStatus() { return marketSessionStatus; }
    public void setMarketSessionStatus(String marketSessionStatus) { this.marketSessionStatus = marketSessionStatus; }

    public String getActiveMarketType() { return activeMarketType; }
    public void setActiveMarketType(String activeMarketType) { this.activeMarketType = activeMarketType; }

    public int getMaxAddBuysGlobal() { return maxAddBuysGlobal; }
    public void setMaxAddBuysGlobal(int maxAddBuysGlobal) { this.maxAddBuysGlobal = Math.max(0, maxAddBuysGlobal); }

    public double getTakeProfitPct() { return takeProfitPct; }
    public void setTakeProfitPct(double takeProfitPct) { this.takeProfitPct = Math.max(0.0, takeProfitPct); }

    public double getStopLossPct() { return stopLossPct; }
    public void setStopLossPct(double stopLossPct) { this.stopLossPct = Math.max(0.0, stopLossPct); }

    public boolean isStrategyLock() { return strategyLock; }
    public void setStrategyLock(boolean strategyLock) { this.strategyLock = strategyLock; }

    public double getMinConfidence() { return minConfidence; }
    public void setMinConfidence(double minConfidence) { this.minConfidence = minConfidence; }

    public int getTimeStopMinutes() { return timeStopMinutes; }
    public void setTimeStopMinutes(int timeStopMinutes) { this.timeStopMinutes = timeStopMinutes; }

    public String getStrategyIntervalsCsv() { return strategyIntervalsCsv; }
    public void setStrategyIntervalsCsv(String csv) { this.strategyIntervalsCsv = csv; }

    public String getEmaFilterCsv() { return emaFilterCsv; }
    public void setEmaFilterCsv(String csv) { this.emaFilterCsv = csv; }

    public String getStrategyType() { return strategyType; }
    public void setStrategyType(String strategyType) { this.strategyType = strategyType; }

    public List<String> getStrategies() { return strategies; }
    public void setStrategies(List<String> strategies) { this.strategies = strategies; }

    public double getBaseOrderKrw() { return baseOrderKrw; }
    public void setBaseOrderKrw(double baseOrderKrw) { this.baseOrderKrw = baseOrderKrw; }

    public double getUsedCapitalKrw() { return usedCapitalKrw; }
    public void setUsedCapitalKrw(double usedCapitalKrw) { this.usedCapitalKrw = usedCapitalKrw; }

    public double getAvailableCapitalKrw() { return availableCapitalKrw; }
    public void setAvailableCapitalKrw(double availableCapitalKrw) { this.availableCapitalKrw = availableCapitalKrw; }

    public double getRealizedPnlKrw() { return realizedPnlKrw; }
    public void setRealizedPnlKrw(double realizedPnlKrw) { this.realizedPnlKrw = realizedPnlKrw; }

    public double getUnrealizedPnlKrw() { return unrealizedPnlKrw; }
    public void setUnrealizedPnlKrw(double unrealizedPnlKrw) { this.unrealizedPnlKrw = unrealizedPnlKrw; }

    public double getTotalPnlKrw() { return totalPnlKrw; }
    public void setTotalPnlKrw(double totalPnlKrw) { this.totalPnlKrw = totalPnlKrw; }

    public double getRoi() { return roiPercent; }
    public void setRoi(double roi) { this.roiPercent = roi; }

    public double getRoiPercent() { return roiPercent; }
    public void setRoiPercent(double roiPercent) { this.roiPercent = roiPercent; }

    public int getSellCountToday() { return sellCountToday; }
    public void setSellCountToday(int sellCountToday) { this.sellCountToday = sellCountToday; }

    public int getTotalTrades() { return totalTrades; }
    public void setTotalTrades(int totalTrades) { this.totalTrades = totalTrades; }

    public int getWins() { return wins; }
    public void setWins(int wins) { this.wins = wins; }

    public double getWinRate() { return winRate; }
    public void setWinRate(double winRate) { this.winRate = winRate; }

    public Map<String, StockStatus> getStocks() { return stocks; }
    public void setStocks(Map<String, StockStatus> stocks) { this.stocks = stocks; }

    public String getKrxSessionStatus() { return krxSessionStatus; }
    public void setKrxSessionStatus(String krxSessionStatus) { this.krxSessionStatus = krxSessionStatus; }

    public long getKrxMinutesToClose() { return krxMinutesToClose; }
    public void setKrxMinutesToClose(long krxMinutesToClose) { this.krxMinutesToClose = krxMinutesToClose; }

    public String getNyseSessionStatus() { return nyseSessionStatus; }
    public void setNyseSessionStatus(String nyseSessionStatus) { this.nyseSessionStatus = nyseSessionStatus; }

    public long getNyseMinutesToClose() { return nyseMinutesToClose; }
    public void setNyseMinutesToClose(long nyseMinutesToClose) { this.nyseMinutesToClose = nyseMinutesToClose; }

    public ScannerStatusDto getKrxOpening() { return krxOpening; }
    public void setKrxOpening(ScannerStatusDto krxOpening) { this.krxOpening = krxOpening; }

    public ScannerStatusDto getKrxAllday() { return krxAllday; }
    public void setKrxAllday(ScannerStatusDto krxAllday) { this.krxAllday = krxAllday; }

    public ScannerStatusDto getNyseOpening() { return nyseOpening; }
    public void setNyseOpening(ScannerStatusDto nyseOpening) { this.nyseOpening = nyseOpening; }

    public ScannerStatusDto getNyseAllday() { return nyseAllday; }
    public void setNyseAllday(ScannerStatusDto nyseAllday) { this.nyseAllday = nyseAllday; }

    public boolean isAutoStartEnabled() { return autoStartEnabled; }
    public void setAutoStartEnabled(boolean autoStartEnabled) { this.autoStartEnabled = autoStartEnabled; }

    /**
     * 개별 종목 현황 (대시보드 표시용).
     */
    public static class StockStatus {
        private String symbol;
        private String marketType;
        private String displayName;
        private boolean enabled;
        private double baseOrderKrw;
        private boolean positionOpen;
        private double avgPrice;
        private int qty;
        private int addBuys;
        private double lastPrice;
        private double realizedPnlKrw;
        private String entryStrategy;

        public String getSymbol() { return symbol; }
        public void setSymbol(String symbol) { this.symbol = symbol; }

        public String getMarketType() { return marketType; }
        public void setMarketType(String marketType) { this.marketType = marketType; }

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public double getBaseOrderKrw() { return baseOrderKrw; }
        public void setBaseOrderKrw(double baseOrderKrw) { this.baseOrderKrw = baseOrderKrw; }

        public boolean isPositionOpen() { return positionOpen; }
        public void setPositionOpen(boolean positionOpen) { this.positionOpen = positionOpen; }

        public double getAvgPrice() { return avgPrice; }
        public void setAvgPrice(double avgPrice) { this.avgPrice = avgPrice; }

        public int getQty() { return qty; }
        public void setQty(int qty) { this.qty = qty; }

        public int getAddBuys() { return addBuys; }
        public void setAddBuys(int addBuys) { this.addBuys = addBuys; }

        public double getLastPrice() { return lastPrice; }
        public void setLastPrice(double lastPrice) { this.lastPrice = lastPrice; }

        public double getRealizedPnlKrw() { return realizedPnlKrw; }
        public void setRealizedPnlKrw(double realizedPnlKrw) { this.realizedPnlKrw = realizedPnlKrw; }

        public String getEntryStrategy() { return entryStrategy; }
        public void setEntryStrategy(String entryStrategy) { this.entryStrategy = entryStrategy; }
    }
}
