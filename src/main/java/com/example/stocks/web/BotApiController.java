package com.example.stocks.web;

import com.example.stocks.bot.BotStatus;
import com.example.stocks.bot.PositionReconciler;
import com.example.stocks.bot.TradingBotService;
import com.example.stocks.db.BotConfigEntity;
import com.example.stocks.db.BotConfigRepository;
import com.example.stocks.db.PositionEntity;
import com.example.stocks.db.PositionRepository;
import com.example.stocks.db.StockConfigEntity;
import com.example.stocks.db.TradeEntity;
import com.example.stocks.db.TradeRepository;
import com.example.stocks.exchange.ExchangeAdapter;
import com.example.stocks.kis.KisAccount;
import com.example.stocks.kis.KisPrivateClient;
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
    // V42 (2026-05-14): 웹 UI 보유 포지션 표시 누락 수정 — MR/Opening 포지션 + STUCK broker 포지션 노출
    private final PositionRepository positionRepo;
    private final PositionReconciler positionReconciler;

    public BotApiController(TradingBotService bot, TradeRepository tradeRepo,
                            ExchangeAdapter exchangeAdapter, BotConfigRepository botConfigRepo,
                            SymbolNameService symbolNameService,
                            PositionRepository positionRepo,
                            PositionReconciler positionReconciler) {
        this.bot = bot;
        this.tradeRepo = tradeRepo;
        this.exchangeAdapter = exchangeAdapter;
        this.botConfigRepo = botConfigRepo;
        this.symbolNameService = symbolNameService;
        this.positionRepo = positionRepo;
        this.positionReconciler = positionReconciler;
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

    /**
     * V42 (2026-05-14): 모든 보유 포지션 통합 노출.
     *
     * 이전 동작: TradingBotService.getStatus().getStocks() 만 → stock_config에 등록된 종목 (005930·012330)만 표시.
     * MR/Opening 스캐너 매수 종목은 positions 테이블에 있지만 BotStatus.stocks 맵에 없어서 UI에 안 보임.
     * STUCK 종목 (broker엔 있는데 봇 DB엔 없는 잔여) 도 깜깜이.
     *
     * 신규 동작: 3가지 출처 통합 노출
     *  (a) BotStatus.stocks 의 positionOpen=true (메인 봇 stock_config 종목)
     *  (b) positions 테이블의 모든 qty>0 (MR/Opening/MainBot 모두)
     *  (c) PositionReconciler.stuckBotPositions (broker엔 있으나 봇 DB엔 없는 STUCK)
     * 심볼별 중복 제거. (a)·(b) 가 동일 심볼이면 (b) 우선 (실제 DB가 fact).
     */
    @GetMapping("/api/bot/positions")
    public Object positions() {
        Map<String, BotStatus.StockStatus> result = new LinkedHashMap<String, BotStatus.StockStatus>();

        // (a) BotStatus.stocks 중 positionOpen=true
        BotStatus status = bot.getStatus();
        Map<String, BotStatus.StockStatus> botStocks = status.getStocks();
        if (botStocks != null) {
            for (BotStatus.StockStatus ss : botStocks.values()) {
                if (ss.isPositionOpen() && ss.getSymbol() != null) {
                    result.put(ss.getSymbol(), ss);
                }
            }
        }

        // (b) positions 테이블의 모든 qty>0 (MR/Opening 포함). 동일 심볼은 fact로 덮어씀.
        for (PositionEntity p : positionRepo.findAll()) {
            if (p.getQty() <= 0 || p.getSymbol() == null) continue;
            BotStatus.StockStatus ss = result.get(p.getSymbol());
            if (ss == null) {
                ss = new BotStatus.StockStatus();
                ss.setSymbol(p.getSymbol());
            }
            ss.setPositionOpen(true);
            ss.setQty(p.getQty());
            ss.setAvgPrice(p.getAvgPrice() != null ? p.getAvgPrice().doubleValue() : 0);
            ss.setEntryStrategy(p.getEntryStrategy());
            if (ss.getMarketType() == null) ss.setMarketType("KRX");
            result.put(p.getSymbol(), ss);
        }

        // (c) STUCK_BOT_POSITION — broker 잔량 (봇 DB 미인식) 표시
        PositionReconciler.ReconcileReport rep = positionReconciler.getLastReport();
        if (rep != null) {
            for (Map.Entry<String, KisAccount> e : rep.stuckBotPositions.entrySet()) {
                String sym = e.getKey();
                if (result.containsKey(sym)) continue; // 봇 DB에 이미 있으면 중복 안 함
                KisAccount acc = e.getValue();
                BotStatus.StockStatus ss = new BotStatus.StockStatus();
                ss.setSymbol(sym);
                ss.setPositionOpen(true);
                ss.setQty(acc.getQty());
                ss.setAvgPrice(acc.getAvgPrice());
                ss.setEntryStrategy("STUCK_BROKER_ONLY"); // 봇 DB 없음 표식
                ss.setMarketType("KRX");
                result.put(sym, ss);
            }
            // (d) QTY_MISMATCH — broker qty 가 더 많으면 qty 를 broker 기준으로 덮어씀
            //     (사용자가 화면에서 broker 진실을 보도록)
            for (Map.Entry<String, int[]> e : rep.qtyMismatches.entrySet()) {
                String sym = e.getKey();
                int dbQty = e.getValue()[0];
                int brokerQty = e.getValue()[1];
                BotStatus.StockStatus ss = result.get(sym);
                if (ss != null && brokerQty > dbQty) {
                    ss.setQty(brokerQty);
                    // entryStrategy 에 "+잔량N" 표식
                    String orig = ss.getEntryStrategy() == null ? "" : ss.getEntryStrategy();
                    ss.setEntryStrategy(orig + " [broker+" + (brokerQty - dbQty) + "]");
                }
            }
        }

        // 종목명 채움
        Set<String> symbolsToResolve = new HashSet<String>();
        for (BotStatus.StockStatus ss : result.values()) {
            if (ss.getDisplayName() == null) symbolsToResolve.add(ss.getSymbol());
        }
        if (!symbolsToResolve.isEmpty()) {
            Map<String, String> nameMap = symbolNameService.getNames(symbolsToResolve);
            for (BotStatus.StockStatus ss : result.values()) {
                if (ss.getDisplayName() == null) {
                    String nm = nameMap.get(ss.getSymbol());
                    if (nm != null) ss.setDisplayName(nm);
                }
            }
        }

        return new ArrayList<BotStatus.StockStatus>(result.values());
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
