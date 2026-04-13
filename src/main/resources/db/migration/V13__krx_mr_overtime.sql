UPDATE krx_morning_rush_config SET
  top_n = 15,
  max_positions = 3,
  tp_pct = 3.0,
  sl_pct = 3.0,
  entry_delay_sec = 0
WHERE id = 1;
