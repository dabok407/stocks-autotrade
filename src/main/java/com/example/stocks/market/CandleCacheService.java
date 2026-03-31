package com.example.stocks.market;

import com.example.stocks.db.CandleCacheEntity;
import com.example.stocks.db.CandleCacheRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Candle data cache service.
 * Downloads candle data from broker API and stores in H2 DB.
 * Provides cached data for backtest / optimization.
 */
@Service
public class CandleCacheService {

    private static final Logger log = LoggerFactory.getLogger(CandleCacheService.class);

    private static final String[] DEFAULT_SYMBOLS = {
            "005930", "035420", "000660", "051910", "005380"
    };
    private static final int[] DEFAULT_INTERVALS = {5, 15, 30, 60, 240};
    private static final int LOOKBACK_DAYS = 365;

    private final CandleService candleService;
    private final CandleCacheRepository cacheRepo;

    private final AtomicBoolean downloading = new AtomicBoolean(false);
    private final AtomicInteger totalJobs = new AtomicInteger(0);
    private final AtomicInteger completedJobs = new AtomicInteger(0);
    private volatile String currentTask = "";

    public CandleCacheService(CandleService candleService, CandleCacheRepository cacheRepo) {
        this.candleService = candleService;
        this.cacheRepo = cacheRepo;
    }

    // ===== Status queries =====

    public boolean isDownloading() { return downloading.get(); }
    public int getTotalJobs() { return totalJobs.get(); }
    public int getCompletedJobs() { return completedJobs.get(); }
    public String getCurrentTask() { return currentTask; }

    /**
     * Cache status: candle count per symbol per interval.
     */
    public Map<String, Map<Integer, Long>> getCacheStatus() {
        Map<String, Map<Integer, Long>> status = new LinkedHashMap<String, Map<Integer, Long>>();
        for (String symbol : DEFAULT_SYMBOLS) {
            Map<Integer, Long> byInterval = new LinkedHashMap<Integer, Long>();
            for (int interval : DEFAULT_INTERVALS) {
                byInterval.put(interval, cacheRepo.countBySymbolAndIntervalMin(symbol, interval));
            }
            status.put(symbol, byInterval);
        }
        return status;
    }

    // ===== Download =====

    public void downloadAllAsync() {
        if (!downloading.compareAndSet(false, true)) {
            log.info("Candle cache download already in progress.");
            return;
        }

        totalJobs.set(DEFAULT_SYMBOLS.length * DEFAULT_INTERVALS.length);
        completedJobs.set(0);

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    downloadAllSync();
                } finally {
                    downloading.set(false);
                    currentTask = "Complete";
                    log.info("Candle cache download complete. {} jobs processed.", completedJobs.get());
                }
            }
        }, "candle-cache-download");
        t.setDaemon(true);
        t.start();
    }

    private void downloadAllSync() {
        for (String symbol : DEFAULT_SYMBOLS) {
            for (int interval : DEFAULT_INTERVALS) {
                currentTask = symbol + " / " + interval + "min";
                try {
                    downloadSymbolInterval(symbol, interval);
                } catch (Exception e) {
                    log.error("Candle cache download failed: {} {}min - {}", symbol, interval, e.getMessage());
                }
                completedJobs.incrementAndGet();
            }
        }
    }

    @Transactional
    public void downloadSymbolInterval(String symbol, int interval) {
        log.info("Candle cache download start: {} {}min", symbol, interval);
        List<StockCandle> candles = candleService.fetchLookback(symbol, MarketType.KRX, interval, LOOKBACK_DAYS);
        int saved = 0;
        for (StockCandle c : candles) {
            if (c.candle_date_time_utc == null || c.candle_date_time_utc.isEmpty()) continue;
            if (cacheRepo.existsBySymbolAndIntervalMinAndCandleTsUtc(symbol, interval, c.candle_date_time_utc)) {
                continue;
            }
            CandleCacheEntity entity = new CandleCacheEntity();
            entity.setSymbol(symbol);
            entity.setIntervalMin(interval);
            entity.setCandleTsUtc(c.candle_date_time_utc);
            entity.setOpenPrice(c.opening_price);
            entity.setHighPrice(c.high_price);
            entity.setLowPrice(c.low_price);
            entity.setClosePrice(c.trade_price);
            entity.setVolume(c.candle_acc_trade_volume);
            cacheRepo.save(entity);
            saved++;
        }
        log.info("Candle cache download done: {} {}min - {} fetched, {} new", symbol, interval, candles.size(), saved);
    }

    // ===== Cache queries =====

    /**
     * Get cached candle data as StockCandle list.
     */
    public List<StockCandle> getCached(String symbol, int intervalMin) {
        List<CandleCacheEntity> entities =
                cacheRepo.findBySymbolAndIntervalMinOrderByCandleTsUtcAsc(symbol, intervalMin);
        List<StockCandle> candles = new ArrayList<StockCandle>(entities.size());
        for (CandleCacheEntity e : entities) {
            candles.add(toStockCandle(e));
        }
        return candles;
    }

    /**
     * Bulk load all cached data (for optimization engine).
     * Returns: Map<symbol, Map<intervalMin, List<StockCandle>>>
     */
    public Map<String, Map<Integer, List<StockCandle>>> getAllCached() {
        Map<String, Map<Integer, List<StockCandle>>> result =
                new LinkedHashMap<String, Map<Integer, List<StockCandle>>>();

        for (String symbol : DEFAULT_SYMBOLS) {
            Map<Integer, List<StockCandle>> byInterval =
                    new LinkedHashMap<Integer, List<StockCandle>>();
            for (int interval : DEFAULT_INTERVALS) {
                List<StockCandle> candles = getCached(symbol, interval);
                if (!candles.isEmpty()) {
                    byInterval.put(interval, candles);
                }
            }
            if (!byInterval.isEmpty()) {
                result.put(symbol, byInterval);
            }
        }
        return result;
    }

    /**
     * Get cached candles within a date range (DB-level filtering).
     */
    public List<StockCandle> getCachedBetween(String symbol, int intervalMin, String fromUtc, String toUtc) {
        List<CandleCacheEntity> entities =
                cacheRepo.findBySymbolAndIntervalMinAndCandleTsUtcBetweenOrderByCandleTsUtcAsc(
                        symbol, intervalMin, fromUtc, toUtc);
        List<StockCandle> candles = new ArrayList<StockCandle>(entities.size());
        for (CandleCacheEntity e : entities) {
            candles.add(toStockCandle(e));
        }
        return candles;
    }

    /**
     * Check if cached data exists for a symbol/interval.
     */
    public boolean hasCachedData(String symbol, int intervalMin) {
        return cacheRepo.countBySymbolAndIntervalMin(symbol, intervalMin) > 0;
    }

    // ===== Utilities =====

    private StockCandle toStockCandle(CandleCacheEntity e) {
        StockCandle c = new StockCandle();
        c.symbol = e.getSymbol();
        c.candle_date_time_utc = e.getCandleTsUtc();
        c.opening_price = e.getOpenPrice();
        c.high_price = e.getHighPrice();
        c.low_price = e.getLowPrice();
        c.trade_price = e.getClosePrice();
        c.candle_acc_trade_volume = e.getVolume();
        return c;
    }

    public String[] getDefaultSymbols() { return DEFAULT_SYMBOLS; }
    public int[] getDefaultIntervals() { return DEFAULT_INTERVALS; }
}
