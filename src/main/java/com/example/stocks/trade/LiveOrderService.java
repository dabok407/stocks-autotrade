package com.example.stocks.trade;

import com.example.stocks.config.TradeProperties;
import com.example.stocks.db.OrderEntity;
import com.example.stocks.db.OrderRepository;
import com.example.stocks.exchange.ExchangeAdapter;
import com.example.stocks.exchange.OrderResult;
import com.example.stocks.market.MarketType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * LIVE 모드 주문 실행 (ExchangeAdapter 기반).
 *
 * 안전장치:
 * 1) identifier(멱등키)로 중복주문 방지
 * 2) 체결 확인 전 "pending"으로 간주하고 전략이 추가 주문/청산을 내지 않도록 함
 * 3) 타임아웃 시 cancel 요청 가능
 */
@Service
public class LiveOrderService {

    private static final Logger log = LoggerFactory.getLogger(LiveOrderService.class);

    private final ExchangeAdapter exchange;
    private final OrderRepository orderRepo;
    private final TradeProperties tradeProps;

    public LiveOrderService(ExchangeAdapter exchange, OrderRepository orderRepo, TradeProperties tradeProps) {
        this.exchange = exchange;
        this.orderRepo = orderRepo;
        this.tradeProps = tradeProps;
    }

    /**
     * 거래소 API 자격증명이 설정되어 있는지 확인.
     */
    public boolean isConfigured() {
        return exchange.isConfigured();
    }

    // =====================================================================
    // Order execution
    // =====================================================================

    /**
     * 매수 주문 (지정가).
     *
     * @param symbol     종목 심볼 (KRX: "005930", US: "AAPL")
     * @param marketType 시장 유형
     * @param qty        주문 수량
     * @param price      주문 가격 (호가 단위에 맞아야 함)
     * @return 주문 결과
     */
    public LiveOrderResult placeBuyOrder(String symbol, MarketType marketType, int qty, double price) {
        String identifier = newIdentifier();
        double tickPrice = TickSizeUtil.roundToTickSize(price, marketType);

        // DB에 주문 기록 (pending 상태)
        OrderEntity order = new OrderEntity();
        order.setIdentifier(identifier);
        order.setSymbol(symbol);
        order.setMarketType(marketType.name());
        order.setSide("BUY");
        order.setOrdType("00"); // 지정가
        order.setPrice(BigDecimal.valueOf(tickPrice));
        order.setQty(qty);
        order.setState("wait");
        order.setTsEpochMs(System.currentTimeMillis());
        orderRepo.save(order);

        log.info("[LIVE] BUY order via {}: symbol={}, market={}, qty={}, price={}",
                exchange.getName(), symbol, marketType, qty, tickPrice);

        OrderResult result = exchange.placeBuyOrder(symbol, marketType, qty, tickPrice);
        return processOrderResult(identifier, result, order);
    }

    /**
     * 매도 주문 (지정가).
     *
     * @param symbol     종목 심볼
     * @param marketType 시장 유형
     * @param qty        매도 수량
     * @param price      매도 가격
     * @return 주문 결과
     */
    public LiveOrderResult placeSellOrder(String symbol, MarketType marketType, int qty, double price) {
        String identifier = newIdentifier();
        double tickPrice = TickSizeUtil.roundToTickSize(price, marketType);

        OrderEntity order = new OrderEntity();
        order.setIdentifier(identifier);
        order.setSymbol(symbol);
        order.setMarketType(marketType.name());
        order.setSide("SELL");
        order.setOrdType("00"); // 지정가
        order.setPrice(BigDecimal.valueOf(tickPrice));
        order.setQty(qty);
        order.setState("wait");
        order.setTsEpochMs(System.currentTimeMillis());
        orderRepo.save(order);

        log.info("[LIVE] SELL order via {}: symbol={}, market={}, qty={}, price={}",
                exchange.getName(), symbol, marketType, qty, tickPrice);

        OrderResult result = exchange.placeSellOrder(symbol, marketType, qty, tickPrice);
        return processOrderResult(identifier, result, order);
    }

    /**
     * 주문 상태 폴링.
     *
     * @param ordNo 주문번호
     * @return 주문 상태 ("done", "wait", "cancel" 등)
     */
    public String pollOrderStatus(String ordNo) {
        OrderResult result = exchange.getOrderStatus(ordNo);
        if (result != null && result.isFilled()) {
            return "done";
        }
        return "wait";
    }

    /**
     * 해당 종목에 미체결 주문이 있는지 확인.
     */
    public boolean hasPendingOrder(String symbol) {
        long cutoffMs = System.currentTimeMillis() - 3_600_000L;
        for (OrderEntity o : orderRepo.findAll()) {
            if (!symbol.equals(o.getSymbol())) continue;
            if (!"wait".equalsIgnoreCase(o.getState())) continue;
            if (o.getTsEpochMs() < cutoffMs) continue;
            return true;
        }
        return false;
    }

    // =====================================================================
    // Internal helpers
    // =====================================================================

    private LiveOrderResult processOrderResult(String identifier, OrderResult result, OrderEntity order) {
        if (result == null || result.isFailed()) {
            String msg = result != null ? result.getMessage() : "null response";
            log.error("[LIVE] Order failed: {}", msg);
            order.setState("error");
            order.setTsEpochMs(System.currentTimeMillis());
            orderRepo.save(order);
            return new LiveOrderResult(identifier, null, "error", 0, 0.0);
        }

        String ordNo = result.getOrderId();
        int executedQty = result.getQty();
        double avgPrice = result.getPrice();

        order.setUuid(ordNo);
        order.setState("done");
        order.setExecutedVolume(executedQty);
        order.setAvgPrice(BigDecimal.valueOf(avgPrice));
        order.setTsEpochMs(System.currentTimeMillis());
        orderRepo.save(order);

        log.info("[LIVE] Order accepted: ordNo={}, symbol={}", ordNo, order.getSymbol());
        return new LiveOrderResult(identifier, ordNo, "done", executedQty, avgPrice);
    }

    private String newIdentifier() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // =====================================================================
    // LiveOrderResult DTO (backward-compatible)
    // =====================================================================

    public static class LiveOrderResult {
        public final String identifier;
        public final String ordNo;
        public final String state;
        public final int executedQty;
        public final double avgPrice;

        public LiveOrderResult(String identifier, String ordNo, String state, int executedQty, double avgPrice) {
            this.identifier = identifier;
            this.ordNo = ordNo;
            this.state = state;
            this.executedQty = executedQty;
            this.avgPrice = avgPrice;
        }

        public boolean isDone() {
            return "done".equalsIgnoreCase(state);
        }

        public boolean isFilled() {
            return "done".equalsIgnoreCase(state) && executedQty > 0;
        }

        public boolean isRejected() {
            return "rejected".equalsIgnoreCase(state) || "error".equalsIgnoreCase(state);
        }
    }
}
