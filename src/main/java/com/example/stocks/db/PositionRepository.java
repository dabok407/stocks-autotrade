package com.example.stocks.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PositionRepository extends JpaRepository<PositionEntity, String> {

    List<PositionEntity> findByMarketType(String marketType);
}
