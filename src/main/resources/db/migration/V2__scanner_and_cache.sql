-- strategy_group: independent strategy/risk settings per symbol set
CREATE TABLE strategy_group (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_name VARCHAR(100) NOT NULL DEFAULT 'Group 1',
    sort_order INT NOT NULL DEFAULT 0,
    symbols_csv VARCHAR(2048) NOT NULL DEFAULT '',
    strategy_types_csv VARCHAR(1024) NOT NULL DEFAULT '',
    candle_unit_min INT NOT NULL DEFAULT 60,
    order_sizing_mode VARCHAR(20) NOT NULL DEFAULT 'PCT',
    order_sizing_value DECIMAL(19,4) NOT NULL DEFAULT 90,
    take_profit_pct DECIMAL(10,4) NOT NULL DEFAULT 3.0,
    stop_loss_pct DECIMAL(10,4) NOT NULL DEFAULT 2.0,
    max_add_buys INT NOT NULL DEFAULT 2,
    strategy_lock TINYINT(1) NOT NULL DEFAULT 0,
    min_confidence DOUBLE NOT NULL DEFAULT 0,
    time_stop_minutes INT NOT NULL DEFAULT 0,
    strategy_intervals_csv VARCHAR(2048) NOT NULL DEFAULT '',
    ema_filter_csv VARCHAR(2048) NOT NULL DEFAULT '',
    selected_preset VARCHAR(20)
);

-- krx_scanner_config: KRX opening range breakout scanner (single row, id=1)
CREATE TABLE krx_scanner_config (
    id INT PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    mode VARCHAR(10) NOT NULL DEFAULT 'PAPER',
    top_n INT NOT NULL DEFAULT 15,
    max_positions INT NOT NULL DEFAULT 3,
    order_sizing_mode VARCHAR(10) NOT NULL DEFAULT 'PCT',
    order_sizing_value DECIMAL(10,2) NOT NULL DEFAULT 30,
    candle_unit_min INT NOT NULL DEFAULT 5,
    range_start_hour INT NOT NULL DEFAULT 9,
    range_start_min INT NOT NULL DEFAULT 0,
    range_end_hour INT NOT NULL DEFAULT 9,
    range_end_min INT NOT NULL DEFAULT 15,
    entry_start_hour INT NOT NULL DEFAULT 9,
    entry_start_min INT NOT NULL DEFAULT 15,
    entry_end_hour INT NOT NULL DEFAULT 10,
    entry_end_min INT NOT NULL DEFAULT 30,
    session_end_hour INT NOT NULL DEFAULT 15,
    session_end_min INT NOT NULL DEFAULT 15,
    tp_atr_mult DECIMAL(5,2) NOT NULL DEFAULT 1.5,
    sl_pct DECIMAL(5,2) NOT NULL DEFAULT 2.0,
    trail_atr_mult DECIMAL(5,2) NOT NULL DEFAULT 0.6,
    kospi_filter_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    kospi_ema_period INT NOT NULL DEFAULT 20,
    volume_mult DECIMAL(5,2) NOT NULL DEFAULT 1.5,
    min_body_ratio DECIMAL(5,2) NOT NULL DEFAULT 0.45,
    exclude_symbols VARCHAR(1000) DEFAULT '',
    min_price_krw INT NOT NULL DEFAULT 1000
);
INSERT INTO krx_scanner_config (id) VALUES (1);

-- us_stock_scanner_config: US market scanner (single row, id=1)
CREATE TABLE us_stock_scanner_config (
    id INT PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    mode VARCHAR(10) NOT NULL DEFAULT 'PAPER',
    top_n INT NOT NULL DEFAULT 15,
    max_positions INT NOT NULL DEFAULT 2,
    order_sizing_mode VARCHAR(10) NOT NULL DEFAULT 'PCT',
    order_sizing_value DECIMAL(10,2) NOT NULL DEFAULT 20,
    candle_unit_min INT NOT NULL DEFAULT 5,
    entry_start_hour INT NOT NULL DEFAULT 10,
    entry_start_min INT NOT NULL DEFAULT 0,
    entry_end_hour INT NOT NULL DEFAULT 15,
    entry_end_min INT NOT NULL DEFAULT 0,
    session_end_hour INT NOT NULL DEFAULT 15,
    session_end_min INT NOT NULL DEFAULT 45,
    sl_pct DECIMAL(5,2) NOT NULL DEFAULT 1.5,
    trail_atr_mult DECIMAL(5,2) NOT NULL DEFAULT 0.8,
    min_confidence DECIMAL(5,2) NOT NULL DEFAULT 9.4,
    time_stop_candles INT NOT NULL DEFAULT 12,
    time_stop_min_pnl DECIMAL(5,2) NOT NULL DEFAULT 0.3,
    spy_filter_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    spy_ema_period INT NOT NULL DEFAULT 20,
    volume_surge_mult DECIMAL(5,2) NOT NULL DEFAULT 3.0,
    min_body_ratio DECIMAL(5,2) NOT NULL DEFAULT 0.60,
    exclude_symbols VARCHAR(1000) DEFAULT '',
    min_price_usd INT NOT NULL DEFAULT 5,
    quick_tp_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    quick_tp_pct DECIMAL(5,2) NOT NULL DEFAULT 0.70,
    quick_tp_interval_sec INT NOT NULL DEFAULT 5
);
INSERT INTO us_stock_scanner_config (id) VALUES (1);

-- optimization_result: backtest optimization results
CREATE TABLE optimization_result (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(50) NOT NULL,
    strategy_type VARCHAR(50) NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    interval_min INT NOT NULL,
    tp_pct DOUBLE NOT NULL,
    sl_pct DOUBLE NOT NULL,
    max_add_buys INT NOT NULL,
    min_confidence DOUBLE NOT NULL,
    strategy_lock BOOLEAN NOT NULL,
    time_stop_minutes INT NOT NULL,
    ema_period INT NOT NULL,
    roi DOUBLE NOT NULL,
    win_rate DOUBLE NOT NULL,
    total_trades INT NOT NULL,
    wins INT NOT NULL,
    total_pnl DOUBLE NOT NULL,
    final_capital DOUBLE NOT NULL,
    tp_sell_count INT NOT NULL,
    sl_sell_count INT NOT NULL,
    pattern_sell_count INT NOT NULL,
    strategies_csv VARCHAR(500),
    strategy_intervals_csv VARCHAR(200),
    ema_filter_csv VARCHAR(200),
    phase INT DEFAULT 1,
    roi_3m DOUBLE,
    win_rate_3m DOUBLE,
    total_trades_3m INT,
    wins_3m INT,
    roi_1m DOUBLE,
    win_rate_1m DOUBLE,
    total_trades_1m INT,
    wins_1m INT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Default stock configs: Samsung, NAVER
INSERT INTO stock_config (symbol, market_type, display_name, enabled, base_order_krw)
VALUES ('005930', 'KRX', 'Samsung Electronics', TRUE, 100000);
INSERT INTO stock_config (symbol, market_type, display_name, enabled, base_order_krw)
VALUES ('035420', 'KRX', 'NAVER', TRUE, 100000);
