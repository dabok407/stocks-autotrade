-- V36: Split-Exit 매도 비율 조정 (2026-04-16)
-- 1차 매도 60% → 40%, 2차 매도 40% → 60%로 전환
-- 1차는 고정 TP_TARGET (+1.6%), 2차는 TP_TRAIL(1.5% drop)/breakeven 유지.

UPDATE krx_morning_rush_config SET
  split_ratio = 0.40
WHERE id = 1;
