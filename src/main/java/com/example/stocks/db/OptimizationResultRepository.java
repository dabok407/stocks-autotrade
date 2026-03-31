package com.example.stocks.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OptimizationResultRepository extends JpaRepository<OptimizationResultEntity, Long> {

    List<OptimizationResultEntity> findByRunIdOrderByRoiDesc(String runId);

    List<OptimizationResultEntity> findByRunIdAndSymbolOrderByRoiDesc(String runId, String symbol);

    long countByRunId(String runId);

    void deleteByRunId(String runId);

    // Phase-based queries
    List<OptimizationResultEntity> findByRunIdAndPhaseOrderByRoiDesc(String runId, int phase);

    List<OptimizationResultEntity> findByRunIdAndPhaseAndSymbolOrderByRoiDesc(String runId, int phase, String symbol);
}
