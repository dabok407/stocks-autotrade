package com.example.stocks.exchange;

/**
 * 주문 결과 DTO.
 * 증권사 API 응답을 통일된 형태로 표현한다.
 */
public class OrderResult {

    private String orderId;
    private String symbol;
    private String side;       // "BUY" or "SELL"
    private int qty;
    private double price;
    private Status status;
    private String message;

    public enum Status {
        FILLED, PENDING, CANCELLED, FAILED
    }

    public OrderResult() {
    }

    public OrderResult(String orderId, String symbol, String side, int qty, double price,
                       Status status, String message) {
        this.orderId = orderId;
        this.symbol = symbol;
        this.side = side;
        this.qty = qty;
        this.price = price;
        this.status = status;
        this.message = message;
    }

    public boolean isFilled() {
        return status == Status.FILLED;
    }

    public boolean isFailed() {
        return status == Status.FAILED;
    }

    // ---------- getters / setters ----------

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getSide() {
        return side;
    }

    public void setSide(String side) {
        this.side = side;
    }

    public int getQty() {
        return qty;
    }

    public void setQty(int qty) {
        this.qty = qty;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    @Override
    public String toString() {
        return "OrderResult{" +
                "orderId='" + orderId + '\'' +
                ", symbol='" + symbol + '\'' +
                ", side='" + side + '\'' +
                ", qty=" + qty +
                ", price=" + price +
                ", status=" + status +
                ", message='" + message + '\'' +
                '}';
    }
}
