package com.example.stocks.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StrategyStateRepository extends JpaRepository<StrategyStateEntity, Long> {

    List<StrategyStateEntity> findBySymbol(String symbol);

    StrategyStateEntity findBySymbolAndStrategyType(String symbol, String strategyType);
}
