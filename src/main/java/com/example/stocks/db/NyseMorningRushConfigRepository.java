package com.example.stocks.db;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NyseMorningRushConfigRepository extends JpaRepository<NyseMorningRushConfigEntity, Integer> {

    default NyseMorningRushConfigEntity loadOrCreate() {
        return findById(1).orElseGet(new java.util.function.Supplier<NyseMorningRushConfigEntity>() {
            @Override
            public NyseMorningRushConfigEntity get() {
                return save(new NyseMorningRushConfigEntity());
            }
        });
    }
}
