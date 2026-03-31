package com.example.stocks.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockConfigRepository extends JpaRepository<StockConfigEntity, String> {

    List<StockConfigEntity> findByEnabledTrue();

    List<StockConfigEntity> findByMarketType(String marketType);
}
