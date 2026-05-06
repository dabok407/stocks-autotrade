package com.example.stocks.exchange.kis;

import com.example.stocks.exchange.OrderResult;
import com.example.stocks.kis.KisAuth;
import com.example.stocks.kis.KisPrivateClient;
import com.example.stocks.kis.KisPublicClient;
import com.example.stocks.market.MarketType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-Fix#1 (V41 2026-05-06): 매도 주문 접수 후 실제 체결 여부 폴링 검증.
 *
 * 회귀 시나리오:
 *   - 04-16 073540 에프알텍: SL 지정가 6120 → 체결 0/10 → 봇이 EXECUTED 로 잘못 기록 → stuck 14만원 손실
 *   - 04-17 184230 SGA솔루션즈: SL_WIDE 지정가 1047 → 체결 0/46 → stuck
 *   - 05-04 047040 대우건설: SESSION_END 지정가 37150 → 체결 0/1 → stuck
 *
 * 수정 후: convertOrderResult 가 inquire-daily-ccld 폴링으로 실제 체결 확인.
 *   미체결 → PENDING(qty=0) 반환 → LiveOrderResult.isFilled()=false → 포지션 미삭제 → 재시도 가능.
 */
class KisExchangeAdapterFillVerificationTest {

    private KisAuth auth;
    private KisPublicClient publicClient;
    private KisPrivateClient privateClient;
    private KisExchangeAdapter adapter;

    private int origAttempts;
    private long origDelay;

    @BeforeEach
    void setUp() {
        auth = mock(KisAuth.class);
        publicClient = mock(KisPublicClient.class);
        privateClient = mock(KisPrivateClient.class);
        adapter = new KisExchangeAdapter(auth, publicClient, privateClient);

        // 테스트 가속 — 폴링 딜레이를 10ms 로 단축
        origAttempts = KisExchangeAdapter.FILL_POLL_ATTEMPTS;
        origDelay = KisExchangeAdapter.FILL_POLL_DELAY_MS;
        KisExchangeAdapter.FILL_POLL_ATTEMPTS = 3;
        KisExchangeAdapter.FILL_POLL_DELAY_MS = 5L;
    }

    @AfterEach
    void tearDown() {
        KisExchangeAdapter.FILL_POLL_ATTEMPTS = origAttempts;
        KisExchangeAdapter.FILL_POLL_DELAY_MS = origDelay;
    }

