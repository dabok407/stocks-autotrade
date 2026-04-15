-- V34: 주식봇 모닝러쉬 Split-Exit 분할 익절 (2026-04-15)
-- 코인봇 동일 구조 적용

-- ── config 테이블: Split-Exit 설정 4컬럼 ──
ALTER TABLE krx_morning_rush_config ADD COLUMN IF NOT EXISTS split_exit_enabled BOOLEAN DEFAULT TRUE;
ALTER TABLE krx_morning_rush_config ADD COLUMN IF NOT EXISTS split_tp_pct DECIMAL(5,2) DEFAULT 1.6;
ALTER TABLE krx_morning_rush_config ADD COLUMN IF NOT EXISTS split_ratio DECIMAL(5,2) DEFAULT 0.60;
ALTER TABLE krx_morning_rush_config ADD COLUMN IF NOT EXISTS trail_drop_after_split DECIMAL(5,2) DEFAULT 1.5;

-- ── position 테이블: Split 상태 2컬럼 ──
ALTER TABLE position ADD COLUMN IF NOT EXISTS split_phase INT DEFAULT 0;
ALTER TABLE position ADD COLUMN IF NOT EXISTS split_original_qty INT DEFAULT 0;

-- ── 설정 적용 ──
UPDATE krx_morning_rush_config SET
  split_exit_enabled = TRUE,
  split_tp_pct = 1.6,
  split_ratio = 0.60,
  trail_drop_after_split = 1.5
WHERE id = 1;
