package com.example.stocks.kis;

/**
 * DTO representing a single holding in a KIS brokerage account.
 */
public class KisAccount {

    private String symbol;
    private String name;
    private int qty;
    private double avgPrice;
    private double currentPrice;
    private double pnl;
    private String currency;  // KRW, USD

    public KisAccount() {
    }

    public KisAccount(String symbol, String name, int qty, double avgPrice,
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
        return "KisAccount{" +
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
