-- E2E: gap 음수 허용해서 거의 무조건 진입
UPDATE krx_morning_rush_config SET
  gap_threshold_pct = -10.0,
  session_end_hour = 11,
  session_end_min = 50
WHERE id = 1;
