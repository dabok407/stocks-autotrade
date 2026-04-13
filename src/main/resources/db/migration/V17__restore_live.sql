-- V17: 모닝러쉬만 ON, 오프닝/올데이 OFF, Entry 09:00~09:10 (5분 연장)
UPDATE krx_opening_config SET enabled = false WHERE id = 1;
UPDATE krx_allday_config SET enabled = false WHERE id = 1;
UPDATE krx_morning_rush_config SET
  mode = 'LIVE',
  gap_threshold_pct = 2.0,
  volume_mult = 3.0,
  confirm_count = 2,
  tp_pct = 3.0,
  sl_pct = 3.0,
  entry_delay_sec = 0,
  session_end_hour = 10,
  session_end_min = 0
WHERE id = 1;
