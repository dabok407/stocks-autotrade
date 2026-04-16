package com.example.stocks.trade;

import com.example.stocks.db.*;
import com.example.stocks.kis.KisPublicClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 심볼 → 종목명 해결 서비스.
 *
 * 요청 경로(getName/getNames)는 DB 소스만 사용해 즉시 반환.
 * KIS 원격 조회는 @Scheduled 백필로 분리되어 rate-limit 경쟁을 피함.
 *
 * 조회 우선순위(API 경로):
 *   1) 메모리 캐시 (기동 시 symbol_name_cache에서 warm-up)
 *   2) symbol_name_cache (영속)
 *   3) stock_config.displayName (수동 등록)
 *   4) krx_overtime_rank_log.symbolName (모닝러쉬 진입 기록)
 *
 * 백필 루프:
 *   trade_log의 distinct (symbol, marketType)에서 1~4단계로 미해결인 심볼을
 *   KIS inquire-price로 1건/500ms 씩 조회해 symbol_name_cache에 영속.
 */
@Service
public class SymbolNameService {

    private static final Logger log = LoggerFactory.getLogger(SymbolNameService.class);
    private static final long KIS_INTER_CALL_SLEEP_MS = 500L; // 3 req/s 제한 대비 안전
    private static final int KIS_MAX_CALLS_PER_RUN = 20;     // 백필 1회당 최대 조회

    private final SymbolNameCacheRepository cacheRepo;
    private final StockConfigRepository stockConfigRepo;
    private final KrxOvertimeRankLogRepository overtimeRankRepo;
    private final TradeRepository tradeRepo;
    private final KisPublicClient kisPublic;

    private final ConcurrentHashMap<String, String> memCache = new ConcurrentHashMap<String, String>();

    @Autowired
    public SymbolNameService(SymbolNameCacheRepository cacheRepo,
                             StockConfigRepository stockConfigRepo,
                             KrxOvertimeRankLogRepository overtimeRankRepo,
                             TradeRepository tradeRepo,
                             KisPublicClient kisPublic) {
        this.cacheRepo = cacheRepo;
        this.stockConfigRepo = stockConfigRepo;
        this.overtimeRankRepo = overtimeRankRepo;
        this.tradeRepo = tradeRepo;
        this.kisPublic = kisPublic;
    }

    @PostConstruct
    public void warmUp() {
        int loaded = 0, resolved = 0;
        try {
            // 1) symbol_name_cache → memCache
            List<SymbolNameCacheEntity> all = cacheRepo.findAll();
            for (SymbolNameCacheEntity e : all) {
                if (e.getSymbol() != null && e.getName() != null) {
                    memCache.put(e.getSymbol(), e.getName());
                    loaded++;
                }
            }
            // 2) trade_log에 등장한 심볼 중 cache 미등록인 것들을 DB 소스(stock_config/rank_log)로 즉시 해결
            //    KIS 호출 없이 순수 DB — 재시작 직후 첫 API 호출부터 이름이 보이게 함.
            List<Object[]> pairs = tradeRepo.findDistinctSymbolMarketPairs();
            if (pairs != null) {
                for (Object[] row : pairs) {
                    String sym = (String) row[0];
                    String mkt = row.length > 1 ? (String) row[1] : "KRX";
                    if (sym == null || sym.isEmpty()) continue;
                    if (memCache.containsKey(sym)) continue;
                    String name = resolveFromConfigOrRankLog(sym);
                    if (name != null) {
                        memCache.put(sym, name);
                        persist(sym, mkt == null ? "KRX" : mkt, name);
                        resolved++;
                    }
                }
            }
            log.info("SymbolNameService warm-up: {} from cache, {} resolved from stock_config/rank_log",
                    loaded, resolved);
        } catch (Exception ex) {
            log.warn("SymbolNameService warm-up failed: {}", ex.getMessage());
        }
    }

    // ═══ API 경로 (동기, KIS 호출 없음) ═══

    /** 단건 조회. DB 소스만 사용. 없으면 null. */
    public String getName(String symbol) {
        if (symbol == null || symbol.isEmpty()) return null;
        String hit = memCache.get(symbol);
        if (hit != null) return hit;
        String resolved = resolveFromDb(symbol);
        if (resolved != null) {
            memCache.put(symbol, resolved);
            persist(symbol, "KRX", resolved);
        }
        return resolved;
    }