    private Map<String, Object> okResponse(String odno) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("rt_cd", "0");
        Map<String, Object> output = new HashMap<>();
        output.put("ODNO", odno);
        resp.put("output", output);
        return resp;
    }

    private Map<String, Object> ccldResponse(int orderedQty, int filledQty, double avgPrice, String cancelYn) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("ord_qty", String.valueOf(orderedQty));
        resp.put("tot_ccld_qty", String.valueOf(filledQty));
        resp.put("avg_prvs", String.valueOf((long) avgPrice));
        resp.put("sll_buy_dvsn_cd", "1");  // 매도
        resp.put("pdno", "073540");
        resp.put("cncl_yn", cancelYn);
        return resp;
    }

    // ===================================================================
    // CASE A: 정상 체결 — getDomesticOrderStatus 가 fully filled 반환
    // ===================================================================
    @Test
    @DisplayName("[A] 지정가 매도 → KIS 접수 OK → 폴링 1회차에 fully filled → FILLED 반환")
    void successfulFill_returnsFilled() {
        when(privateClient.placeDomesticOrder(eq("073540"), eq("SELL"), eq(10), anyLong(), eq("00")))
                .thenReturn(okResponse("0005004500"));
        when(privateClient.getDomesticOrderStatus("0005004500"))
                .thenReturn(ccldResponse(10, 10, 6120.0, "N"));

        OrderResult r = adapter.placeSellOrder("073540", MarketType.KRX, 10, 6120.0);

        assertEquals(OrderResult.Status.FILLED, r.getStatus());
        assertEquals(10, r.getQty());
        assertEquals("0005004500", r.getOrderId());
        assertTrue(r.isFilled());
    }

    // ===================================================================
    // CASE B: 미체결 — KIS 접수 OK 지만 폴링 결과 모두 0주 (실제 회귀 케이스!)
    // ===================================================================
    @Test
    @DisplayName("[B] 04-16 에프알텍 회귀: 지정가 매도 → KIS 접수 OK → 폴링 모두 0주 → PENDING(qty=0) 반환")
    void notFilled_returnsPendingZeroQty() {
        when(privateClient.placeDomesticOrder(eq("073540"), eq("SELL"), eq(10), anyLong(), eq("00")))
                .thenReturn(okResponse("0005004500"));
        // 모든 폴링 시도에서 체결 0주
        when(privateClient.getDomesticOrderStatus("0005004500"))
                .thenReturn(ccldResponse(10, 0, 0.0, "N"));

        OrderResult r = adapter.placeSellOrder("073540", MarketType.KRX, 10, 6120.0);

        // CRITICAL: PENDING + qty=0 → 호출자 isFilled()=false → 포지션 미삭제
        assertEquals(OrderResult.Status.PENDING, r.getStatus());
        assertEquals(0, r.getQty());
        assertFalse(r.isFilled(), "isFilled() must be false to prevent position deletion");
        // 폴링 시도 횟수 = FILL_POLL_ATTEMPTS
        verify(privateClient, times(KisExchangeAdapter.FILL_POLL_ATTEMPTS))
                .getDomesticOrderStatus("0005004500");
    }

    // ===================================================================
    // CASE C: 부분 체결 — 안전하게 PENDING 처리 (포지션 분할 처리는 reconciler 가)
    // ===================================================================
    @Test
    @DisplayName("[C] 부분 체결 (5/10) → PENDING 으로 안전 처리 (전부 체결 안 된 경우)")
    void partialFill_returnsPending() {
        when(privateClient.placeDomesticOrder(eq("184230"), eq("SELL"), eq(46), anyLong(), eq("00")))
                .thenReturn(okResponse("0005162200"));
        when(privateClient.getDomesticOrderStatus("0005162200"))
                .thenReturn(ccldResponse(46, 20, 1047.0, "N"));

        OrderResult r = adapter.placeSellOrder("184230", MarketType.KRX, 46, 1047.0);

        assertEquals(OrderResult.Status.PENDING, r.getStatus());
        assertEquals(0, r.getQty(), "partial 은 안전을 위해 qty=0 으로 반환");
        assertFalse(r.isFilled());
    }

    // ===================================================================
    // CASE D: 취소 — getDomesticOrderStatus 가 cncl_yn=Y 반환
    // ===================================================================
    @Test
    @DisplayName("[D] 주문 취소 (cncl_yn=Y) → CANCELLED 반환")
    void cancelled_returnsCancelled() {
        when(privateClient.placeDomesticOrder(anyString(), eq("SELL"), anyInt(), anyLong(), anyString()))
                .thenReturn(okResponse("0005000000"));
        when(privateClient.getDomesticOrderStatus("0005000000"))
                .thenReturn(ccldResponse(10, 0, 0.0, "Y"));

        OrderResult r = adapter.placeSellOrder("073540", MarketType.KRX, 10, 6120.0);

        assertEquals(OrderResult.Status.CANCELLED, r.getStatus());
        assertEquals(0, r.getQty());
        assertFalse(r.isFilled());
    }

    // ===================================================================
    // CASE E: KIS 응답 자체가 실패
    // ===================================================================
    @Test
    @DisplayName("[E] KIS rt_cd != \"0\" → FAILED, getOrderStatus 호출 안 함")
    void kisRejection_returnsFailedNoPolling() {
        Map<String, Object> rejectResp = new HashMap<>();
        rejectResp.put("rt_cd", "1");
        rejectResp.put("msg1", "주문가능금액을 초과 했습니다");
        when(privateClient.placeDomesticOrder(anyString(), anyString(), anyInt(), anyLong(), anyString()))
                .thenReturn(rejectResp);

        OrderResult r = adapter.placeBuyOrder("322310", MarketType.KRX, 1, 100000);

        assertEquals(OrderResult.Status.FAILED, r.getStatus());
        assertTrue(r.getMessage() != null && r.getMessage().contains("주문가능금액"));
        // 폴링이 호출되지 않아야 함 (불필요한 API 콜 절약)
        verify(privateClient, times(0)).getDomesticOrderStatus(any());
    }

    // ===================================================================
    // CASE F: 빈 응답
    // ===================================================================
    @Test
    @DisplayName("[F] 빈 응답 / null → FAILED")
    void emptyResponse_returnsFailed() {
        when(privateClient.placeDomesticOrder(anyString(), anyString(), anyInt(), anyLong(), anyString()))
                .thenReturn(new HashMap<>());

        OrderResult r = adapter.placeSellOrder("073540", MarketType.KRX, 10, 6120.0);

        assertEquals(OrderResult.Status.FAILED, r.getStatus());
    }

    // ===================================================================
    // CASE G: 시장가 매도 (P0-Fix#2) — ordType="01" 전달, price=0 으로 KIS 호출
    // ===================================================================
    @Test
    @DisplayName("[G] 시장가 매도 ordType=\"01\" → KIS 에 price=0 전달, 정상 체결")
    void marketOrder_passesZeroPrice() {
        when(privateClient.placeDomesticOrder(eq("073540"), eq("SELL"), eq(10), eq(0L), eq("01")))
                .thenReturn(okResponse("9999999999"));
        when(privateClient.getDomesticOrderStatus("9999999999"))
                .thenReturn(ccldResponse(10, 10, 6050.0, "N"));

        OrderResult r = adapter.placeSellOrder("073540", MarketType.KRX, 10, 6120.0, "01");

        assertEquals(OrderResult.Status.FILLED, r.getStatus());
        assertEquals(10, r.getQty());
        // KIS 에는 price=0 으로 전달됐는지 확인
        verify(privateClient, times(1)).placeDomesticOrder("073540", "SELL", 10, 0L, "01");
    }

    // ===================================================================
    // CASE H: 지정가 디폴트 — overload 없는 placeSellOrder 호출 시 ordType="00"
    // ===================================================================
    @Test
    @DisplayName("[H] 기본 placeSellOrder (overload 없음) → ordType=\"00\" 으로 KIS 호출")
    void defaultPlaceSell_usesLimitOrder() {
        when(privateClient.placeDomesticOrder(eq("073540"), eq("SELL"), eq(10), eq(6120L), eq("00")))
                .thenReturn(okResponse("8888888888"));
        when(privateClient.getDomesticOrderStatus("8888888888"))
                .thenReturn(ccldResponse(10, 10, 6120.0, "N"));

        adapter.placeSellOrder("073540", MarketType.KRX, 10, 6120.0);

        verify(privateClient, times(1)).placeDomesticOrder("073540", "SELL", 10, 6120L, "00");
    }

    // ===================================================================
    // CASE I: 첫 폴링은 0, 두번째 폴링에서 fully filled — 폴링 작동 검증
    // ===================================================================
    @Test
    @DisplayName("[I] 폴링 진행 중 체결 완료 (1회차 0 → 2회차 full) → FILLED 반환")
    void delayedFill_returnsFilledAfterPolling() {
        when(privateClient.placeDomesticOrder(anyString(), anyString(), anyInt(), anyLong(), anyString()))
                .thenReturn(okResponse("0007000000"));
        when(privateClient.getDomesticOrderStatus("0007000000"))
                .thenReturn(ccldResponse(10, 0, 0.0, "N"))
                .thenReturn(ccldResponse(10, 10, 6125.0, "N"));

        OrderResult r = adapter.placeSellOrder("073540", MarketType.KRX, 10, 6120.0);

        assertEquals(OrderResult.Status.FILLED, r.getStatus());
        assertEquals(10, r.getQty());
        assertEquals(6125.0, r.getPrice());
    }

    // ===================================================================
    // CASE J: 응답에 ODNO 가 없음 → FAILED
    // ===================================================================
    @Test
    @DisplayName("[J] rt_cd=0 인데 ODNO 없음 (이상응답) → FAILED, no polling")
    void noOrderNumber_returnsFailed() {
        Map<String, Object> respNoOdno = new HashMap<>();
        respNoOdno.put("rt_cd", "0");
        respNoOdno.put("output", new HashMap<>());  // ODNO 없음
        when(privateClient.placeDomesticOrder(anyString(), anyString(), anyInt(), anyLong(), anyString()))
                .thenReturn(respNoOdno);

        OrderResult r = adapter.placeSellOrder("073540", MarketType.KRX, 10, 6120.0);

        assertEquals(OrderResult.Status.FAILED, r.getStatus());
        verify(privateClient, times(0)).getDomesticOrderStatus(any());
    }
}
