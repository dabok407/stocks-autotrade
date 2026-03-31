package com.example.stocks.db;

import org.springframework.data.jpa.repository.JpaRepository;

public interface KrxAlldayConfigRepository extends JpaRepository<KrxAlldayConfigEntity, Integer> {

    default KrxAlldayConfigEntity loadOrCreate() {
        return findById(1).orElseGet(new java.util.function.Supplier<KrxAlldayConfigEntity>() {
            @Override
            public KrxAlldayConfigEntity get() {
                return save(new KrxAlldayConfigEntity());
            }
        });
    }
}
