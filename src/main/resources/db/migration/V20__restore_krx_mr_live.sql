-- KRX Morning Rush: LIVE 모드 + 정상 파라미터 복원 (E2E 테스트 완료)
UPDATE krx_morning_rush_config SET
  mode = 'LIVE',
  gap_threshold_pct = 2.0,
  volume_mult = 3.0,
  confirm_count = 3,
  tp_pct = 1.5,
  sl_pct = 1.0,
  session_end_hour = 10,
  session_end_min = 0,
  max_positions = 3
WHERE id = 1;
