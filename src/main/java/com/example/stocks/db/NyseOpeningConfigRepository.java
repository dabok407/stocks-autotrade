package com.example.stocks.db;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NyseOpeningConfigRepository extends JpaRepository<NyseOpeningConfigEntity, Integer> {

    default NyseOpeningConfigEntity loadOrCreate() {
        return findById(1).orElseGet(new java.util.function.Supplier<NyseOpeningConfigEntity>() {
            @Override
            public NyseOpeningConfigEntity get() {
                return save(new NyseOpeningConfigEntity());
            }
        });
    }
}
