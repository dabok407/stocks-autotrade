-- V43 (2026-05-06): stuck cleanup 안전 강화 — 화이트리스트 기반 + default OFF
--
-- 배경 (V42 검증 결과 false positive 발견):
--   PositionReconciler 가 005880 대한해운 24주를 STUCK_BOT_POSITION 로 오분류.
--   실제로는: 봇이 04-16 BUY 24 → SELL 24 정상 체결 완료.
--   현재 보유 24주는 사용자가 별도 시점에 추가 매수한 본인 보유분.
--   trade_log 에 봇 BUY 이력만 있다고 자동 매도하면 사용자 자산 강제 매도 위험!
--
-- 변경:
--   1) auto_cleanup_stuck_enabled default true → false (안전 우선)
--   2) stuck_cleanup_whitelist VARCHAR(500) — 사용자가 명시적으로 등록한 symbol 만 매도
--      형식: "073540,184230,047040" (CSV)
--      비어있으면 자동 매도 절대 안 함
--
-- 새 자동 청산 정책:
--   STUCK_BOT_POSITION 분류 = 알람만 (운영자에게 cleanup 후보 통지)
--   실제 매도 = (auto_cleanup_stuck_enabled = TRUE) AND (symbol IN stuck_cleanup_whitelist) 둘 다 만족
--
-- 사용자 워크플로:
--   1) PositionReconciler 로그/decision 에서 STUCK_BOT_POSITION 후보 확인
--   2) 본인 보유분과 봇 stuck 을 사용자가 직접 판단
--   3) 진짜 stuck 만 화이트리스트에 등록 (admin UI 또는 SQL UPDATE)
--   4) auto_cleanup_stuck_enabled = TRUE 로 설정
--   5) 다음 reconcile cycle 에서 화이트리스트 종목만 시장가 매도
--
-- 롤백:
--   ALTER TABLE krx_morning_rush_config DROP COLUMN stuck_cleanup_whitelist;
--   UPDATE krx_morning_rush_config SET auto_cleanup_stuck_enabled = TRUE WHERE id = 1;

ALTER TABLE krx_morning_rush_config ADD COLUMN stuck_cleanup_whitelist VARCHAR(500) NOT NULL DEFAULT '';

UPDATE krx_morning_rush_config SET
  auto_cleanup_stuck_enabled = FALSE,
  stuck_cleanup_whitelist = ''
WHERE id = 1;
