-- V3: 4 scanner config tables (KRX opening/allday, NYSE opening/allday)

-- KRX Opening Range Breakout Scanner
CREATE TABLE krx_opening_config (
    id INT DEFAULT 1 NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    mode VARCHAR(10) NOT NULL DEFAULT 'PAPER',
    top_n INT NOT NULL DEFAULT 20,
    max_positions INT NOT NULL DEFAULT 3,
    order_sizing_mode VARCHAR(10) NOT NULL DEFAULT 'PCT',
    order_sizing_value DECIMAL(10,2) NOT NULL DEFAULT 33,
    candle_unit_min INT NOT NULL DEFAULT 5,
    range_start_hour INT NOT NULL DEFAULT 9, range_start_min INT NOT NULL DEFAULT 0,
    range_end_hour INT NOT NULL DEFAULT 9, range_end_min INT NOT NULL DEFAULT 15,
    entry_start_hour INT NOT NULL DEFAULT 9, entry_start_min INT NOT NULL DEFAULT 15,
    entry_end_hour INT NOT NULL DEFAULT 10, entry_end_min INT NOT NULL DEFAULT 30,
    session_end_hour INT NOT NULL DEFAULT 15, session_end_min INT NOT NULL DEFAULT 15,
    tp_atr_mult DECIMAL(5,2) NOT NULL DEFAULT 1.5,
    sl_pct DECIMAL(5,2) NOT NULL DEFAULT 2.8,
    trail_atr_mult DECIMAL(5,2) NOT NULL DEFAULT 0.7,
    kospi_filter_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    kospi_ema_period INT NOT NULL DEFAULT 20,
    volume_mult DECIMAL(5,2) NOT NULL DEFAULT 1.5,
    min_body_ratio DECIMAL(5,2) NOT NULL DEFAULT 0.45,
    exclude_symbols VARCHAR(1000) DEFAULT '',
    min_price_krw INT NOT NULL DEFAULT 1000,
    open_failed_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    quick_tp_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    quick_tp_pct DECIMAL(5,2) NOT NULL DEFAULT 0.70,
    quick_tp_interval_sec INT NOT NULL DEFAULT 5,
    CONSTRAINT pk_krx_opening PRIMARY KEY (id)
);
INSERT INTO krx_opening_config (id) VALUES (1);

-- KRX AllDay Momentum Scanner
CREATE TABLE krx_allday_config (
    id INT DEFAULT 1 NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    mode VARCHAR(10) NOT NULL DEFAULT 'PAPER',
    top_n INT NOT NULL DEFAULT 20,
    max_positions INT NOT NULL DEFAULT 2,
    order_sizing_mode VARCHAR(10) NOT NULL DEFAULT 'PCT',
    order_sizing_value DECIMAL(10,2) NOT NULL DEFAULT 20,
    candle_unit_min INT NOT NULL DEFAULT 5,
    entry_start_hour INT NOT NULL DEFAULT 10, entry_start_min INT NOT NULL DEFAULT 35,
    entry_end_hour INT NOT NULL DEFAULT 14, entry_end_min INT NOT NULL DEFAULT 0,
    session_end_hour INT NOT NULL DEFAULT 15, session_end_min INT NOT NULL DEFAULT 15,
    sl_pct DECIMAL(5,2) NOT NULL DEFAULT 1.5,
    trail_atr_mult DECIMAL(5,2) NOT NULL DEFAULT 0.8,
    min_confidence DECIMAL(5,2) NOT NULL DEFAULT 9.4,
    time_stop_candles INT NOT NULL DEFAULT 12,
    time_stop_min_pnl DECIMAL(5,2) NOT NULL DEFAULT 0.3,
    kospi_filter_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    kospi_ema_period INT NOT NULL DEFAULT 20,
    volume_surge_mult DECIMAL(5,2) NOT NULL DEFAULT 3.0,
    min_body_ratio DECIMAL(5,2) NOT NULL DEFAULT 0.60,
    exclude_symbols VARCHAR(1000) DEFAULT '',
    min_price_krw INT NOT NULL DEFAULT 1000,
    quick_tp_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    quick_tp_pct DECIMAL(5,2) NOT NULL DEFAULT 0.70,
    quick_tp_interval_sec INT NOT NULL DEFAULT 5,
    CONSTRAINT pk_krx_allday PRIMARY KEY (id)
);
INSERT INTO krx_allday_config (id) VALUES (1);

