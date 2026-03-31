package com.example.stocks.web;

import com.example.stocks.bot.NyseMorningRushService;
import com.example.stocks.db.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/nyse-morning-rush")
public class NyseMorningRushApiController {

    private static final Logger log = LoggerFactory.getLogger(NyseMorningRushApiController.class);

    private final NyseMorningRushService scannerService;
    private final NyseMorningRushConfigRepository configRepo;
    private final BotConfigRepository botConfigRepo;

    public NyseMorningRushApiController(NyseMorningRushService scannerService,
                                         NyseMorningRushConfigRepository configRepo,
                                         BotConfigRepository botConfigRepo) {
        this.scannerService = scannerService;
        this.configRepo = configRepo;
        this.botConfigRepo = botConfigRepo;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start() {
        NyseMorningRushConfigEntity cfg = configRepo.loadOrCreate();
        cfg.setEnabled(true);
        configRepo.save(cfg);
        scannerService.start();
        return ResponseEntity.ok(buildStatus());
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stop() {
        NyseMorningRushConfigEntity cfg = configRepo.loadOrCreate();
        cfg.setEnabled(false);
        configRepo.save(cfg);
        scannerService.stop();
        return ResponseEntity.ok(buildStatus());
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(buildStatus());
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        return ResponseEntity.ok(configToMap(configRepo.loadOrCreate()));
    }

    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody Map<String, Object> body) {
        NyseMorningRushConfigEntity cfg = configRepo.loadOrCreate();
        if (body.containsKey("enabled")) cfg.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        if (body.containsKey("mode")) cfg.setMode(String.valueOf(body.get("mode")));
        if (body.containsKey("topN")) cfg.setTopN(toInt(body.get("topN"), 20));
        if (body.containsKey("maxPositions")) cfg.setMaxPositions(toInt(body.get("maxPositions"), 2));
        if (body.containsKey("orderSizingMode")) cfg.setOrderSizingMode(String.valueOf(body.get("orderSizingMode")));
        if (body.containsKey("orderSizingValue")) cfg.setOrderSizingValue(toBD(body.get("orderSizingValue")));
        if (body.containsKey("gapThresholdPct")) cfg.setGapThresholdPct(toBD(body.get("gapThresholdPct")));
        if (body.containsKey("pmHighBreakoutPct")) cfg.setPmHighBreakoutPct(toBD(body.get("pmHighBreakoutPct")));
        if (body.containsKey("volumeMult")) cfg.setVolumeMult(toBD(body.get("volumeMult")));
        if (body.containsKey("confirmCount")) cfg.setConfirmCount(toInt(body.get("confirmCount"), 2));
        if (body.containsKey("checkIntervalSec")) cfg.setCheckIntervalSec(toInt(body.get("checkIntervalSec"), 5));
        if (body.containsKey("tpPct")) cfg.setTpPct(toBD(body.get("tpPct")));
        if (body.containsKey("slPct")) cfg.setSlPct(toBD(body.get("slPct")));
        if (body.containsKey("entryDelaySec")) cfg.setEntryDelaySec(toInt(body.get("entryDelaySec"), 30));
        if (body.containsKey("sessionEndHour")) cfg.setSessionEndHour(toInt(body.get("sessionEndHour"), 10));
        if (body.containsKey("sessionEndMin")) cfg.setSessionEndMin(toInt(body.get("sessionEndMin"), 30));
        if (body.containsKey("timeStopMin")) cfg.setTimeStopMin(toInt(body.get("timeStopMin"), 20));
        if (body.containsKey("minPriceUsd")) cfg.setMinPriceUsd(toInt(body.get("minPriceUsd"), 5));
        if (body.containsKey("excludeSymbols")) cfg.setExcludeSymbols(String.valueOf(body.get("excludeSymbols")));
        configRepo.save(cfg);
        return ResponseEntity.ok(configToMap(cfg));
    }

    @GetMapping("/decisions")
    public ResponseEntity<List<Map<String, Object>>> decisions(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(scannerService.getRecentDecisions(Math.min(limit, 200)));
    }

    private Map<String, Object> buildStatus() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("running", scannerService.isRunning());
        m.put("status", scannerService.getStatusText());
        m.put("scanCount", scannerService.getScanCount());
        m.put("activePositions", scannerService.getActivePositions());
        m.put("lastScannedSymbols", scannerService.getLastScannedSymbols());
        m.put("lastTickEpochMs", scannerService.getLastTickEpochMs());
        m.put("config", configToMap(configRepo.loadOrCreate()));
        return m;
    }

    private Map<String, Object> configToMap(NyseMorningRushConfigEntity cfg) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("enabled", cfg.isEnabled());
        m.put("mode", cfg.getMode());
        m.put("topN", cfg.getTopN());
        m.put("maxPositions", cfg.getMaxPositions());
        List<BotConfigEntity> bcs = botConfigRepo.findAll();
        BigDecimal globalCap = (!bcs.isEmpty() && bcs.get(0).getUsCapitalKrw() != null) ? bcs.get(0).getUsCapitalKrw() : BigDecimal.valueOf(500000);
        m.put("globalCapitalKrw", globalCap);
        m.put("orderSizingMode", cfg.getOrderSizingMode());
        m.put("orderSizingValue", cfg.getOrderSizingValue());
        m.put("gapThresholdPct", cfg.getGapThresholdPct());
        m.put("pmHighBreakoutPct", cfg.getPmHighBreakoutPct());
        m.put("volumeMult", cfg.getVolumeMult());
        m.put("confirmCount", cfg.getConfirmCount());
        m.put("checkIntervalSec", cfg.getCheckIntervalSec());
        m.put("tpPct", cfg.getTpPct());
        m.put("slPct", cfg.getSlPct());
        m.put("entryDelaySec", cfg.getEntryDelaySec());
        m.put("sessionEndHour", cfg.getSessionEndHour());
        m.put("sessionEndMin", cfg.getSessionEndMin());
        m.put("timeStopMin", cfg.getTimeStopMin());
        m.put("minPriceUsd", cfg.getMinPriceUsd());
        m.put("excludeSymbols", cfg.getExcludeSymbols());
        return m;
    }

    private static int toInt(Object v, int def) { if (v == null) return def; try { return ((Number) v).intValue(); } catch (Exception e) { try { return Integer.parseInt(v.toString()); } catch (Exception e2) { return def; } } }
    private static BigDecimal toBD(Object v) { if (v == null) return BigDecimal.ZERO; if (v instanceof Number) return BigDecimal.valueOf(((Number) v).doubleValue()); try { return new BigDecimal(v.toString()); } catch (Exception e) { return BigDecimal.ZERO; } }
}
