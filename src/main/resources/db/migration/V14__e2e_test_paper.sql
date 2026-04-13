-- V14: E2E PAPER 테스트 (시간외 API + 매수/매도)
UPDATE krx_morning_rush_config SET
  mode = 'PAPER',
  gap_threshold_pct = 0.3,
  volume_mult = 1.0,
  confirm_count = 1,
  session_end_hour = 14,
  session_end_min = 30,
  tp_pct = 0.5,
  sl_pct = 1.0
WHERE id = 1;
