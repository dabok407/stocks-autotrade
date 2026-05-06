-- V42 (2026-05-06): Stuck 포지션 자동 청산 플래그 추가
--
-- 배경:
--   2026-05-06 V41 배포 후 사용자 지적:
--     "매도 못한 종목 아직 가지고 있는데 매도 안되고 그대로 있는데?"
--   기존 P1 정책 = "사용자가 수동 매도" → 봇이 실수로 stuck 만들었으면 봇이 청산해야 한다는 사용자 의견 정당.
--
-- 새 정책 (V42):
--   PositionReconciler 가 ORPHAN_BROKER 검출 시, trade_log 에 봇 BUY 이력
--   (patternType=KRX_MORNING_RUSH) 이 있는 symbol = STUCK_BOT_POSITION → 자동 시장가 매도.
--   trade_log 에 BUY 이력 없는 symbol (사용자 본인 매수: 삼성전자, 현대모비스 등)은 절대 매도 안 함.
--
-- 안전장치:
--   1) auto_cleanup_stuck_enabled flag 로 ON/OFF 가능 (default true)
--   2) 시장 시간(09:00-15:30 KST) 내에서만 시도
--   3) 매도 시도한 symbol 은 in-memory Set 에 추가 → 같은 세션 반복 시도 방지
--   4) 시장가 매도 (체결 보장) — P0-Fix#2 활용
--   5) 매도 후 trade_log 에 SELL 기록 (RECONCILE_STUCK_CLEANUP)
--
-- 롤백:
--   ALTER TABLE krx_morning_rush_config DROP COLUMN auto_cleanup_stuck_enabled;

ALTER TABLE krx_morning_rush_config ADD COLUMN auto_cleanup_stuck_enabled BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE krx_morning_rush_config SET auto_cleanup_stuck_enabled = TRUE WHERE id = 1;
