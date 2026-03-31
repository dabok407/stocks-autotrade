package com.example.stocks.api;

import com.example.stocks.backtest.BacktestService;
import com.example.stocks.backtest.BatchOptimizationService;
import com.example.stocks.db.OptimizationResultEntity;
import com.example.stocks.market.CandleCacheService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/backtest")
public class BacktestApiController {

    private final BacktestService backtestService;
    private final CandleCacheService candleCacheService;
    private final BatchOptimizationService optimizationService;

    // Async backtest storage: jobId -> "running" | BacktestResponse | error message
    private final ConcurrentHashMap<String, Object> asyncJobs = new ConcurrentHashMap<String, Object>();
    private final AtomicLong jobSeq = new AtomicLong(0);

    public BacktestApiController(BacktestService backtestService,
                                  CandleCacheService candleCacheService,
                                  BatchOptimizationService optimizationService) {
        this.backtestService = backtestService;
        this.candleCacheService = candleCacheService;
        this.optimizationService = optimizationService;
    }

    @PostMapping("/run")
    public ResponseEntity<BacktestResponse> run(@RequestBody BacktestRequest req) {
        BacktestResponse body = backtestService.run(req);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(body);
    }

    // ===== Async backtest =====

    @PostMapping("/run-async")
    public ResponseEntity<Map<String, Object>> runAsync(@RequestBody final BacktestRequest req) {
        final String jobId = "bt-" + System.currentTimeMillis() + "-" + jobSeq.incrementAndGet();
        asyncJobs.put(jobId, "running");

        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    BacktestResponse body = backtestService.run(req);
                    asyncJobs.put(jobId, body);
                } catch (Exception e) {
                    asyncJobs.put(jobId, "error:" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                }
            }
        }, "async-backtest-" + jobId);
        t.setDaemon(true);
        t.start();

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", "started");
        result.put("jobId", jobId);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/async-result/{jobId}")
    public ResponseEntity<Object> asyncResult(@PathVariable String jobId) {
        Object val = asyncJobs.get(jobId);
        if (val == null) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("status", "not_found");
            return ResponseEntity.status(404).body((Object) result);
        }
        if ("running".equals(val)) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("status", "running");
            result.put("jobId", jobId);
            return ResponseEntity.ok((Object) result);
        }
        if (val instanceof String && ((String) val).startsWith("error:")) {
            asyncJobs.remove(jobId);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("status", "error");
            result.put("message", ((String) val).substring(6));
            return ResponseEntity.ok((Object) result);
        }
        asyncJobs.remove(jobId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(val);
    }

    // ===== Candle cache API =====

    @PostMapping("/candle-cache/download")
    public ResponseEntity<Map<String, Object>> downloadCandleCache() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (candleCacheService.isDownloading()) {
            result.put("status", "already_running");
            result.put("progress", candleCacheService.getCompletedJobs() + "/" + candleCacheService.getTotalJobs());
            result.put("currentTask", candleCacheService.getCurrentTask());
        } else {
            candleCacheService.downloadAllAsync();
            result.put("status", "started");
            result.put("totalJobs", candleCacheService.getTotalJobs());
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/candle-cache/status")
    public ResponseEntity<Map<String, Object>> candleCacheStatus() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("downloading", candleCacheService.isDownloading());
        result.put("progress", candleCacheService.getCompletedJobs() + "/" + candleCacheService.getTotalJobs());
        result.put("currentTask", candleCacheService.getCurrentTask());
        result.put("cache", candleCacheService.getCacheStatus());
        return ResponseEntity.ok(result);
    }

    // ===== Optimization API =====

    @PostMapping("/optimization/run")
    public ResponseEntity<Map<String, Object>> startOptimization() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (optimizationService.isRunning()) {
            result.put("status", "already_running");
            result.put("runId", optimizationService.getCurrentRunId());
            result.put("progress", optimizationService.getCompletedCombinations() + "/" + optimizationService.getTotalCombinations());
        } else {
            String runId = optimizationService.startOptimization();
            result.put("status", "started");
            result.put("runId", runId);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/optimization/status/{runId}")
    public ResponseEntity<Map<String, Object>> optimizationStatus(@PathVariable String runId) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("runId", runId);
        result.put("running", optimizationService.isRunning());
        result.put("completed", optimizationService.getCompletedCombinations());
        result.put("total", optimizationService.getTotalCombinations());
        long total = optimizationService.getTotalCombinations();
        long done = optimizationService.getCompletedCombinations();
        result.put("progressPct", total > 0 ? (done * 100.0 / total) : 0);
        result.put("message", optimizationService.getStatusMessage());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/optimization/results/{runId}")
    public ResponseEntity<List<OptimizationResultEntity>> optimizationResults(@PathVariable String runId) {
        return ResponseEntity.ok(optimizationService.getResults(runId));
    }

    @GetMapping("/optimization/results/{runId}/{symbol}")
    public ResponseEntity<List<OptimizationResultEntity>> optimizationResultsBySymbol(
            @PathVariable String runId, @PathVariable String symbol) {
        return ResponseEntity.ok(optimizationService.getResultsBySymbol(runId, symbol));
    }

    @GetMapping("/optimization/top")
    public ResponseEntity<List<BatchOptimizationService.ResultEntry>> optimizationTopLive() {
        return ResponseEntity.ok(optimizationService.getTopResultsInMemory());
    }
}
