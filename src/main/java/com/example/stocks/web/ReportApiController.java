package com.example.stocks.web;

import com.example.stocks.db.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 외부 스케줄러용 거래 리포트 API.
 * API 키 인증 (X-API-Key 헤더) — 로그인 불필요.
 */
@RestController
@RequestMapping("/api/report")
public class ReportApiController {

    @Value("${report.apiKey:}")
    private String reportApiKey;

    private final TradeRepository tradeRepo;
    private final PositionRepository positionRepo;
    private final BotConfigRepository botConfigRepo;

    public ReportApiController(TradeRepository tradeRepo,
                               PositionRepository positionRepo,
                               BotConfigRepository botConfigRepo) {
        this.tradeRepo = tradeRepo;
        this.positionRepo = positionRepo;
        this.botConfigRepo = botConfigRepo;
    }

    /**
     * 오전 장 중간 리포트 (12:10 KST 스케줄용).
     * 오늘 전체 거래 반환.
     */
    @GetMapping("/opening")
    public ResponseEntity<?> openingReport(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        ResponseEntity<?> authCheck = checkApiKey(apiKey);
        if (authCheck != null) return authCheck;

        return ResponseEntity.ok(buildReport("opening", LocalDate.now(ZoneId.of("Asia/Seoul"))));
    }

    /**
     * 일일 리포트 (스케줄러/텔레그램 전송용).
     *
     * V39 B5 (2026-04-18): 운영 사고 — 자정 스케줄러가 자정 직후(00:00~00:05) 호출하면
     *   LocalDate.now().minusDays(1) = 어제 → 어제 트레이드 반환. 정상.
     *   그러나 장중 테스트(예: 10:00 KST)로 호출 시 "어제" 데이터만 나와 빈 결과 보임.
     *   → date 파라미터 오버라이드 추가 (today/yesterday/YYYY-MM-DD).
     *
     * 쿼리 파라미터:
     *   - date=today         → 오늘 KST
     *   - date=yesterday     → 어제 KST (기본값, 자정 이후 스케줄러 호출)
     *   - date=YYYY-MM-DD    → 지정 날짜
     *   - 미지정              → 어제 (기존 동작 유지)
     */
    @GetMapping("/daily")
    public ResponseEntity<?> dailyReport(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestParam(value = "date", required = false) String dateParam) {
        ResponseEntity<?> authCheck = checkApiKey(apiKey);
        if (authCheck != null) return authCheck;

        LocalDate target;
        LocalDate todayKst = LocalDate.now(ZoneId.of("Asia/Seoul"));
        if (dateParam == null || dateParam.isEmpty() || "yesterday".equalsIgnoreCase(dateParam)) {
            target = todayKst.minusDays(1);
        } else if ("today".equalsIgnoreCase(dateParam)) {
            target = todayKst;
        } else {
            try {
                target = LocalDate.parse(dateParam);
            } catch (Exception e) {
                Map<String, String> err = new HashMap<String, String>();
                err.put("error", "INVALID_DATE");
                err.put("message", "date 는 today/yesterday 또는 YYYY-MM-DD 형식이어야 합니다. 받은 값: " + dateParam);
                return ResponseEntity.badRequest().body(err);
            }
        }
        return ResponseEntity.ok(buildReport("all", target));
    }

    /**
     * 특정 날짜 거래 리포트.
     */
    @GetMapping("/date/{date}")
    public ResponseEntity<?> dateReport(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @PathVariable String date) {
        ResponseEntity<?> authCheck = checkApiKey(apiKey);
        if (authCheck != null) return authCheck;

        LocalDate targetDate = LocalDate.parse(date);
        return ResponseEntity.ok(buildReport("all", targetDate));
    }

    // ===== Internal =====

    private Map<String, Object> buildReport(String type, LocalDate targetDate) {
        ZonedDateTime startKst = targetDate.atStartOfDay(ZoneId.of("Asia/Seoul"));
        ZonedDateTime endKst = startKst.plusDays(1);
        long fromMs = startKst.toInstant().toEpochMilli();
        long toMs = endKst.toInstant().toEpochMilli();

        List<TradeEntity> allTrades = tradeRepo.findAll();
        List<Map<String, Object>> trades = new ArrayList<Map<String, Object>>();

        int sellCount = 0, wins = 0;
        double totalPnl = 0;

        for (TradeEntity t : allTrades) {
            if (t.getTsEpochMs() < fromMs || t.getTsEpochMs() >= toMs) continue;

            Map<String, Object> trade = new LinkedHashMap<String, Object>();
            trade.put("id", t.getId());
            trade.put("tsEpochMs", t.getTsEpochMs());
            trade.put("time", Instant.ofEpochMilli(t.getTsEpochMs())
                    .atZone(ZoneId.of("Asia/Seoul"))
                    .format(DateTimeFormatter.ofPattern("HH:mm")));
            trade.put("symbol", t.getSymbol());
            trade.put("marketType", t.getMarketType());
            trade.put("action", t.getAction());
            trade.put("price", t.getPrice());
            trade.put("qty", t.getQty());
            trade.put("pnlKrw", t.getPnlKrw());
            trade.put("roiPercent", t.getRoiPercent());
            trade.put("mode", t.getMode());
            trade.put("patternType", t.getPatternType());
            trade.put("patternReason", t.getPatternReason());
            trade.put("avgBuyPrice", t.getAvgBuyPrice());
            trade.put("confidence", t.getConfidence());
            trades.add(trade);

            if ("SELL".equals(t.getAction()) || "PAPER_SELL".equals(t.getAction())) {
                sellCount++;
                double pnl = t.getPnlKrw() != null ? t.getPnlKrw().doubleValue() : 0;
                totalPnl += pnl;
                if (pnl > 0) wins++;
            }
        }

        // 현재 포지션
        List<Map<String, Object>> positions = new ArrayList<Map<String, Object>>();
        for (PositionEntity p : positionRepo.findAll()) {
            if (p.getQty() > 0) {
                Map<String, Object> pos = new LinkedHashMap<String, Object>();
                pos.put("symbol", p.getSymbol());
                pos.put("marketType", p.getMarketType());
                pos.put("qty", p.getQty());
                pos.put("avgPrice", p.getAvgPrice());
                pos.put("entryStrategy", p.getEntryStrategy());
                positions.add(pos);
            }
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("type", type);
        result.put("date", targetDate.toString());
        result.put("trades", trades);
        result.put("sellCount", sellCount);
        result.put("wins", wins);
        result.put("losses", sellCount - wins);
        result.put("winRate", sellCount > 0 ? Math.round(wins * 100.0 / sellCount) : 0);
        result.put("totalPnlKrw", Math.round(totalPnl));
        result.put("openPositions", positions);
        return result;
    }

    private ResponseEntity<?> checkApiKey(String apiKey) {
        if (reportApiKey == null || reportApiKey.isEmpty() || "changeme-replace-with-random-key".equals(reportApiKey)) {
            Map<String, String> err = new HashMap<String, String>();
            err.put("error", "REPORT_API_KEY_NOT_CONFIGURED");
            err.put("message", "환경변수 REPORT_API_KEY를 설정해주세요.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
        if (apiKey == null || !reportApiKey.equals(apiKey)) {
            Map<String, String> err = new HashMap<String, String>();
            err.put("error", "UNAUTHORIZED");
            err.put("message", "유효한 X-API-Key 헤더가 필요합니다.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(err);
        }
        return null; // OK
    }
}
