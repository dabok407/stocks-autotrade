package com.example.stocks.db;

import org.springframework.data.jpa.repository.JpaRepository;

public interface KrxScannerConfigRepository extends JpaRepository<KrxScannerConfigEntity, Integer> {

    /**
     * Config is always a single row with id=1. Creates with defaults if missing.
     */
    default KrxScannerConfigEntity loadOrCreate() {
        return findById(1).orElseGet(new java.util.function.Supplier<KrxScannerConfigEntity>() {
            @Override
            public KrxScannerConfigEntity get() {
                KrxScannerConfigEntity e = new KrxScannerConfigEntity();
                return save(e);
            }
        });
    }
}
