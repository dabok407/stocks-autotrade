package com.example.stocks.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CandleCacheRepository extends JpaRepository<CandleCacheEntity, Long> {

    List<CandleCacheEntity> findBySymbolAndIntervalMinOrderByCandleTsUtcAsc(String symbol, int intervalMin);

    CandleCacheEntity findTopBySymbolAndIntervalMinOrderByCandleTsUtcDesc(String symbol, int intervalMin);

    List<CandleCacheEntity> findBySymbolAndIntervalMinAndCandleTsUtcBetweenOrderByCandleTsUtcAsc(
            String symbol, int intervalMin, String fromUtc, String toUtc);

    long countBySymbolAndIntervalMin(String symbol, int intervalMin);

    boolean existsBySymbolAndIntervalMinAndCandleTsUtc(String symbol, int intervalMin, String candleTsUtc);

    void deleteBySymbolAndIntervalMin(String symbol, int intervalMin);
}
