package com.example.stocks.db;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UsStockScannerConfigRepository extends JpaRepository<UsStockScannerConfigEntity, Integer> {

    /**
     * Config is always a single row with id=1. Creates with defaults if missing.
     */
    default UsStockScannerConfigEntity loadOrCreate() {
        return findById(1).orElseGet(new java.util.function.Supplier<UsStockScannerConfigEntity>() {
            @Override
            public UsStockScannerConfigEntity get() {
                UsStockScannerConfigEntity e = new UsStockScannerConfigEntity();
                return save(e);
            }
        });
    }
}
