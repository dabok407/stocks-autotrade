package com.example.stocks.bot;

import com.example.stocks.db.TradeEntity;
import com.example.stocks.db.TradeRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.List;

/**
 * KRX 스캐너(MorningRush, Opening Scanner)가 공유하는 단일 매수 throttle.
 *
 * 같은 종목에 대해 두 가지 제한을 동시에 적용:
 *  - 1시간 내 최대 2회 매수
 *  - 가장 최근 매수로부터 20분 쿨다운
 *
 * 두 제한을 모두 만족해야 매수 가능. 모든 스캐너가 단일 인스턴스를 사용하므로
 * "모닝러쉬가 매수한 종목을 오프닝이 또 매수"하는 사고가 차단된다.
 *
 * 코인봇 SharedTradeThrottle(upbit-autotrade-java8) 포팅 (2026-04-18).
 * 운영 사고 대응:
 *  - 2026-04-17 010170 반복 매수/매도(SPLIT_1ST 38회 중복): 같은 날 재엔트리 차단은
 *    tradedSymbols(daily set)가 담당하지만, 앱 재시작 후 재엔트리/Dual-scanner 공격 케이스는
 *    미커버. 이 throttle이 백업 방어선.
 */
@Service
public class KrxSharedTradeThrottle {

    private static final Logger log = LoggerFactory.getLogger(KrxSharedTradeThrottle.class);

    private final HourlyTradeThrottle delegate;
    private final TradeRepository tradeRepo;

    public KrxSharedTradeThrottle(TradeRepository tradeRepo) {
        this.delegate = new HourlyTradeThrottle(2, 20);
        this.tradeRepo = tradeRepo;
    }

    KrxSharedTradeThrottle() {
        this.delegate = new HourlyTradeThrottle(2, 20);
        this.tradeRepo = null;
    }

    @PostConstruct
    public void restoreFromDb() {
        if (tradeRepo == null) return;
        try {
            long oneHourAgo = System.currentTimeMillis() - 3600_000L;
            List<TradeEntity> recentBuys = tradeRepo.findByActionAndTsEpochMsGreaterThanEqual("BUY", oneHourAgo);
            int restored = 0;
            for (TradeEntity t : recentBuys) {
                delegate.recordBuyAt(t.getSymbol(), t.getTsEpochMs());
                restored++;
            }
            if (restored > 0) {
                log.info("[KrxSharedThrottle] 서버 재시작 throttle 복원: {}건 (최근 1시간 BUY)", restored);
            }
        } catch (Exception e) {
            log.warn("[KrxSharedThrottle] throttle 복원 실패 (무시): {}", e.getMessage());
        }
    }

    public boolean canBuy(String symbol) {
        return delegate.canBuy(symbol);
    }

    public void recordBuy(String symbol) {
        delegate.recordBuy(symbol);
    }

    /**
     * 원자적 매수 권한 확보 (canBuy + recordBuy를 한 번에).
     * synchronized로 여러 thread가 동시에 canBuy→recordBuy race window 통과하는 것을 차단.
     */
    public synchronized boolean tryClaim(String symbol) {
        if (!delegate.canBuy(symbol)) return false;
        delegate.recordBuy(symbol);
        return true;
    }

    /** 매수 실패 시 권한 반환 (주문 거부 시 catch에서 호출). */
    public synchronized void releaseClaim(String symbol) {
        delegate.removeLastBuy(symbol);
    }

    public long remainingWaitMs(String symbol) {
        return delegate.remainingWaitMs(symbol);
    }

    HourlyTradeThrottle getDelegate() {
        return delegate;
    }
}
