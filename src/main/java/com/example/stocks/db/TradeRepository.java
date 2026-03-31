package com.example.stocks.db;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TradeRepository extends JpaRepository<TradeEntity, Long> {

    List<TradeEntity> findBySymbol(String symbol);

    List<TradeEntity> findByMode(String mode);

    Page<TradeEntity> findAllByOrderByTsEpochMsDesc(Pageable pageable);

    @Query("SELECT t FROM TradeEntity t WHERE t.action = 'SELL' AND t.mode = ?1 ORDER BY t.tsEpochMs DESC")
    List<TradeEntity> findSellsByMode(String mode);

    List<TradeEntity> findByTsEpochMsBetween(long from, long to);

    /** 특정 종목의 가장 최근 BUY/SELL 트레이드 조회 (Quick TP confidence 참조용) */
    TradeEntity findTop1BySymbolAndActionOrderByTsEpochMsDesc(String symbol, String action);
}
