package com.example.stocks.db;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SymbolNameCacheRepository extends JpaRepository<SymbolNameCacheEntity, String> {
}
