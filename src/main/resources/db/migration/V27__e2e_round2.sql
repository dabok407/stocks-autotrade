-- E2E Round 2: PAPER + FIXED order + 음수 gap + tp/sl 작게 + sessionEnd 12:35
UPDATE krx_morning_rush_config SET
  mode = 'PAPER',
  gap_threshold_pct = -10.0,
  volume_mult = 1.0,
  confirm_count = 1,
  tp_pct = 0.3,
  sl_pct = 0.5,
  session_end_hour = 12,
  session_end_min = 35,
  max_positions = 3,
  order_sizing_mode = 'FIXED',
  order_sizing_value = 100000
WHERE id = 1;
