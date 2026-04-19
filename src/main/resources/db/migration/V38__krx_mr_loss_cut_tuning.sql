-- V38: 모닝러쉬 Loss-Cut 튜닝 (2026-04-18)
-- 2026-04-14 ~ 04-17 운영 손실 분석 결과 반영.
--
-- 배경:
--   V28(2026-04-02) sl_pct = 1.0으로 과도하게 타이트하게 설정되어 Entity 기본값 2.0과 괴리.
--   V35(2026-04-15) wide_sl_pct = 2.0 으로 조정했으나 tight sl_pct 는 1.0 유지 → grace 구간 진입 손실 다수.
--   이번 주 손실 트레이드 대부분이 (sl=-1.0%, grace 30s) 조합에서 "노이즈 드롭" 에 걸려 조기 청산됨.
--
-- 투자전문가(2) 권고:
--   - sl_pct: 1.0 → 2.0 (Entity 기본값, wide_sl_pct와 동일 ─ 티어드 차이 단순화).
--   - gap_threshold_pct: 2.5 → 3.0 (저질 gap 엔트리 컷).
--   - max_positions: 3 → 2 (리스크 분산 축소, 3일 연속 손실 후 포지션 제한).
--   - grace_period_sec: 30 → 45 (초기 변동성 구간 확대, 섀도우 SL 방지).
--
-- 롤백:
--   UPDATE krx_morning_rush_config SET
--     sl_pct = 1.0, gap_threshold_pct = 2.5, max_positions = 3, grace_period_sec = 30
--   WHERE id = 1;

UPDATE krx_morning_rush_config SET
  sl_pct = 2.0,
  gap_threshold_pct = 3.0,
  max_positions = 2,
  grace_period_sec = 45
WHERE id = 1;
