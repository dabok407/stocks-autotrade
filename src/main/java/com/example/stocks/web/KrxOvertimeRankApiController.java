package com.example.stocks.web;

import com.example.stocks.bot.KrxOvertimeRankCollector;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/**
 * KRX 시간외거래순위 수집 API.
 */
@RestController
@RequestMapping("/api/krx/overtime-rank")
public class KrxOvertimeRankApiController {

    private final KrxOvertimeRankCollector collector;

    public KrxOvertimeRankApiController(KrxOvertimeRankCollector collector) {
        this.collector = collector;
    }

    /**
     * 즉시 수집 (디버깅/강제 수집용).
     * POST /api/krx/overtime-rank/collect-now
     */
    @GetMapping("/collect-now")
    public ResponseEntity<Map<String, Object>> collectNow(
            @RequestParam(required = false, defaultValue = "false") boolean force) {
        Map<String, Object> result = collector.collectNow(force);
        return ResponseEntity.ok(result);
    }

    /**
     * 특정 날짜 데이터 조회.
     * GET /api/krx/overtime-rank/by-date?date=2026-04-09
     */
    @GetMapping("/by-date")
    public ResponseEntity<?> getByDate(@RequestParam(required = false) String date) {
        LocalDate d;
        if (date != null && !date.isEmpty()) {
            d = LocalDate.parse(date);
        } else {
            d = LocalDate.now(ZoneId.of("Asia/Seoul"));
        }
        return ResponseEntity.ok(collector.getByDate(d));
    }
}
