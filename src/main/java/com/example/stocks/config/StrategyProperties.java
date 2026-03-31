package com.example.stocks.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "strategy")
public class StrategyProperties {
    private int consecutiveDown = 4;
    // TP 2%
    private double takeProfitRate = 0.02;
    private boolean addBuyOnEachExtraDown = false;
    // 주식 수수료 0.015% (증권사 온라인 기준)
    private double feeRate = 0.00015;

    public int getConsecutiveDown() { return consecutiveDown; }
    public void setConsecutiveDown(int consecutiveDown) { this.consecutiveDown = consecutiveDown; }

    public double getTakeProfitRate() { return takeProfitRate; }
    public void setTakeProfitRate(double takeProfitRate) { this.takeProfitRate = takeProfitRate; }

    public boolean isAddBuyOnEachExtraDown() { return addBuyOnEachExtraDown; }
    public void setAddBuyOnEachExtraDown(boolean addBuyOnEachExtraDown) { this.addBuyOnEachExtraDown = addBuyOnEachExtraDown; }

    public double getFeeRate() { return feeRate; }
    public void setFeeRate(double feeRate) { this.feeRate = feeRate; }
}
