package com.example.stocks.web;

import com.example.stocks.db.StrategyGroupEntity;
import com.example.stocks.db.StrategyGroupRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@RestController
public class StrategyGroupApiController {

    @Autowired
    private StrategyGroupRepository groupRepo;

    @GetMapping("/api/bot/groups")
    public List<Map<String, Object>> getGroups() {
        List<StrategyGroupEntity> entities = groupRepo.findAllByOrderBySortOrderAsc();
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (StrategyGroupEntity e : entities) {
            result.add(entityToMap(e));
        }
        return result;
    }

    @PostMapping("/api/bot/groups")
    public List<Map<String, Object>> saveGroups(@RequestBody List<Map<String, Object>> groups) {
        // Delete all existing groups, then re-create
        groupRepo.deleteAll();
        groupRepo.flush();

        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < groups.size(); i++) {
            Map<String, Object> g = groups.get(i);
            StrategyGroupEntity e = new StrategyGroupEntity();
            e.setGroupName(str(g.get("groupName"), "Group " + (i + 1)));
            e.setSortOrder(i);

            // Markets/symbols
            Object marketsObj = g.get("markets");
            if (marketsObj instanceof List) {
                List<?> ml = (List<?>) marketsObj;
                StringBuilder sb = new StringBuilder();
                for (Object m : ml) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(String.valueOf(m).trim());
                }
                e.setSymbolsCsv(sb.toString());
            } else if (marketsObj instanceof String) {
                e.setSymbolsCsv((String) marketsObj);
            }

            // Strategies
            Object stratsObj = g.get("strategies");
            if (stratsObj instanceof List) {
                List<?> sl = (List<?>) stratsObj;
                StringBuilder sb = new StringBuilder();
                for (Object s : sl) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(String.valueOf(s).trim());
                }
                e.setStrategyTypesCsv(sb.toString());
            } else if (stratsObj instanceof String) {
                e.setStrategyTypesCsv((String) stratsObj);
            }

            e.setCandleUnitMin(toInt(g.get("candleUnitMin"), 60));
            e.setOrderSizingMode(str(g.get("orderSizingMode"), "FIXED"));
            e.setOrderSizingValue(toBD(g.get("orderSizingValue"), BigDecimal.valueOf(100000)));
            e.setTakeProfitPct(toBD(g.get("takeProfitPct"), BigDecimal.valueOf(3.0)));
            e.setStopLossPct(toBD(g.get("stopLossPct"), BigDecimal.valueOf(2.0)));
            e.setMaxAddBuys(toInt(g.get("maxAddBuys"), 2));
            e.setStrategyLock(toBool(g.get("strategyLock"), false));
            e.setMinConfidence(toDbl(g.get("minConfidence"), 0));
            e.setTimeStopMinutes(toInt(g.get("timeStopMinutes"), 0));
            e.setStrategyIntervalsCsv(str(g.get("strategyIntervalsCsv"), ""));
            e.setEmaFilterCsv(str(g.get("emaFilterCsv"), ""));

            groupRepo.save(e);
            result.add(entityToMap(e));
        }
        return result;
    }

    private Map<String, Object> entityToMap(StrategyGroupEntity e) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", e.getId());
        m.put("groupName", e.getGroupName());
        m.put("sortOrder", e.getSortOrder());
        m.put("markets", e.getSymbolsList());
        m.put("strategies", e.getStrategyTypesList());
        m.put("candleUnitMin", e.getCandleUnitMin());
        m.put("orderSizingMode", e.getOrderSizingMode());
        m.put("orderSizingValue", e.getOrderSizingValue());
        m.put("takeProfitPct", e.getTakeProfitPct());
        m.put("stopLossPct", e.getStopLossPct());
        m.put("maxAddBuys", e.getMaxAddBuys());
        m.put("strategyLock", e.getStrategyLock());
        m.put("minConfidence", e.getMinConfidence());
        m.put("timeStopMinutes", e.getTimeStopMinutes());
        m.put("strategyIntervalsCsv", e.getStrategyIntervalsCsv());
        m.put("emaFilterCsv", e.getEmaFilterCsv());
        return m;
    }

    private static String str(Object v, String def) {
        return v != null ? String.valueOf(v) : def;
    }

    private static int toInt(Object v, int def) {
        if (v == null) return def;
        try { return ((Number) v).intValue(); } catch (Exception e) {
            try { return Integer.parseInt(v.toString()); } catch (Exception e2) { return def; }
        }
    }

    private static double toDbl(Object v, double def) {
        if (v == null) return def;
        try { return ((Number) v).doubleValue(); } catch (Exception e) {
            try { return Double.parseDouble(v.toString()); } catch (Exception e2) { return def; }
        }
    }

    private static boolean toBool(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean) return (Boolean) v;
        return Boolean.parseBoolean(v.toString());
    }

    private static BigDecimal toBD(Object v, BigDecimal def) {
        if (v == null) return def;
        if (v instanceof Number) return BigDecimal.valueOf(((Number) v).doubleValue());
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return def; }
    }
}
