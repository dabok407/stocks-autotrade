package com.example.stocks.db;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NyseAlldayConfigRepository extends JpaRepository<NyseAlldayConfigEntity, Integer> {

    default NyseAlldayConfigEntity loadOrCreate() {
        return findById(1).orElseGet(new java.util.function.Supplier<NyseAlldayConfigEntity>() {
            @Override
            public NyseAlldayConfigEntity get() {
                return save(new NyseAlldayConfigEntity());
            }
        });
    }
}
