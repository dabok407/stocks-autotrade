package com.example.stocks.exchange;

/**
 * 계좌 보유 종목 DTO.
 * 증권사별 잔고 조회 결과를 통일된 형태로 표현한다.
 */
public class AccountPosition {

    private String symbol;
    private String name;
    private int qty;
    private double avgPrice;
    private double currentPrice;
    private double pnl;
    private String currency;  // "KRW", "USD"

    public AccountPosition() {
    }

    public AccountPosition(String symbol, String name, int qty, double avgPrice,
                           double currentPrice, double pnl, String currency) {
        this.symbol = symbol;
        this.name = name;
        this.qty = qty;
        this.avgPrice = avgPrice;
        this.currentPrice = currentPrice;
        this.pnl = pnl;
        this.currency = currency;
    }

    // ---------- getters / setters ----------

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getQty() {
        return qty;
    }

    public void setQty(int qty) {
        this.qty = qty;
    }

    public double getAvgPrice() {
        return avgPrice;
    }

    public void setAvgPrice(double avgPrice) {
        this.avgPrice = avgPrice;
    }

    public double getCurrentPrice() {
        return currentPrice;
    }

    public void setCurrentPrice(double currentPrice) {
        this.currentPrice = currentPrice;
    }

    public double getPnl() {
        return pnl;
    }

    public void setPnl(double pnl) {
        this.pnl = pnl;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    @Override
    public String toString() {
        return "AccountPosition{" +
                "symbol='" + symbol + '\'' +
                ", name='" + name + '\'' +
                ", qty=" + qty +
                ", avgPrice=" + avgPrice +
                ", currentPrice=" + currentPrice +
                ", pnl=" + pnl +
                ", currency='" + currency + '\'' +
                '}';
    }
}
