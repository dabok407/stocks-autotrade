UPDATE krx_morning_rush_config SET
  mode = 'PAPER',
  gap_threshold_pct = 0.01,
  volume_mult = 1.0,
  confirm_count = 1,
  tp_pct = 0.03,
  sl_pct = 0.5,
  session_end_hour = 16,
  session_end_min = 40
WHERE id = 1;
