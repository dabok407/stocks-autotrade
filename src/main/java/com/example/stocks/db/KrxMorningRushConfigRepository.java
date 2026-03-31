package com.example.stocks.db;

import org.springframework.data.jpa.repository.JpaRepository;

public interface KrxMorningRushConfigRepository extends JpaRepository<KrxMorningRushConfigEntity, Integer> {

    default KrxMorningRushConfigEntity loadOrCreate() {
        return findById(1).orElseGet(new java.util.function.Supplier<KrxMorningRushConfigEntity>() {
            @Override
            public KrxMorningRushConfigEntity get() {
                return save(new KrxMorningRushConfigEntity());
            }
        });
    }
}
