-- E2E PAPER 전체 싸이클 테스트 (매수→TP→SL→세션종료)
UPDATE krx_morning_rush_config SET
  mode = 'PAPER',
  gap_threshold_pct = 0.01,
  volume_mult = 1.0,
  confirm_count = 1,
  tp_pct = 0.05,
  sl_pct = 0.5,
  session_end_hour = 11,
  session_end_min = 25,
  max_positions = 3
WHERE id = 1;
