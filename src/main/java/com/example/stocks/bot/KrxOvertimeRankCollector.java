package com.example.stocks.bot;

import com.example.stocks.db.KrxOvertimeRankLogEntity;
import com.example.stocks.db.KrxOvertimeRankLogRepository;
import com.example.stocks.kis.KisPublicClient;
import com.example.stocks.market.MarketType;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/**
 * KRX 시간외거래순위 일일 수집기.
 *
 * 매일 18:05 KST 평일에 KIS API(FHPST02340000) 시간외 등락률 순위 조회 후
 * krx_overtime_rank_log 테이블에 저장.
 *
 * 다음날 08:50 KrxMorningRushService.collectRange()에서 이 데이터 로드하여
 * 모닝러쉬 target 종목 선정에 활용.
 *
 * 30일 누적 후 시간외 순위 → 다음날 급등 확률 백테스트 가능.
 */
@Service
public class KrxOvertimeRankCollector {

    private static final Logger log = LoggerFactory.getLogger(KrxOvertimeRankCollector.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int COLLECT_COUNT = 30;  // 상위 30개 종목

    private final KisPublicClient kisPublic;
    private final KrxOvertimeRankLogRepository repo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KrxOvertimeRankCollector(KisPublicClient kisPublic, KrxOvertimeRankLogRepository repo) {
        this.kisPublic = kisPublic;
        this.repo = repo;
    }

    /**
     * 매일 18:05 KST 평일 자동 수집.
     * 시간외 단일가 종료(18:00) + 5분 buffer.
     */
    @Scheduled(cron = "0 5 18 * * MON-FRI", zone = "Asia/Seoul")
    public void collectDaily() {
        LocalDate today = LocalDate.now(KST);

        // 한국 거래일 체크
        if (MarketCalendar.isHoliday(today, MarketType.KRX)) {
            log.info("[OvertimeRankCollector] 공휴일 skip: {}", today);
            return;
        }

        collectAndSave(today);
    }

    /**
     * 수동/강제 수집 (collect-now API에서 호출).
     * @return 수집 결과 (종목 list + 상태)
     */
    public Map<String, Object> collectNow() {
        return collectNow(false);
    }

    public Map<String, Object> collectNow(boolean force) {
        LocalDate today = LocalDate.now(KST);
        return collectAndSave(today, force);
    }

    private Map<String, Object> collectAndSave(LocalDate tradeDate) {
        return collectAndSave(tradeDate, false);
    }

    private Map<String, Object> collectAndSave(LocalDate tradeDate, boolean force) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("tradeDate", tradeDate.toString());

        // force=true: 기존 데이터 삭제 후 재수집
        if (force && repo.existsByTradeDate(tradeDate)) {
            List<KrxOvertimeRankLogEntity> old = repo.findByTradeDateOrderByRankNoAsc(tradeDate);
            repo.deleteAll(old);
            log.info("[OvertimeRankCollector] force=true: {} 기존 {}건 삭제", tradeDate, old.size());
        }

        // 중복 체크
        if (!force && repo.existsByTradeDate(tradeDate)) {
            log.info("[OvertimeRankCollector] {} 이미 수집됨, skip", tradeDate);
            result.put("status", "ALREADY_EXISTS");
            result.put("message", tradeDate + " 이미 수집됨");
            // 기존 데이터 반환
            List<KrxOvertimeRankLogEntity> existing = repo.findByTradeDateOrderByRankNoAsc(tradeDate);
            result.put("count", existing.size());
            result.put("data", entitiesToList(existing));
            return result;
        }

        // KIS API 호출
        List<Map<String, Object>> ranking;
        try {
            ranking = kisPublic.getOvertimeUpdownRanking(COLLECT_COUNT);
        } catch (Exception e) {
            log.error("[OvertimeRankCollector] API 호출 실패: {}", e.getMessage());
            result.put("status", "API_ERROR");
            result.put("message", e.getMessage());
            return result;
        }

        if (ranking == null || ranking.isEmpty()) {
            log.warn("[OvertimeRankCollector] API 결과 0건");
            result.put("status", "EMPTY");
            result.put("message", "API 결과 0건");
            return result;
        }

        // 첫 항목의 전체 키 + 값 로그 (필드명 확인용)
        if (!ranking.isEmpty()) {
            log.info("[OvertimeRankCollector] API 응답 필드 keys: {}", ranking.get(0).keySet());
            log.info("[OvertimeRankCollector] API 응답 첫 항목: {}", ranking.get(0));
        }

        // DB 저장
        Instant now = Instant.now();
        List<Map<String, Object>> savedList = new ArrayList<Map<String, Object>>();
        int rankNo = 1;

        for (Map<String, Object> item : ranking) {
            try {
                KrxOvertimeRankLogEntity entity = new KrxOvertimeRankLogEntity();
                entity.setTradeDate(tradeDate);
                entity.setCollectedAt(now);
                entity.setRankNo(rankNo);

                // KIS API 필드 매핑 (시간외 전용 필드 사용)
                // ovtm_untp_prpr = 시간외 현재가
                // ovtm_untp_prdy_ctrt = 시간외 전일대비 등락률 %
                // ovtm_untp_vol = 시간외 거래량
                // stck_prpr = 정규장 종가 (시간외 아님, 참고용)
                String symbol = getStr(item, "mksc_shrn_iscd");
                String symbolName = getStr(item, "hts_kor_isnm");
                String priceStr = getStr(item, "ovtm_untp_prpr");       // 시간외 현재가
                String changePctStr = getStr(item, "ovtm_untp_prdy_ctrt"); // 시간외 등락률
                String volumeStr = getStr(item, "ovtm_untp_vol");       // 시간외 거래량
                String amountStr = getStr(item, "acml_vol");            // 누적 거래량 (정규장)

                entity.setSymbol(symbol != null ? symbol.trim() : "");
                entity.setSymbolName(symbolName != null ? symbolName.trim() : "");

                if (priceStr != null && !priceStr.isEmpty()) {
                    entity.setCurrentPrice(new BigDecimal(priceStr.trim()));
                }
                if (changePctStr != null && !changePctStr.isEmpty()) {
                    entity.setChangePct(new BigDecimal(changePctStr.trim()));
                }
                if (volumeStr != null && !volumeStr.isEmpty()) {
                    try { entity.setVolume(Long.parseLong(volumeStr.trim())); } catch (NumberFormatException ignore) {}
                }
                if (amountStr != null && !amountStr.isEmpty()) {
                    try { entity.setTradeAmount(Long.parseLong(amountStr.trim())); } catch (NumberFormatException ignore) {}
                }

                // raw JSON (디버깅용)
                try {
                    entity.setRawJson(objectMapper.writeValueAsString(item));
                } catch (Exception ignore) {}

                repo.save(entity);

                // 결과 list에 추가
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("rank", rankNo);
                row.put("symbol", entity.getSymbol());
                row.put("name", entity.getSymbolName());
                row.put("price", entity.getCurrentPrice());
                row.put("changePct", entity.getChangePct());
                row.put("volume", entity.getVolume());
                savedList.add(row);

                log.info("[OvertimeRankCollector] #{} {} {} 가격={} 등락={}%",
                        rankNo, entity.getSymbol(), entity.getSymbolName(),
                        entity.getCurrentPrice(), entity.getChangePct());

                rankNo++;
            } catch (Exception e) {
                log.warn("[OvertimeRankCollector] #{} 저장 실패: {}", rankNo, e.getMessage());
                rankNo++;
            }
        }

        log.info("[OvertimeRankCollector] {} 수집 완료: {}건 저장", tradeDate, savedList.size());

        result.put("status", "OK");
        result.put("count", savedList.size());
        result.put("data", savedList);
        return result;
    }

    /**
     * 특정 거래일의 수집 데이터 조회.
     */
    public List<Map<String, Object>> getByDate(LocalDate tradeDate) {
        List<KrxOvertimeRankLogEntity> entities = repo.findByTradeDateOrderByRankNoAsc(tradeDate);
        return entitiesToList(entities);
    }

    private List<Map<String, Object>> entitiesToList(List<KrxOvertimeRankLogEntity> entities) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (KrxOvertimeRankLogEntity e : entities) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("rank", e.getRankNo());
            row.put("symbol", e.getSymbol());
            row.put("name", e.getSymbolName());
            row.put("price", e.getCurrentPrice());
            row.put("changePct", e.getChangePct());
            row.put("volume", e.getVolume());
            list.add(row);
        }
        return list;
    }

    private String getStr(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}
