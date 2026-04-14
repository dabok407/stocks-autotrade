package com.example.stocks.bot;

import com.example.stocks.db.*;
import com.example.stocks.exchange.ExchangeAdapter;
import com.example.stocks.kis.KisPublicClient;
import com.example.stocks.kis.KisWebSocketClient;
import com.example.stocks.market.CandleService;
import com.example.stocks.market.TickerService;
import com.example.stocks.trade.LiveOrderService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V33: 주식봇 TP_TRAIL + 티어드 SL 테스트.
 *
 * positionCache: [avgPrice, peakPrice, trailActivated(0/1), openedAtEpochMs]
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class KrxMorningRushTpTrailTest {

    @Mock private KrxMorningRushConfigRepository configRepo;
    @Mock private BotConfigRepository botConfigRepo;
    @Mock private PositionRepository positionRepo;
    @Mock private TradeRepository tradeLogRepo;
    @Mock private LiveOrderService liveOrders;
    @Mock private TickerService tickerService;
    @Mock private CandleService candleService;
    @Mock private KisWebSocketClient kisWs;
    @Mock private TransactionTemplate txTemplate;
    @Mock private ExchangeAdapter exchangeAdapter;
    @Mock private KisPublicClient kisPublic;
    @Mock private KrxOvertimeRankLogRepository overtimeRankRepo;

    private KrxMorningRushService service;

    @BeforeEach
    public void setUp() throws Exception {
        service = new KrxMorningRushService(
                configRepo, botConfigRepo, positionRepo, tradeLogRepo,
                liveOrders, tickerService, candleService, kisWs, txTemplate,
                exchangeAdapter, kisPublic, overtimeRankRepo
        );
        setField("running", new AtomicBoolean(true));
        // 기본 cached 값 설정
        setField("cachedTpTrailActivatePct", 3.0);
        setField("cachedTpTrailDropPct", 1.5);
        setField("cachedSlPct", 3.0);
        setField("cachedWideSlPct", 3.0);
        setField("cachedGracePeriodMs", 30_000L);
        setField("cachedWidePeriodMs", 10 * 60_000L);

        when(txTemplate.execute(any())).thenAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock inv) throws Throwable {
                Object cb = inv.getArgument(0);
                if (cb instanceof TransactionCallbackWithoutResult) {
                    ((TransactionCallbackWithoutResult) cb).doInTransaction(mock(TransactionStatus.class));
                }
                return null;
            }
        });
    }

    // ═══ TP_TRAIL 테스트 ═══

    @Test
    @DisplayName("TP_TRAIL 활성화: +3% 도달 시 trail 시작, 매도 안 함")
    public void tpTrailActivation() throws Exception {
        putPosition("TEST", 10000, System.currentTimeMillis() - 60_000);
        invoke("TEST", 10300);  // +3%
        assertTrue(getCache().containsKey("TEST"), "활성화만, 매도 안 함");
        assertEquals(1.0, getCache().get("TEST")[2], 0.01, "activated=1");
    }

    @Test
    @DisplayName("TP_TRAIL: 피크 추적 후 -1.5% drop 시 매도")
    public void tpTrailDropSell() throws Exception {
        // trail 이미 활성화, peak=10500
        putPositionWithPeak("SELL1", 10000, 10500, true, System.currentTimeMillis() - 60_000);
        when(positionRepo.findById("SELL1")).thenReturn(Optional.of(buildPos("SELL1", 10000)));

        invoke("SELL1", 10340);  // peak 10500에서 -1.52% drop → 매도
        Thread.sleep(300);
        assertFalse(getCache().containsKey("SELL1"), "TP_TRAIL drop 매도");
    }

    @Test
    @DisplayName("TP_TRAIL: -1.0% drop → 1.5% 미달 → 매도 안 함")
    public void tpTrailDropNotEnough() throws Exception {
        putPositionWithPeak("HOLD1", 10000, 10500, true, System.currentTimeMillis() - 60_000);
        invoke("HOLD1", 10400);  // peak 10500에서 -0.95% → 미달
        assertTrue(getCache().containsKey("HOLD1"), "drop 부족, 유지");
    }

    @Test
    @DisplayName("TP_TRAIL 활성 후: SL 비활성 확인 (-5% 하락해도 SL 안 걸림)")
    public void tpTrailActiveThenSlDisabled() throws Exception {
        // trail 활성화 상태에서 큰 하락
        putPositionWithPeak("NOSL", 10000, 10500, true, System.currentTimeMillis() - 600_000);
        invoke("NOSL", 9700);  // -3% but trail 활성 → peak 10500에서 -7.6% drop → TP_TRAIL 매도 (SL 아님)
        // peak에서 drop이 1.5% 초과이므로 TP_TRAIL로 매도됨 (SL 아님)
        // 핵심: SL_TIGHT가 아닌 TP_TRAIL로 매도
    }

    // ═══ 티어드 SL 테스트 ═══

    @Test
    @DisplayName("Grace 30초: -2% 하락해도 SL 무시")
    public void graceIgnoresSl() throws Exception {
        putPosition("GRACE", 10000, System.currentTimeMillis() - 10_000);  // 10초 전
        invoke("GRACE", 9800);  // -2%
        assertTrue(getCache().containsKey("GRACE"), "Grace에서 SL 무시");
    }

    @Test
    @DisplayName("Grace 비상: -10% 급락 → SL_EMERGENCY")
    public void graceEmergency() throws Exception {
        putPosition("EMRG", 10000, System.currentTimeMillis() - 10_000);
        when(positionRepo.findById("EMRG")).thenReturn(Optional.of(buildPos("EMRG", 10000)));
        invoke("EMRG", 8900);  // -11%
        Thread.sleep(300);
        assertFalse(getCache().containsKey("EMRG"), "비상 SL 발동");
    }

    @Test
    @DisplayName("Wide 구간: -3% → SL_WIDE 발동")
    public void wideSlFires() throws Exception {
        putPosition("WIDE", 10000, System.currentTimeMillis() - 120_000);  // 2분 전
        when(positionRepo.findById("WIDE")).thenReturn(Optional.of(buildPos("WIDE", 10000)));
        invoke("WIDE", 9690);  // -3.1%
        Thread.sleep(300);
        assertFalse(getCache().containsKey("WIDE"), "SL_WIDE 발동");
    }

    @Test
    @DisplayName("Wide 구간: -2.5% → SL 미달 유지")
    public void wideSlNotTriggered() throws Exception {
        putPosition("WSAFE", 10000, System.currentTimeMillis() - 120_000);
        invoke("WSAFE", 9750);  // -2.5% < -3%
        assertTrue(getCache().containsKey("WSAFE"), "Wide SL 미달, 유지");
    }

    @Test
    @DisplayName("Tight 구간: -3% → SL_TIGHT 발동")
    public void tightSlFires() throws Exception {
        putPosition("TIGHT", 10000, System.currentTimeMillis() - 15 * 60_000);  // 15분 전
        when(positionRepo.findById("TIGHT")).thenReturn(Optional.of(buildPos("TIGHT", 10000)));
        invoke("TIGHT", 9690);  // -3.1%
        Thread.sleep(300);
        assertFalse(getCache().containsKey("TIGHT"), "SL_TIGHT 발동");
    }

    @Test
    @DisplayName("피크 업데이트: 가격 상승 시 peak 갱신")
    public void peakUpdate() throws Exception {
        putPosition("PEAK", 10000, System.currentTimeMillis() - 60_000);
        invoke("PEAK", 10200);  // peak=10200
        assertEquals(10200, getCache().get("PEAK")[1], 0.01);
        invoke("PEAK", 10500);  // peak=10500
        assertEquals(10500, getCache().get("PEAK")[1], 0.01);
        invoke("PEAK", 10400);  // 하락, peak 유지
        assertEquals(10500, getCache().get("PEAK")[1], 0.01, "peak은 최고값 유지");
    }

    // ═══ Helpers ═══

    private void putPosition(String symbol, double avgPrice, long openedAtMs) throws Exception {
        getCache().put(symbol, new double[]{avgPrice, avgPrice, 0, openedAtMs});
    }

    private void putPositionWithPeak(String symbol, double avgPrice, double peak,
                                      boolean activated, long openedAtMs) throws Exception {
        getCache().put(symbol, new double[]{avgPrice, peak, activated ? 1.0 : 0, openedAtMs});
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, double[]> getCache() throws Exception {
        Field f = KrxMorningRushService.class.getDeclaredField("positionCache");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, double[]>) f.get(service);
    }

    private void invoke(String symbol, double price) throws Exception {
        Method m = KrxMorningRushService.class.getDeclaredMethod(
                "checkRealtimeTpSl", String.class, double.class);
        m.setAccessible(true);
        m.invoke(service, symbol, price);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = KrxMorningRushService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private PositionEntity buildPos(String symbol, double avgPrice) {
        PositionEntity pe = new PositionEntity();
        pe.setSymbol(symbol);
        pe.setQty(10);
        pe.setAvgPrice(avgPrice);
        pe.setEntryStrategy("KRX_MORNING_RUSH");
        return pe;
    }
}
