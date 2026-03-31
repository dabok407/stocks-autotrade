-- V6: Add US market capital and mode to bot_config
ALTER TABLE bot_config ADD COLUMN IF NOT EXISTS us_mode VARCHAR(10) DEFAULT 'PAPER';
ALTER TABLE bot_config ADD COLUMN IF NOT EXISTS us_capital_krw DECIMAL(20,2) DEFAULT 500000;
