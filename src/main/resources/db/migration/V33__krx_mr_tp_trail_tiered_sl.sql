-- 주식봇 모닝러쉬: TP_TRAIL + 티어드 SL 도입 (2026-04-14)
-- 코인봇 구조 동일 적용

-- TP_TRAIL
ALTER TABLE krx_morning_rush_config ADD COLUMN IF NOT EXISTS tp_trail_activate_pct DECIMAL(5,2) DEFAULT 3.0;
ALTER TABLE krx_morning_rush_config ADD COLUMN IF NOT EXISTS tp_trail_drop_pct DECIMAL(5,2) DEFAULT 1.5;

-- 티어드 SL
ALTER TABLE krx_morning_rush_config ADD COLUMN IF NOT EXISTS grace_period_sec INT DEFAULT 30;
ALTER TABLE krx_morning_rush_config ADD COLUMN IF NOT EXISTS wide_sl_pct DECIMAL(5,2) DEFAULT 3.0;
ALTER TABLE krx_morning_rush_config ADD COLUMN IF NOT EXISTS wide_period_min INT DEFAULT 10;

-- 설정 적용
UPDATE krx_morning_rush_config SET
  tp_trail_activate_pct = 3.0,
  tp_trail_drop_pct = 1.5,
  grace_period_sec = 30,
  wide_sl_pct = 3.0,
  wide_period_min = 10
WHERE id = 1;
