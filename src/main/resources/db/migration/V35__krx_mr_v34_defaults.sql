-- V35: V33 기본값을 V34 운영 기준으로 보정 (2026-04-15)
-- tp_trail_activate_pct: 3.0 → 2.1 (더 빠른 trail 활성화)
-- wide_sl_pct: 3.0 → 2.0 (더 타이트한 SL)
-- sl_pct(tight): 기존 유지 확인용

UPDATE krx_morning_rush_config SET
  tp_trail_activate_pct = 2.1,
  wide_sl_pct = 2.0
WHERE id = 1;
