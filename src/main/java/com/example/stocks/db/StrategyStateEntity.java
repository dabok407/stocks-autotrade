package com.example.stocks.db;

import javax.persistence.*;

/**
 * Persistent per-symbol per-strategy runtime state for safe restart / catch-up.
 * Maps to existing strategy_state table (V1 schema: id PK, symbol+strategy_type unique).
 */
@Entity
@Table(name = "STRATEGY_STATE",
       uniqueConstraints = @UniqueConstraint(columnNames = {"symbol", "strategy_type"}))
public class StrategyStateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "SYMBOL", length = 20, nullable = false)
    private String symbol;

    @Column(name = "STRATEGY_TYPE", length = 64, nullable = false)
    private String strategyType;

    @Column(name = "STATE_JSON", columnDefinition = "TEXT")
    private String stateJson;

    public StrategyStateEntity() {}

    public StrategyStateEntity(String symbol, String strategyType) {
        this.symbol = symbol;
        this.strategyType = strategyType;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getStrategyType() { return strategyType; }
    public void setStrategyType(String strategyType) { this.strategyType = strategyType; }

    public String getStateJson() { return stateJson; }
    public void setStateJson(String stateJson) { this.stateJson = stateJson; }
}