    /** 일괄 조회. DB 소스만 사용. */
    public Map<String, String> getNames(Collection<String> symbols) {
        Map<String, String> result = new HashMap<String, String>();
        if (symbols == null || symbols.isEmpty()) return result;

        Set<String> misses = new HashSet<String>();
        for (String sym : symbols) {
            if (sym == null || sym.isEmpty()) continue;
            String hit = memCache.get(sym);
            if (hit != null) result.put(sym, hit);
            else misses.add(sym);
        }
        if (misses.isEmpty()) return result;

        // 영속 캐시 일괄 조회
        for (SymbolNameCacheEntity e : cacheRepo.findAllById(misses)) {
            if (e.getName() != null) {
                memCache.put(e.getSymbol(), e.getName());
                result.put(e.getSymbol(), e.getName());
                misses.remove(e.getSymbol());
            }
        }
        // stock_config / rank_log 개별 해결
        for (String sym : misses) {
            String resolved = resolveFromConfigOrRankLog(sym);
            if (resolved != null) {
                memCache.put(sym, resolved);
                persist(sym, "KRX", resolved);
                result.put(sym, resolved);
            }
        }
        return result;
    }

    /** 외부에서 이미 알고 있는 이름 등록 (예: 랭킹 수집기). */
    public void registerIfAbsent(String symbol, String marketType, String name) {
        if (symbol == null || name == null) return;
        if (memCache.containsKey(symbol)) return;
        memCache.put(symbol, name);
        persist(symbol, marketType == null ? "KRX" : marketType, name);
    }

    // ═══ @Scheduled 백필 (KIS 호출) ═══

    /**
     * 60초 주기로 trade_log의 미해결 심볼을 KIS로 조회해 캐시에 누적.
     * 기동 직후 한 번 실행되도록 initialDelay=15초.
     */
    @Scheduled(initialDelayString = "15000", fixedDelayString = "60000")
    public void backfillFromKis() {
        List<Object[]> pairs;
        try {
            pairs = tradeRepo.findDistinctSymbolMarketPairs();
        } catch (Exception ex) {
            log.warn("backfill: trade distinct lookup failed: {}", ex.getMessage());
            return;
        }
        if (pairs == null || pairs.isEmpty()) return;

        int calls = 0;
        for (Object[] row : pairs) {
            if (calls >= KIS_MAX_CALLS_PER_RUN) break;
            String sym = (String) row[0];
            String mkt = row.length > 1 ? (String) row[1] : "KRX";
            if (sym == null || sym.isEmpty()) continue;
            if (memCache.containsKey(sym)) continue;

            // 이미 DB/rank/config에 있으면 그걸로 해결하고 KIS 안 부름
            String resolved = resolveFromDb(sym);
            if (resolved != null) {
                memCache.put(sym, resolved);
                persist(sym, mkt, resolved);
                continue;
            }
            // KIS 조회 (KRX만) — search-stock-info 의 prdt_name 필드 사용
            if (!"KRX".equalsIgnoreCase(mkt)) continue;
            try {
                Map<String, Object> output = kisPublic.searchStockInfo(sym);
                if (output == null || output.isEmpty()) {
                    log.warn("backfill: KIS search-stock-info returned empty for {}", sym);
                } else {
                    Object prdt = output.get("prdt_name");
                    String name = (prdt == null) ? null : String.valueOf(prdt).trim();
                    if (name == null || name.isEmpty()) {
                        log.warn("backfill: KIS response for {} missing prdt_name (keys={})",
                                sym, output.keySet());
                    } else {
                        memCache.put(sym, name);
                        persist(sym, mkt, name);
                        log.info("symbol_name_cache backfilled: {} → {}", sym, name);
                    }
                }
            } catch (Exception ex) {
                log.warn("backfill KIS lookup failed for {}: {}", sym, ex.getMessage());
            }
            calls++;
            try { Thread.sleep(KIS_INTER_CALL_SLEEP_MS); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (calls > 0) {
            log.info("symbol_name_cache backfill run: {} KIS calls", calls);
        }
    }

    // ═══ 내부 ═══

    /** symbol_name_cache → stock_config → rank_log */
    private String resolveFromDb(String symbol) {
        try {
            Optional<SymbolNameCacheEntity> opt = cacheRepo.findById(symbol);
            if (opt.isPresent() && opt.get().getName() != null) return opt.get().getName();
        } catch (Exception ex) { /* continue */ }
        return resolveFromConfigOrRankLog(symbol);
    }

    /** stock_config.displayName → krx_overtime_rank_log.symbolName (최신) */
    private String resolveFromConfigOrRankLog(String symbol) {
        try {
            Optional<StockConfigEntity> sc = stockConfigRepo.findById(symbol);
            if (sc.isPresent() && sc.get().getDisplayName() != null && !sc.get().getDisplayName().isEmpty()) {
                return sc.get().getDisplayName();
            }
        } catch (Exception ex) { /* continue */ }
        try {
            Optional<KrxOvertimeRankLogEntity> opt = overtimeRankRepo.findFirstBySymbolOrderByIdDesc(symbol);
            if (opt.isPresent() && opt.get().getSymbolName() != null && !opt.get().getSymbolName().isEmpty()) {
                return opt.get().getSymbolName();
            }
        } catch (Exception ex) { /* continue */ }
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
