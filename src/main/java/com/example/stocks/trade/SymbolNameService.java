package com.example.stocks.trade;

import com.example.stocks.db.*;
import com.example.stocks.kis.KisPublicClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 심볼 → 종목명 해결 서비스.
 *
 * 조회 우선순위:
 *   1) 메모리 캐시 (재시작 시 DB에서 warm-up)
 *   2) symbol_name_cache (영속)
 *   3) stock_config.displayName (수동 등록)
 *   4) krx_overtime_rank_log.symbolName (KRX 모닝러쉬 진입 기록)
 *   5) KIS inquire-price의 hts_kor_isnm (KRX only)
 *
 * 2~5단계 hit 시 1), 2)에 자동 기록. 영속 캐시라 재시작 후에도 유지되며,
 * 신규 거래 심볼이 등장하면 자동으로 KIS에서 1회 조회 후 누적.
 */
@Service
public class SymbolNameService {

    private static final Logger log = LoggerFactory.getLogger(SymbolNameService.class);

    private final SymbolNameCacheRepository cacheRepo;
    private final StockConfigRepository stockConfigRepo;
    private final KrxOvertimeRankLogRepository overtimeRankRepo;
    private final KisPublicClient kisPublic;

    private final ConcurrentHashMap<String, String> memCache = new ConcurrentHashMap<String, String>();

    @Autowired
    public SymbolNameService(SymbolNameCacheRepository cacheRepo,
                             StockConfigRepository stockConfigRepo,
                             KrxOvertimeRankLogRepository overtimeRankRepo,
                             KisPublicClient kisPublic) {
        this.cacheRepo = cacheRepo;
        this.stockConfigRepo = stockConfigRepo;
        this.overtimeRankRepo = overtimeRankRepo;
        this.kisPublic = kisPublic;
    }

    @PostConstruct
    public void warmUp() {
        try {
            List<SymbolNameCacheEntity> all = cacheRepo.findAll();
            for (SymbolNameCacheEntity e : all) {
                if (e.getSymbol() != null && e.getName() != null) {
                    memCache.put(e.getSymbol(), e.getName());
                }
            }
            log.info("SymbolNameService warm-up: {} entries loaded from symbol_name_cache", all.size());
        } catch (Exception ex) {
            log.warn("SymbolNameService warm-up failed: {}", ex.getMessage());
        }
    }

    /** 단건 조회. 알 수 없으면 null 반환. */
    public String getName(String symbol, String marketType) {
        if (symbol == null || symbol.isEmpty()) return null;

        String hit = memCache.get(symbol);
        if (hit != null) return hit;

        String mkt = (marketType == null || marketType.isEmpty()) ? "KRX" : marketType;
        String name = resolveMissing(symbol, mkt);
        if (name != null) {
            memCache.put(symbol, name);
            persist(symbol, mkt, name);
        }
        return name;
    }

    /** 여러 심볼 일괄 해결. symbol → name 맵(없는 심볼은 미포함). */
    public Map<String, String> getNames(Collection<String> symbols, Map<String, String> marketBySymbol) {
        Map<String, String> result = new HashMap<String, String>();
        if (symbols == null || symbols.isEmpty()) return result;

        Set<String> misses = new HashSet<String>();
        for (String sym : symbols) {
            if (sym == null || sym.isEmpty()) continue;
            String hit = memCache.get(sym);
            if (hit != null) {
                result.put(sym, hit);
            } else {
                misses.add(sym);
            }
        }
        if (misses.isEmpty()) return result;

        // DB 영속 캐시에서 배치 조회
        List<SymbolNameCacheEntity> cached = cacheRepo.findAllById(misses);
        for (SymbolNameCacheEntity e : cached) {
            if (e.getName() != null) {
                memCache.put(e.getSymbol(), e.getName());
                result.put(e.getSymbol(), e.getName());
                misses.remove(e.getSymbol());
            }
        }
        if (misses.isEmpty()) return result;

        // 나머지는 stock_config / rank_log / KIS 순으로 개별 해결
        for (String sym : misses) {
            String mkt = marketBySymbol != null && marketBySymbol.get(sym) != null
                    ? marketBySymbol.get(sym) : "KRX";
            String name = resolveMissing(sym, mkt);
            if (name != null) {
                memCache.put(sym, name);
                persist(sym, mkt, name);
                result.put(sym, name);
            }
        }
        return result;
    }

    /** 외부에서 이미 알고 있는 이름을 수동 등록 (예: 랭킹 수집기). */
    public void registerIfAbsent(String symbol, String marketType, String name) {
        if (symbol == null || name == null) return;
        if (memCache.containsKey(symbol)) return;
        memCache.put(symbol, name);
        persist(symbol, marketType == null ? "KRX" : marketType, name);
    }

    // ─── 내부 ────────────────────────────────────────────────

    private String resolveMissing(String symbol, String marketType) {
        // 1) DB 영속 캐시
        try {
            Optional<SymbolNameCacheEntity> opt = cacheRepo.findById(symbol);
            if (opt.isPresent() && opt.get().getName() != null) {
                return opt.get().getName();
            }
        } catch (Exception ex) { /* continue */ }

        // 2) stock_config.displayName
        try {
            Optional<StockConfigEntity> sc = stockConfigRepo.findById(symbol);
            if (sc.isPresent() && sc.get().getDisplayName() != null && !sc.get().getDisplayName().isEmpty()) {
                return sc.get().getDisplayName();
            }
        } catch (Exception ex) { /* continue */ }

        // 3) krx_overtime_rank_log (최신)
        try {
            Optional<KrxOvertimeRankLogEntity> opt = overtimeRankRepo.findFirstBySymbolOrderByIdDesc(symbol);
            if (opt.isPresent() && opt.get().getSymbolName() != null && !opt.get().getSymbolName().isEmpty()) {
                return opt.get().getSymbolName();
            }
        } catch (Exception ex) { /* continue */ }

        // 4) KIS inquire-price (KRX only)
        if ("KRX".equalsIgnoreCase(marketType)) {
            try {
                Map<String, Object> output = kisPublic.getDomesticCurrentPrice(symbol);
                Object isnm = (output != null) ? output.get("hts_kor_isnm") : null;
                if (isnm != null) {
                    String name = String.valueOf(isnm).trim();
                    if (!name.isEmpty()) return name;
                }
            } catch (Exception ex) {
                log.debug("KIS name lookup failed for {}: {}", symbol, ex.getMessage());
            }
        }
        return null;
    }

    private void persist(String symbol, String marketType, String name) {
        try {
            SymbolNameCacheEntity e = new SymbolNameCacheEntity();
            e.setSymbol(symbol);
            e.setMarketType(marketType == null ? "KRX" : marketType);
            e.setName(name);
            e.setUpdatedAt(System.currentTimeMillis());
            cacheRepo.save(e);
        } catch (Exception ex) {
            log.debug("symbol_name_cache persist failed for {}: {}", symbol, ex.getMessage());
        }
    }
}
