package com.example.stocks.bot;

import java.util.Collections;
import java.util.List;

/**
 * Scanner status DTO for dashboard polling.
 * Each scanner (KRX Opening, KRX AllDay, NYSE Opening, NYSE AllDay) returns this.
 */
public class ScannerStatusDto {

    private boolean running;
    private String mode;
    private String statusText;
    private int scanCount;
    private int activePositions;
    private List<String> scannedSymbols;
    private long lastTickEpochMs;

    public ScannerStatusDto() {
        this.scannedSymbols = Collections.emptyList();
    }

    public boolean isRunning() { return running; }
    public void setRunning(boolean running) { this.running = running; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getStatusText() { return statusText; }
    public void setStatusText(String statusText) { this.statusText = statusText; }

    public int getScanCount() { return scanCount; }
    public void setScanCount(int scanCount) { this.scanCount = scanCount; }

    public int getActivePositions() { return activePositions; }
    public void setActivePositions(int activePositions) { this.activePositions = activePositions; }

    public List<String> getScannedSymbols() { return scannedSymbols; }
    public void setScannedSymbols(List<String> scannedSymbols) { this.scannedSymbols = scannedSymbols; }

    public long getLastTickEpochMs() { return lastTickEpochMs; }
    public void setLastTickEpochMs(long lastTickEpochMs) { this.lastTickEpochMs = lastTickEpochMs; }
}
