package com.example.stocks.bot;

import com.example.stocks.market.MarketType;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Stock-specific safety checks:
 * - KRX VI (Volatility Interruption) proximity detection
 * - Settlement date calculation (T+2)
 * - Overnight gap risk detection
 */
public final class StockSafetyGuard {

    private StockSafetyGuard() {}

    /** KRX static VI triggers at +/-30%. Warn if price is within 2% of the limit. */
    private static final double VI_LIMIT_PCT = 0.30;
    private static final double VI_WARN_MARGIN = 0.02;

    /**
     * Returns true if the current price is within 2% of the KRX +-30% VI limit.
     *
     * @param price     current price
     * @param prevClose previous day's closing price
     * @return true if near VI trigger zone
     */
    public static boolean isNearViLimit(double price, double prevClose) {
        if (prevClose <= 0 || price <= 0) return false;

        double upperLimit = prevClose * (1.0 + VI_LIMIT_PCT);
        double lowerLimit = prevClose * (1.0 - VI_LIMIT_PCT);

        double upperThreshold = prevClose * (1.0 + VI_LIMIT_PCT - VI_WARN_MARGIN);
        double lowerThreshold = prevClose * (1.0 - VI_LIMIT_PCT + VI_WARN_MARGIN);

        return price >= upperThreshold || price <= lowerThreshold;
    }

    /**
     * Calculates the settlement date (T+2 business days, skipping weekends).
     * Does not account for market-specific holidays.
     *
     * @param tradeDate  the trade execution date
     * @param marketType the market type (KRX, NYSE, NASDAQ)
     * @return settlement date (T+2 business days)
     */
    public static LocalDate calculateSettlementDate(LocalDate tradeDate, MarketType marketType) {
        LocalDate date = tradeDate;
        int businessDays = 0;
        while (businessDays < 2) {
            date = date.plusDays(1);
            DayOfWeek dow = date.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                businessDays++;
            }
        }
        return date;
    }

    /**
     * Returns true if current hour is within 30 minutes of session end,
     * indicating overnight gap risk. Useful for avoiding new entries near close.
     *
     * @param sessionEndHour session end hour (e.g. 15 for KRX 15:15)
     * @param currentHour    current hour in market local time
     * @return true if within the risk window
     */
    public static boolean hasOvernightGapRisk(int sessionEndHour, int currentHour) {
        // Within 30 min means: if session ends at hour H, risk starts at H-1 (conservative)
        // Since we only have hour granularity, flag if currentHour >= sessionEndHour - 1
        return currentHour >= sessionEndHour - 1;
    }
}
