UPDATE krx_morning_rush_config SET
  gap_threshold_pct = 0.01,
  tp_pct = 0.03,
  sl_pct = 0.5,
  session_end_hour = 15,
  session_end_min = 25
WHERE id = 1;
