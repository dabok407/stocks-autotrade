package com.example.stocks.bot;

import com.example.stocks.db.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 서버 재시작 시 auto_start_enabled가 true이면
 * 봇과 각 스캐너(enabled=true인 것)를 자동으로 시작합니다.
 */
@Component
public class AutoStartRunner {

    private static final Logger log = LoggerFactory.getLogger(AutoStartRunner.class);

    private final BotConfigRepository botConfigRepo;
    private final KrxOpeningConfigRepository krxOpeningRepo;
    private final KrxAlldayConfigRepository krxAlldayRepo;
    private final KrxMorningRushConfigRepository krxMorningRushRepo;
    private final NyseOpeningConfigRepository nyseOpeningRepo;
    private final NyseAlldayConfigRepository nyseAlldayRepo;
    private final NyseMorningRushConfigRepository nyseMorningRushRepo;
    private final TradingBotService tradingBotService;
    private final KrxOpeningScannerService krxOpeningScanner;
    private final KrxAlldayScannerService krxAlldayScanner;
    private final KrxMorningRushService krxMorningRushScanner;
    private final NyseOpeningScannerService nyseOpeningScanner;
    private final NyseAlldayScannerService nyseAlldayScanner;
    private final NyseMorningRushService nyseMorningRushScanner;

    public AutoStartRunner(BotConfigRepository botConfigRepo,
                           KrxOpeningConfigRepository krxOpeningRepo,
                           KrxAlldayConfigRepository krxAlldayRepo,
                           KrxMorningRushConfigRepository krxMorningRushRepo,
                           NyseOpeningConfigRepository nyseOpeningRepo,
                           NyseAlldayConfigRepository nyseAlldayRepo,
                           NyseMorningRushConfigRepository nyseMorningRushRepo,
                           TradingBotService tradingBotService,
                           KrxOpeningScannerService krxOpeningScanner,
                           KrxAlldayScannerService krxAlldayScanner,
                           KrxMorningRushService krxMorningRushScanner,
                           NyseOpeningScannerService nyseOpeningScanner,
                           NyseAlldayScannerService nyseAlldayScanner,
                           NyseMorningRushService nyseMorningRushScanner) {
        this.botConfigRepo = botConfigRepo;
        this.krxOpeningRepo = krxOpeningRepo;
        this.krxAlldayRepo = krxAlldayRepo;
        this.krxMorningRushRepo = krxMorningRushRepo;
        this.nyseOpeningRepo = nyseOpeningRepo;
        this.nyseAlldayRepo = nyseAlldayRepo;
        this.nyseMorningRushRepo = nyseMorningRushRepo;
        this.tradingBotService = tradingBotService;
        this.krxOpeningScanner = krxOpeningScanner;
        this.krxAlldayScanner = krxAlldayScanner;
        this.krxMorningRushScanner = krxMorningRushScanner;
        this.nyseOpeningScanner = nyseOpeningScanner;
        this.nyseAlldayScanner = nyseAlldayScanner;
        this.nyseMorningRushScanner = nyseMorningRushScanner;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            BotConfigEntity bc = botConfigRepo.findAll().stream().findFirst().orElse(null);
            if (bc == null || !bc.isAutoStartEnabled()) {
                log.info("[AutoStart] auto_start_enabled=false, skipping auto-start");
                return;
            }

            log.info("[AutoStart] auto_start_enabled=true, starting...");

            // 1. Main bot
            if (!tradingBotService.isRunning()) {
                boolean started = tradingBotService.start();
                log.info("[AutoStart] Main bot start: {}", started ? "OK" : "FAILED");
            }

            // 2. KRX Opening Scanner
            KrxOpeningConfigEntity krxOpenCfg = krxOpeningRepo.findById(1).orElse(null);
            if (krxOpenCfg != null && krxOpenCfg.isEnabled() && !krxOpeningScanner.isRunning()) {
                boolean started = krxOpeningScanner.start();
                log.info("[AutoStart] KRX Opening scanner start: {}", started ? "OK" : "FAILED");
            }

            // 3. KRX AllDay Scanner
            KrxAlldayConfigEntity krxAlldayCfg = krxAlldayRepo.findById(1).orElse(null);
            if (krxAlldayCfg != null && krxAlldayCfg.isEnabled() && !krxAlldayScanner.isRunning()) {
                boolean started = krxAlldayScanner.start();
                log.info("[AutoStart] KRX AllDay scanner start: {}", started ? "OK" : "FAILED");
            }

            // 4. KRX Morning Rush Scanner
            KrxMorningRushConfigEntity krxMrCfg = krxMorningRushRepo.findById(1).orElse(null);
            if (krxMrCfg != null && krxMrCfg.isEnabled() && !krxMorningRushScanner.isRunning()) {
                boolean started = krxMorningRushScanner.start();
                log.info("[AutoStart] KRX Morning Rush scanner start: {}", started ? "OK" : "FAILED");
            }

            // 5. NYSE Opening Scanner
            NyseOpeningConfigEntity nyseOpenCfg = nyseOpeningRepo.findById(1).orElse(null);
            if (nyseOpenCfg != null && nyseOpenCfg.isEnabled() && !nyseOpeningScanner.isRunning()) {
                boolean started = nyseOpeningScanner.start();
                log.info("[AutoStart] NYSE Opening scanner start: {}", started ? "OK" : "FAILED");
            }

            // 6. NYSE AllDay Scanner
            NyseAlldayConfigEntity nyseAlldayCfg = nyseAlldayRepo.findById(1).orElse(null);
            if (nyseAlldayCfg != null && nyseAlldayCfg.isEnabled() && !nyseAlldayScanner.isRunning()) {
                boolean started = nyseAlldayScanner.start();
                log.info("[AutoStart] NYSE AllDay scanner start: {}", started ? "OK" : "FAILED");
            }

            // 7. NYSE Morning Rush Scanner
            NyseMorningRushConfigEntity nyseMrCfg = nyseMorningRushRepo.findById(1).orElse(null);
            if (nyseMrCfg != null && nyseMrCfg.isEnabled() && !nyseMorningRushScanner.isRunning()) {
                boolean started = nyseMorningRushScanner.start();
                log.info("[AutoStart] NYSE Morning Rush scanner start: {}", started ? "OK" : "FAILED");
            }

            log.info("[AutoStart] Auto-start complete");
        } catch (Exception e) {
            log.error("[AutoStart] Error during auto-start", e);
        }
    }
}
