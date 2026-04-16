package com.example.stocks.db;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface KrxOvertimeRankLogRepository extends JpaRepository<KrxOvertimeRankLogEntity, Long> {

    List<KrxOvertimeRankLogEntity> findByTradeDateOrderByRankNoAsc(LocalDate tradeDate);

    boolean existsByTradeDate(LocalDate tradeDate);

    /** 심볼의 최신 랭크 로그 (symbolName 조회 용). */
    Optional<KrxOvertimeRankLogEntity> findFirstBySymbolOrderByIdDesc(String symbol);
}