-- NYSE Opening Range Breakout Scanner (times in ET)
CREATE TABLE nyse_opening_config (
    id INT DEFAULT 1 NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    mode VARCHAR(10) NOT NULL DEFAULT 'PAPER',
    top_n INT NOT NULL DEFAULT 20,
    max_positions INT NOT NULL DEFAULT 3,
    order_sizing_mode VARCHAR(10) NOT NULL DEFAULT 'PCT',
    order_sizing_value DECIMAL(10,2) NOT NULL DEFAULT 33,
    candle_unit_min INT NOT NULL DEFAULT 5,
    range_start_hour INT NOT NULL DEFAULT 9, range_start_min INT NOT NULL DEFAULT 30,
    range_end_hour INT NOT NULL DEFAULT 10, range_end_min INT NOT NULL DEFAULT 0,
    entry_start_hour INT NOT NULL DEFAULT 10, entry_start_min INT NOT NULL DEFAULT 0,
    entry_end_hour INT NOT NULL DEFAULT 11, entry_end_min INT NOT NULL DEFAULT 30,
    session_end_hour INT NOT NULL DEFAULT 15, session_end_min INT NOT NULL DEFAULT 45,
    tp_atr_mult DECIMAL(5,2) NOT NULL DEFAULT 1.5,
    sl_pct DECIMAL(5,2) NOT NULL DEFAULT 2.8,
    trail_atr_mult DECIMAL(5,2) NOT NULL DEFAULT 0.7,
    spy_filter_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    spy_ema_period INT NOT NULL DEFAULT 20,
    volume_mult DECIMAL(5,2) NOT NULL DEFAULT 1.5,
    min_body_ratio DECIMAL(5,2) NOT NULL DEFAULT 0.45,
    exclude_symbols VARCHAR(1000) DEFAULT '',
    min_price_usd INT NOT NULL DEFAULT 5,
    open_failed_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    quick_tp_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    quick_tp_pct DECIMAL(5,2) NOT NULL DEFAULT 0.70,
    quick_tp_interval_sec INT NOT NULL DEFAULT 5,
    CONSTRAINT pk_nyse_opening PRIMARY KEY (id)
);
INSERT INTO nyse_opening_config (id) VALUES (1);

-- NYSE AllDay Momentum Scanner (times in ET)
CREATE TABLE nyse_allday_config (
    id INT DEFAULT 1 NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    mode VARCHAR(10) NOT NULL DEFAULT 'PAPER',
    top_n INT NOT NULL DEFAULT 20,
    max_positions INT NOT NULL DEFAULT 2,
    order_sizing_mode VARCHAR(10) NOT NULL DEFAULT 'PCT',
    order_sizing_value DECIMAL(10,2) NOT NULL DEFAULT 20,
    candle_unit_min INT NOT NULL DEFAULT 5,
    entry_start_hour INT NOT NULL DEFAULT 11, entry_start_min INT NOT NULL DEFAULT 30,
    entry_end_hour INT NOT NULL DEFAULT 15, entry_end_min INT NOT NULL DEFAULT 0,
    session_end_hour INT NOT NULL DEFAULT 15, session_end_min INT NOT NULL DEFAULT 45,
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
    quick_tp_interval_sec INT NOT NULL DEFAULT 5,
    CONSTRAINT pk_nyse_allday PRIMARY KEY (id)
);
INSERT INTO nyse_allday_config (id) VALUES (1);

-- Add scanner_source to position and trade_log
ALTER TABLE position ADD COLUMN IF NOT EXISTS scanner_source VARCHAR(30) DEFAULT 'MAIN';
ALTER TABLE trade_log ADD COLUMN IF NOT EXISTS scanner_source VARCHAR(30) DEFAULT 'MAIN';
ALTER TABLE trade_log ADD COLUMN IF NOT EXISTS settlement_date VARCHAR(10);
