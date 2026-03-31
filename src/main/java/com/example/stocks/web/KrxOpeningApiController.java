package com.example.stocks.web;

import com.example.stocks.bot.KrxOpeningScannerService;
import com.example.stocks.db.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/krx-opening")
public class KrxOpeningApiController {

    private static final Logger log = LoggerFactory.getLogger(KrxOpeningApiController.class);

    private final KrxOpeningScannerService scannerService;
    private final KrxOpeningConfigRepository configRepo;
    private final BotConfigRepository botConfigRepo;

    public KrxOpeningApiController(KrxOpeningScannerService scannerService,
                                    KrxOpeningConfigRepository configRepo,
                                    BotConfigRepository botConfigRepo) {
        this.scannerService = scannerService;
        this.configRepo = configRepo;
        this.botConfigRepo = botConfigRepo;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start() {
        KrxOpeningConfigEntity cfg = configRepo.loadOrCreate();
        cfg.setEnabled(true);
        configRepo.save(cfg);
        scannerService.start();
        return ResponseEntity.ok(buildStatus());
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stop() {
        KrxOpeningConfigEntity cfg = configRepo.loadOrCreate();
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
        KrxOpeningConfigEntity cfg = configRepo.loadOrCreate();
        if (body.containsKey("enabled")) cfg.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        if (body.containsKey("mode")) cfg.setMode(String.valueOf(body.get("mode")));
        if (body.containsKey("topN")) cfg.setTopN(toInt(body.get("topN"), 20));
        if (body.containsKey("maxPositions")) cfg.setMaxPositions(toInt(body.get("maxPositions"), 3));
        if (body.containsKey("orderSizingMode")) cfg.setOrderSizingMode(String.valueOf(body.get("orderSizingMode")));
        if (body.containsKey("orderSizingValue")) cfg.setOrderSizingValue(toBD(body.get("orderSizingValue")));
        if (body.containsKey("candleUnitMin")) cfg.setCandleUnitMin(toInt(body.get("candleUnitMin"), 5));
        if (body.containsKey("rangeStartHour")) cfg.setRangeStartHour(toInt(body.get("rangeStartHour"), 9));
        if (body.containsKey("rangeStartMin")) cfg.setRangeStartMin(toInt(body.get("rangeStartMin"), 0));
        if (body.containsKey("rangeEndHour")) cfg.setRangeEndHour(toInt(body.get("rangeEndHour"), 9));
        if (body.containsKey("rangeEndMin")) cfg.setRangeEndMin(toInt(body.get("rangeEndMin"), 15));
        if (body.containsKey("entryStartHour")) cfg.setEntryStartHour(toInt(body.get("entryStartHour"), 9));
        if (body.containsKey("entryStartMin")) cfg.setEntryStartMin(toInt(body.get("entryStartMin"), 15));
        if (body.containsKey("entryEndHour")) cfg.setEntryEndHour(toInt(body.get("entryEndHour"), 10));
        if (body.containsKey("entryEndMin")) cfg.setEntryEndMin(toInt(body.get("entryEndMin"), 30));
        if (body.containsKey("sessionEndHour")) cfg.setSessionEndHour(toInt(body.get("sessionEndHour"), 15));
        if (body.containsKey("sessionEndMin")) cfg.setSessionEndMin(toInt(body.get("sessionEndMin"), 15));
        if (body.containsKey("tpAtrMult")) cfg.setTpAtrMult(toBD(body.get("tpAtrMult")));
        if (body.containsKey("slPct")) cfg.setSlPct(toBD(body.get("slPct")));
        if (body.containsKey("trailAtrMult")) cfg.setTrailAtrMult(toBD(body.get("trailAtrMult")));
        if (body.containsKey("kospiFilterEnabled")) cfg.setKospiFilterEnabled(Boolean.TRUE.equals(body.get("kospiFilterEnabled")));
        if (body.containsKey("kospiEmaPeriod")) cfg.setKospiEmaPeriod(toInt(body.get("kospiEmaPeriod"), 20));
        if (body.containsKey("volumeMult")) cfg.setVolumeMult(toBD(body.get("volumeMult")));
        if (body.containsKey("minBodyRatio")) cfg.setMinBodyRatio(toBD(body.get("minBodyRatio")));
        if (body.containsKey("excludeSymbols")) cfg.setExcludeSymbols(String.valueOf(body.get("excludeSymbols")));
        if (body.containsKey("minPriceKrw")) cfg.setMinPriceKrw(toInt(body.get("minPriceKrw"), 1000));
        if (body.containsKey("openFailedEnabled")) cfg.setOpenFailedEnabled(Boolean.TRUE.equals(body.get("openFailedEnabled")));
        if (body.containsKey("quickTpEnabled")) cfg.setQuickTpEnabled(Boolean.TRUE.equals(body.get("quickTpEnabled")));
        if (body.containsKey("quickTpPct")) cfg.setQuickTpPct(toBD(body.get("quickTpPct")));
        if (body.containsKey("quickTpIntervalSec")) cfg.setQuickTpIntervalSec(toInt(body.get("quickTpIntervalSec"), 5));
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

    private Map<String, Object> configToMap(KrxOpeningConfigEntity cfg) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("enabled", cfg.isEnabled());
        m.put("mode", cfg.getMode());
        m.put("topN", cfg.getTopN());
        m.put("maxPositions", cfg.getMaxPositions());
        List<BotConfigEntity> bcs = botConfigRepo.findAll();
        BigDecimal globalCap = (!bcs.isEmpty() && bcs.get(0).getCapitalKrw() != null) ? bcs.get(0).getCapitalKrw() : BigDecimal.valueOf(500000);
        m.put("globalCapitalKrw", globalCap);
        m.put("orderSizingMode", cfg.getOrderSizingMode());
        m.put("orderSizingValue", cfg.getOrderSizingValue());
        m.put("candleUnitMin", cfg.getCandleUnitMin());
        m.put("rangeStartHour", cfg.getRangeStartHour()); m.put("rangeStartMin", cfg.getRangeStartMin());
        m.put("rangeEndHour", cfg.getRangeEndHour()); m.put("rangeEndMin", cfg.getRangeEndMin());
        m.put("entryStartHour", cfg.getEntryStartHour()); m.put("entryStartMin", cfg.getEntryStartMin());
        m.put("entryEndHour", cfg.getEntryEndHour()); m.put("entryEndMin", cfg.getEntryEndMin());
        m.put("sessionEndHour", cfg.getSessionEndHour()); m.put("sessionEndMin", cfg.getSessionEndMin());
        m.put("tpAtrMult", cfg.getTpAtrMult()); m.put("slPct", cfg.getSlPct()); m.put("trailAtrMult", cfg.getTrailAtrMult());
        m.put("kospiFilterEnabled", cfg.isKospiFilterEnabled()); m.put("kospiEmaPeriod", cfg.getKospiEmaPeriod());
        m.put("volumeMult", cfg.getVolumeMult()); m.put("minBodyRatio", cfg.getMinBodyRatio());
        m.put("excludeSymbols", cfg.getExcludeSymbols()); m.put("minPriceKrw", cfg.getMinPriceKrw());
        m.put("openFailedEnabled", cfg.isOpenFailedEnabled());
        m.put("quickTpEnabled", cfg.isQuickTpEnabled()); m.put("quickTpPct", cfg.getQuickTpPct()); m.put("quickTpIntervalSec", cfg.getQuickTpIntervalSec());
        return m;
    }

    private static int toInt(Object v, int def) { if (v == null) return def; try { return ((Number) v).intValue(); } catch (Exception e) { try { return Integer.parseInt(v.toString()); } catch (Exception e2) { return def; } } }
    private static BigDecimal toBD(Object v) { if (v == null) return BigDecimal.ZERO; if (v instanceof Number) return BigDecimal.valueOf(((Number) v).doubleValue()); try { return new BigDecimal(v.toString()); } catch (Exception e) { return BigDecimal.ZERO; } }
}
