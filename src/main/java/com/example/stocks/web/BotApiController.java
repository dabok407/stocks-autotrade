package com.example.stocks.web;

import com.example.stocks.bot.BotStatus;
import com.example.stocks.bot.TradingBotService;
import com.example.stocks.db.BotConfigEntity;
import com.example.stocks.db.BotConfigRepository;
import com.example.stocks.db.StockConfigEntity;
import com.example.stocks.db.TradeEntity;
import com.example.stocks.db.TradeRepository;
import com.example.stocks.exchange.ExchangeAdapter;
import com.example.stocks.market.MarketType;
import com.example.stocks.trade.SymbolNameService;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Bot control + status API for the stock trading bot.
 */
@RestController
public class BotApiController {

    private final TradingBotService bot;
    private final TradeRepository tradeRepo;
    private final ExchangeAdapter exchangeAdapter;
    private final BotConfigRepository botConfigRepo;
    private final SymbolNameService symbolNameService;

    public BotApiController(TradingBotService bot, TradeRepository tradeRepo,
                            ExchangeAdapter exchangeAdapter, BotConfigRepository botConfigRepo,
                            SymbolNameService symbolNameService) {
        this.bot = bot;
        this.tradeRepo = tradeRepo;
        this.exchangeAdapter = exchangeAdapter;
        this.botConfigRepo = botConfigRepo;
        this.symbolNameService = symbolNameService;
    }

    @PostMapping("/api/bot/start")
    public BotStatus start() {
        bot.start();
        return bot.getStatus();
    }

    @PostMapping("/api/bot/stop")
    public BotStatus stop() {
        bot.stop();
        return bot.getStatus();
    }

    @GetMapping("/api/bot/status")
    public BotStatus status() {
        return bot.getStatus();
    }

    /** Auto-Start toggle: auto-start bot/scanners on server restart */
    @PostMapping("/api/bot/auto-start")
    public Map<String, Object> toggleAutoStart(@RequestBody Map<String, Object> body) {
        Boolean enabled = (Boolean) body.get("enabled");
        if (enabled == null) {
            throw new IllegalArgumentException("enabled parameter is required.");
        }
        List<BotConfigEntity> configs = botConfigRepo.findAll();
        if (configs.isEmpty()) {
            throw new IllegalStateException("bot_config not found");
        }
        BotConfigEntity bc = configs.get(0);
        bc.setAutoStartEnabled(enabled);
        botConfigRepo.save(bc);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", true);
        result.put("autoStartEnabled", enabled);
        return result;
    }

    @GetMapping("/api/bot/config")
    public BotStatus config() {
        return bot.getStatus();
    }

    @PostMapping("/api/bot/config")
    public BotStatus updateConfig(@RequestBody ConfigRequest req) {
        validateConfigValues(req.capitalKrw, req.takeProfitPct, req.stopLossPct, req.maxAddBuysGlobal, req.minConfidence);
        bot.updateBotConfig(req.mode, req.candleUnitMin, req.capitalKrw,
                req.strategyType, req.strategies,
                req.maxAddBuysGlobal,
                req.takeProfitPct, req.stopLossPct,
                req.strategyLock,
                req.minConfidence, req.timeStopMinutes);
        // 미장 기본설정 저장
        if (req.usMode != null || req.usCapitalKrw != null) {
            bot.updateUsConfig(req.usMode, req.usCapitalKrw);
        }
        return bot.getStatus();
    }

    @GetMapping("/api/bot/decisions")
    public Object decisions() {
        return bot.getRecentDecisionLogs(200);
    }

    @GetMapping("/api/bot/positions")
    public Object positions() {
        BotStatus status = bot.getStatus();
        Map<String, BotStatus.StockStatus> stocks = status.getStocks();
        List<BotStatus.StockStatus> openPositions = new ArrayList<BotStatus.StockStatus>();
        if (stocks != null) {
            for (BotStatus.StockStatus ss : stocks.values()) {
                if (ss.isPositionOpen()) {
                    openPositions.add(ss);
                }
            }
        }
        return openPositions;
    }

