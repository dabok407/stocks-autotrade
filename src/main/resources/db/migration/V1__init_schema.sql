-- bot_config: single row, global settings
CREATE TABLE bot_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    mode VARCHAR(10) NOT NULL DEFAULT 'PAPER',
    market_type VARCHAR(10) NOT NULL DEFAULT 'KRX',
    candle_unit_min INT NOT NULL DEFAULT 5,
    strategy_type VARCHAR(64) NOT NULL DEFAULT 'REGIME_PULLBACK',
    strategy_types_csv VARCHAR(1024) DEFAULT '',
    capital_krw DECIMAL(20,2) NOT NULL DEFAULT 500000,
    take_profit_pct DECIMAL(10,4) NOT NULL DEFAULT 3.0,
    stop_loss_pct DECIMAL(10,4) NOT NULL DEFAULT 2.0,
    trailing_stop_pct DECIMAL(10,4) NOT NULL DEFAULT 0,
    max_add_buys_global INT NOT NULL DEFAULT 2,
    strategy_lock BOOLEAN NOT NULL DEFAULT FALSE,
    min_confidence DOUBLE NOT NULL DEFAULT 0,
    time_stop_minutes INT NOT NULL DEFAULT 0,
    max_drawdown_pct DECIMAL(10,4) NOT NULL DEFAULT 0,
    daily_loss_limit_pct DECIMAL(10,4) NOT NULL DEFAULT 0,
    strategy_intervals_csv VARCHAR(2048) DEFAULT '',
    ema_filter_csv VARCHAR(2048) DEFAULT ''
);
INSERT INTO bot_config (id) VALUES (1);

-- stock_config: per-stock settings
CREATE TABLE stock_config (
    symbol VARCHAR(20) PRIMARY KEY,
    market_type VARCHAR(10) NOT NULL DEFAULT 'KRX',
    display_name VARCHAR(100),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    base_order_krw DECIMAL(20,2) NOT NULL DEFAULT 100000
);

-- position: current holdings
CREATE TABLE position (
    symbol VARCHAR(20) PRIMARY KEY,
    market_type VARCHAR(10) NOT NULL DEFAULT 'KRX',
    qty INT NOT NULL DEFAULT 0,
    avg_price DECIMAL(28,8) NOT NULL DEFAULT 0,
    add_buys INT NOT NULL DEFAULT 0,
    opened_at TIMESTAMP,
    updated_at TIMESTAMP,
    entry_strategy VARCHAR(100)
);

-- trade_log: completed trades
CREATE TABLE trade_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ts_epoch_ms BIGINT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    market_type VARCHAR(10) NOT NULL DEFAULT 'KRX',
    action VARCHAR(20) NOT NULL,
    price DECIMAL(28,12) NOT NULL,
    qty INT NOT NULL,
    pnl_krw DECIMAL(20,2) NOT NULL DEFAULT 0,
    roi_percent DECIMAL(16,6) NOT NULL DEFAULT 0,
    mode VARCHAR(10) NOT NULL,
    note VARCHAR(512),
    pattern_type VARCHAR(64),
    pattern_reason VARCHAR(512),
    avg_buy_price DECIMAL(28,12),
    confidence DOUBLE,
    candle_unit_min INT,
    currency VARCHAR(3) DEFAULT 'KRW'
);

-- order_log
CREATE TABLE order_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    identifier VARCHAR(64) NOT NULL UNIQUE,
    symbol VARCHAR(20) NOT NULL,
    market_type VARCHAR(10) NOT NULL DEFAULT 'KRX',
    side VARCHAR(10) NOT NULL,
    ord_type VARCHAR(20),
    price DECIMAL(28,8),
    qty INT,
    uuid VARCHAR(64),
    state VARCHAR(20),
    executed_volume INT,
    avg_price DECIMAL(28,8),
    ts_epoch_ms BIGINT NOT NULL
);

-- strategy_state
CREATE TABLE strategy_state (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    strategy_type VARCHAR(64) NOT NULL,
    state_json TEXT,
    UNIQUE(symbol, strategy_type)
);

-- app_users
CREATE TABLE app_users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(200) NOT NULL
);

-- api_key_store
CREATE TABLE api_key_store (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider VARCHAR(20) NOT NULL DEFAULT 'KIS',
    enc_key1 TEXT NOT NULL,
    enc_key2 TEXT NOT NULL,
    account_no VARCHAR(20),
    is_paper BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- candle_cache
CREATE TABLE candle_cache (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    interval_min INT NOT NULL,
    candle_ts_utc VARCHAR(30) NOT NULL,
    open_price DOUBLE NOT NULL,
    high_price DOUBLE NOT NULL,
    low_price DOUBLE NOT NULL,
    close_price DOUBLE NOT NULL,
    volume DOUBLE NOT NULL,
    UNIQUE(symbol, interval_min, candle_ts_utc)
);
