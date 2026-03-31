package com.example.stocks.bot;

import com.example.stocks.db.KrxAlldayConfigEntity;
import com.example.stocks.db.KrxOpeningConfigEntity;
import com.example.stocks.db.NyseAlldayConfigEntity;
import com.example.stocks.db.NyseOpeningConfigEntity;
import com.example.stocks.market.MarketType;

import java.time.LocalTime;

/**
 * Simple value object holding scanner timing windows for a specific market/scanner type.
 */
public final class ScannerTimingConfig {

    private final MarketType marketType;
    private final LocalTime rangeStart;   // null for allday scanners
    private final LocalTime rangeEnd;     // null for allday scanners
    private final LocalTime entryStart;
    private final LocalTime entryEnd;
    private final LocalTime sessionEnd;

    private ScannerTimingConfig(MarketType marketType,
                                LocalTime rangeStart, LocalTime rangeEnd,
                                LocalTime entryStart, LocalTime entryEnd,
                                LocalTime sessionEnd) {
        this.marketType = marketType;
        this.rangeStart = rangeStart;
        this.rangeEnd = rangeEnd;
        this.entryStart = entryStart;
        this.entryEnd = entryEnd;
        this.sessionEnd = sessionEnd;
    }

    public static ScannerTimingConfig fromKrxOpening(KrxOpeningConfigEntity cfg) {
        return new ScannerTimingConfig(
                MarketType.KRX,
                LocalTime.of(cfg.getRangeStartHour(), cfg.getRangeStartMin()),
                LocalTime.of(cfg.getRangeEndHour(), cfg.getRangeEndMin()),
                LocalTime.of(cfg.getEntryStartHour(), cfg.getEntryStartMin()),
                LocalTime.of(cfg.getEntryEndHour(), cfg.getEntryEndMin()),
                LocalTime.of(cfg.getSessionEndHour(), cfg.getSessionEndMin())
        );
    }

    public static ScannerTimingConfig fromKrxAllday(KrxAlldayConfigEntity cfg) {
        return new ScannerTimingConfig(
                MarketType.KRX,
                null, null,
                LocalTime.of(cfg.getEntryStartHour(), cfg.getEntryStartMin()),
                LocalTime.of(cfg.getEntryEndHour(), cfg.getEntryEndMin()),
                LocalTime.of(cfg.getSessionEndHour(), cfg.getSessionEndMin())
        );
    }

    public static ScannerTimingConfig fromNyseOpening(NyseOpeningConfigEntity cfg) {
        return new ScannerTimingConfig(
                MarketType.NYSE,
                LocalTime.of(cfg.getRangeStartHour(), cfg.getRangeStartMin()),
                LocalTime.of(cfg.getRangeEndHour(), cfg.getRangeEndMin()),
                LocalTime.of(cfg.getEntryStartHour(), cfg.getEntryStartMin()),
                LocalTime.of(cfg.getEntryEndHour(), cfg.getEntryEndMin()),
                LocalTime.of(cfg.getSessionEndHour(), cfg.getSessionEndMin())
        );
    }

    public static ScannerTimingConfig fromNyseAllday(NyseAlldayConfigEntity cfg) {
        return new ScannerTimingConfig(
                MarketType.NYSE,
                null, null,
                LocalTime.of(cfg.getEntryStartHour(), cfg.getEntryStartMin()),
                LocalTime.of(cfg.getEntryEndHour(), cfg.getEntryEndMin()),
                LocalTime.of(cfg.getSessionEndHour(), cfg.getSessionEndMin())
        );
    }

    // ========== Getters ==========

    public MarketType getMarketType() { return marketType; }

    /** Range start time. Null for allday scanners (no range phase). */
    public LocalTime getRangeStart() { return rangeStart; }

    /** Range end time. Null for allday scanners. */
    public LocalTime getRangeEnd() { return rangeEnd; }

    public LocalTime getEntryStart() { return entryStart; }

    public LocalTime getEntryEnd() { return entryEnd; }

    public LocalTime getSessionEnd() { return sessionEnd; }

    /** Returns true if this scanner has a range-building phase (opening scanners). */
    public boolean hasRangePhase() { return rangeStart != null && rangeEnd != null; }
}
