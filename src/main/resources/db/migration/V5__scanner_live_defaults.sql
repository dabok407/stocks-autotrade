-- V5: Scanner LIVE optimal defaults (reviewed by investment experts)
-- Conservative settings for first-time LIVE trading

-- === KRX Opening Scanner ===
UPDATE krx_opening_config SET
    top_n = 15,
    max_positions = 2,
    order_sizing_mode = 'PCT',
    order_sizing_value = 25.00,
    range_end_hour = 9, range_end_min = 20,
    entry_start_hour = 9, entry_start_min = 20,
    entry_end_hour = 10, entry_end_min = 0,
    session_end_hour = 14, session_end_min = 30,
    tp_atr_mult = 1.20,
    sl_pct = 2.00,
    trail_atr_mult = 0.60,
    volume_mult = 2.00,
    min_price_krw = 5000,
    quick_tp_enabled = TRUE,
    quick_tp_pct = 0.80,
    exclude_symbols = '005930,012330'
WHERE id = 1;

-- === KRX AllDay Scanner ===
UPDATE krx_allday_config SET
    top_n = 15,
    max_positions = 1,
    order_sizing_mode = 'PCT',
    order_sizing_value = 15.00,
    entry_end_hour = 13, entry_end_min = 30,
    session_end_hour = 14, session_end_min = 45,
    min_confidence = 9.60,
    time_stop_candles = 10,
    quick_tp_pct = 0.60,
    min_price_krw = 5000,
    exclude_symbols = '005930,012330'
WHERE id = 1;

-- === KRX Morning Rush ===
UPDATE krx_morning_rush_config SET
    top_n = 10,
    max_positions = 1,
    gap_threshold_pct = 3.00,
    volume_mult = 4.00,
    confirm_count = 3,
    tp_pct = 1.20,
    sl_pct = 1.20,
    entry_delay_sec = 45,
    time_stop_min = 20,
    min_trade_amount = 5000000000,
    min_price_krw = 5000,
    exclude_symbols = '005930,012330'
WHERE id = 1;

-- === NYSE Opening Scanner ===
UPDATE nyse_opening_config SET
    top_n = 15,
    max_positions = 2,
    order_sizing_mode = 'PCT',
    order_sizing_value = 45.00,
    entry_start_hour = 10, entry_start_min = 5,
    entry_end_hour = 11, entry_end_min = 0,
    tp_atr_mult = 1.20,
    sl_pct = 2.00,
    volume_mult = 2.00,
    min_price_usd = 10,
    quick_tp_enabled = TRUE,
    quick_tp_pct = 0.80,
    exclude_symbols = 'AAPL'
WHERE id = 1;

-- === NYSE AllDay Scanner ===
UPDATE nyse_allday_config SET
    top_n = 12,
    max_positions = 1,
    order_sizing_mode = 'PCT',
    order_sizing_value = 25.00,
    entry_end_hour = 14, entry_end_min = 30,
    min_confidence = 9.60,
    time_stop_candles = 10,
    quick_tp_pct = 0.60,
    min_price_usd = 10,
    exclude_symbols = 'AAPL'
WHERE id = 1;

-- === NYSE Morning Rush ===
UPDATE nyse_morning_rush_config SET
    top_n = 10,
    max_positions = 1,
    gap_threshold_pct = 5.00,
    volume_mult = 2.50,
    confirm_count = 3,
    tp_pct = 2.00,
    sl_pct = 1.50,
    entry_delay_sec = 45,
    session_end_hour = 10, session_end_min = 0,
    time_stop_min = 15,
    min_price_usd = 15,
    exclude_symbols = 'AAPL'
WHERE id = 1;
