package com.example.stocks.web;

import com.example.stocks.bot.BotStatus;
import com.example.stocks.bot.MarketCalendar;
import com.example.stocks.bot.TradingBotService;
import com.example.stocks.db.TradeEntity;
import com.example.stocks.db.TradeRepository;
import com.example.stocks.market.MarketType;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class DashboardApiController {

    private final TradingBotService bot;
    private final TradeRepository tradeRepo;
    private final com.example.stocks.db.BotConfigRepository botConfigRepo;

    public DashboardApiController(TradingBotService bot, TradeRepository tradeRepo,
                                  com.example.stocks.db.BotConfigRepository botConfigRepo) {
        this.bot = bot;
        this.tradeRepo = tradeRepo;
        this.botConfigRepo = botConfigRepo;
    }

    /**
     * Dashboard summary: PnL, ROI, win rate, trade count.
     */
    @GetMapping("/api/dashboard/summary")
    public Map<String, Object> summary() {
        BotStatus status = bot.getStatus();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("totalPnlKrw", status.getTotalPnlKrw());
        m.put("roiPercent", status.getRoiPercent());
        m.put("winRate", status.getWinRate());
        m.put("wins", status.getWins());
        m.put("totalTrades", status.getTotalTrades());
        m.put("realizedPnlKrw", status.getRealizedPnlKrw());
        m.put("unrealizedPnlKrw", status.getUnrealizedPnlKrw());
        // 잔고 정보 (KIS API 또는 설정 기반)
        m.put("configured", true);
        java.util.List<com.example.stocks.db.BotConfigEntity> bcs = botConfigRepo.findAll();
        double capital = (!bcs.isEmpty() && bcs.get(0).getCapitalKrw() != null)
                ? bcs.get(0).getCapitalKrw().doubleValue() : 500000;
        double used = status.getUsedCapitalKrw();
        m.put("availableKrw", capital - used);
        m.put("lockedKrw", used);
        m.put("asOf", java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul")).toString());
        return m;
    }

    /**
     * Trade log with pagination.
     */
    @GetMapping("/api/dashboard/trades")
    public Object trades(
            @org.springframework.web.bind.annotation.RequestParam(value = "page", defaultValue = "1") int page,
            @org.springframework.web.bind.annotation.RequestParam(value = "size", defaultValue = "50") int size
    ) {
        int p = Math.max(0, page - 1);
        int s = Math.max(1, Math.min(500, size));
        org.springframework.data.domain.Page<TradeEntity> result =
                tradeRepo.findAllByOrderByTsEpochMsDesc(org.springframework.data.domain.PageRequest.of(p, s));

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("page", page);
        out.put("size", s);
        out.put("total", result.getTotalElements());
        out.put("items", result.getContent());
        return out;
    }

    /**
     * Capital info (configured capital, used, available).
     */
    @GetMapping("/api/dashboard/capital")
    public Map<String, Object> capital() {
        BotStatus status = bot.getStatus();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("capitalKrw", status.getCapitalKrw());
        m.put("usedCapitalKrw", status.getUsedCapitalKrw());
        m.put("availableCapitalKrw", status.getAvailableCapitalKrw());
        double usagePct = status.getCapitalKrw() > 0
                ? status.getUsedCapitalKrw() / status.getCapitalKrw() * 100.0
                : 0;
        m.put("usagePercent", usagePct);
        return m;
    }

    /**
     * Decision guard logs.
     */
    @GetMapping("/api/dashboard/decision-logs")
    public Object decisionLogs() {
        return bot.getRecentDecisionLogs(200);
    }

    /**
     * Market session status for KRX and NYSE.
     */
    @GetMapping("/api/dashboard/market-sessions")
    public Map<String, Object> marketSessions() {
        ZonedDateTime now = ZonedDateTime.now();
        Map<String, Object> m = new LinkedHashMap<String, Object>();

        // KRX
        boolean krxOpen = MarketCalendar.isMarketOpen(now, MarketType.KRX);
        long krxMinutes = MarketCalendar.minutesToClose(now, MarketType.KRX);
        Map<String, Object> krx = new LinkedHashMap<String, Object>();
        krx.put("status", krxOpen ? "OPEN" : "CLOSED");
        krx.put("minutesToClose", krxMinutes);
        m.put("krx", krx);

        // NYSE
        boolean nyseOpen = MarketCalendar.isMarketOpen(now, MarketType.NYSE);
        long nyseMinutes = MarketCalendar.minutesToClose(now, MarketType.NYSE);
        Map<String, Object> nyse = new LinkedHashMap<String, Object>();
        nyse.put("status", nyseOpen ? "OPEN" : "CLOSED");
        nyse.put("minutesToClose", nyseMinutes);
        m.put("nyse", nyse);

        return m;
    }
}
