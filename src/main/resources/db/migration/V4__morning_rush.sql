-- KRX Morning Rush Scanner Config
CREATE TABLE krx_morning_rush_config (
    id INT DEFAULT 1 NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    mode VARCHAR(10) NOT NULL DEFAULT 'PAPER',
    top_n INT NOT NULL DEFAULT 20,
    max_positions INT NOT NULL DEFAULT 2,
    order_sizing_mode VARCHAR(10) NOT NULL DEFAULT 'PCT',
    order_sizing_value DECIMAL(10,2) NOT NULL DEFAULT 5,
    gap_threshold_pct DECIMAL(5,2) NOT NULL DEFAULT 2.5,
    volume_mult DECIMAL(5,2) NOT NULL DEFAULT 3.0,
    confirm_count INT NOT NULL DEFAULT 2,
    check_interval_sec INT NOT NULL DEFAULT 5,
    tp_pct DECIMAL(5,2) NOT NULL DEFAULT 1.5,
    sl_pct DECIMAL(5,2) NOT NULL DEFAULT 1.5,
    entry_delay_sec INT NOT NULL DEFAULT 30,
    session_end_hour INT NOT NULL DEFAULT 10,
    session_end_min INT NOT NULL DEFAULT 0,
    time_stop_min INT NOT NULL DEFAULT 30,
    min_trade_amount BIGINT NOT NULL DEFAULT 1000000000,
    exclude_symbols VARCHAR(1000) DEFAULT '',
    min_price_krw INT NOT NULL DEFAULT 1000,
    CONSTRAINT pk_krx_mr PRIMARY KEY (id)
);
INSERT INTO krx_morning_rush_config (id) VALUES (1);

-- NYSE Morning Rush Scanner Config
CREATE TABLE nyse_morning_rush_config (
    id INT DEFAULT 1 NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    mode VARCHAR(10) NOT NULL DEFAULT 'PAPER',
    top_n INT NOT NULL DEFAULT 20,
    max_positions INT NOT NULL DEFAULT 2,
    order_sizing_mode VARCHAR(10) NOT NULL DEFAULT 'PCT',
    order_sizing_value DECIMAL(10,2) NOT NULL DEFAULT 5,
    gap_threshold_pct DECIMAL(5,2) NOT NULL DEFAULT 4.0,
    pm_high_breakout_pct DECIMAL(5,2) NOT NULL DEFAULT 2.0,
    volume_mult DECIMAL(5,2) NOT NULL DEFAULT 2.0,
    confirm_count INT NOT NULL DEFAULT 2,
    check_interval_sec INT NOT NULL DEFAULT 5,
    tp_pct DECIMAL(5,2) NOT NULL DEFAULT 3.0,
    sl_pct DECIMAL(5,2) NOT NULL DEFAULT 2.0,
    entry_delay_sec INT NOT NULL DEFAULT 30,
    session_end_hour INT NOT NULL DEFAULT 10,
    session_end_min INT NOT NULL DEFAULT 30,
    time_stop_min INT NOT NULL DEFAULT 20,
    min_price_usd INT NOT NULL DEFAULT 5,
    exclude_symbols VARCHAR(1000) DEFAULT '',
    CONSTRAINT pk_nyse_mr PRIMARY KEY (id)
);
INSERT INTO nyse_morning_rush_config (id) VALUES (1);