    /**
     * Trades with pagination.
     */
    @GetMapping("/api/bot/trades")
    public Object trades(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size
    ) {
        if (page == null || size == null) {
            List<TradeEntity> recent = bot.recentTrades();
            enrichSymbolNames(recent);
            return recent;
        }
        int p = Math.max(0, page - 1); // API uses 1-based, Spring uses 0-based
        int s = Math.max(1, Math.min(500, size));
        Page<TradeEntity> result = tradeRepo.findAllByOrderByTsEpochMsDesc(PageRequest.of(p, s));
        List<TradeEntity> rows = result.getContent();
        enrichSymbolNames(rows);
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("content", rows);
        resp.put("totalPages", result.getTotalPages());
        resp.put("totalElements", result.getTotalElements());
        resp.put("page", page);
        resp.put("size", s);
        return resp;
    }

    /** 거래내역 심볼명 채움. SymbolNameService 위임 (캐시/DB 소스, KIS 백필은 비동기). */
    private void enrichSymbolNames(List<TradeEntity> trades) {
        if (trades == null || trades.isEmpty()) return;
        Set<String> symbols = new HashSet<String>();
        for (TradeEntity t : trades) {
            if (t.getSymbol() != null) symbols.add(t.getSymbol());
        }
        if (symbols.isEmpty()) return;

        Map<String, String> nameMap = symbolNameService.getNames(symbols);
        for (TradeEntity t : trades) {
            String name = nameMap.get(t.getSymbol());
            if (name != null) t.setSymbolName(name);
        }
    }

    @GetMapping("/api/bot/stocks")
    public List<StockConfigEntity> getStocks() {
        return bot.getStockConfigs();
    }

    @PostMapping("/api/bot/stocks")
    public BotStatus updateStocks(@RequestBody List<StockConfigEntity> stocks) {
        bot.updateStockConfigs(stocks);
        return bot.getStatus();
    }

    /**
     * 거래대금 TOP N 종목 조회 (심볼 + 종목명).
     * 보유 종목(삼성전자 005930, 현대모비스 012330)은 제외 표시.
     */
    @GetMapping("/api/bot/volume-ranking")
    public List<Map<String, Object>> volumeRanking(
            @RequestParam(value = "topN", required = false, defaultValue = "100") int topN,
            @RequestParam(value = "marketType", required = false, defaultValue = "KRX") String marketTypeStr
    ) {
        MarketType mt;
        try {
            mt = MarketType.valueOf(marketTypeStr);
        } catch (IllegalArgumentException e) {
            mt = MarketType.KRX;
        }

        List<Map<String, String>> raw = exchangeAdapter.getTopSymbolsWithName(
                Math.max(1, Math.min(200, topN)), mt);

        // 보유 종목 제외 대상
        Set<String> ownedSymbols = new HashSet<String>(Arrays.asList("005930", "012330"));

        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, String> item : raw) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("symbol", item.get("symbol"));
            entry.put("name", item.get("name"));
            entry.put("excluded", ownedSymbols.contains(item.get("symbol")));
            result.add(entry);
        }
        return result;
    }

    private void validateConfigValues(Double capital, Double tp, Double sl, Integer maxAdds, Double minConf) {
        if (capital != null && (capital < 0 || capital > 1_000_000_000)) {
            throw new IllegalArgumentException("Capital must be between 0 and 1,000,000,000.");
        }
        if (tp != null && (tp < 0 || tp > 100)) {
            throw new IllegalArgumentException("Take Profit must be between 0 and 100%.");
        }
        if (sl != null && (sl < 0 || sl > 100)) {
            throw new IllegalArgumentException("Stop Loss must be between 0 and 100%.");
        }
        if (maxAdds != null && (maxAdds < 0 || maxAdds > 20)) {
            throw new IllegalArgumentException("Max Add Buys must be between 0 and 20.");
        }
        if (minConf != null && (minConf < 0 || minConf > 10)) {
            throw new IllegalArgumentException("Min Confidence must be between 0 and 10.");
        }
    }

    public static class ConfigRequest {
        public String mode;
        public Integer candleUnitMin;
        public Double capitalKrw;
        public String strategyType;
        public List<String> strategies;
        public Double takeProfitPct;
        public Double stopLossPct;
        public Integer maxAddBuysGlobal;
        public Boolean strategyLock;
        public Double minConfidence;
        public Integer timeStopMinutes;
        public String marketType;
        public String usMode;
        public Double usCapitalKrw;
    }
}
