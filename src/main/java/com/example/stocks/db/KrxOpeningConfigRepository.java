package com.example.stocks.db;

import org.springframework.data.jpa.repository.JpaRepository;

public interface KrxOpeningConfigRepository extends JpaRepository<KrxOpeningConfigEntity, Integer> {

    default KrxOpeningConfigEntity loadOrCreate() {
        return findById(1).orElseGet(new java.util.function.Supplier<KrxOpeningConfigEntity>() {
            @Override
            public KrxOpeningConfigEntity get() {
                return save(new KrxOpeningConfigEntity());
            }
        });
    }
}
