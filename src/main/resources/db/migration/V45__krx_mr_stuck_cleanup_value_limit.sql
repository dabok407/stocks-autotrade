-- V45 (2026-05-14): STUCK 자동청산 + QTY_MISMATCH 자동동기화 활성화.
--
-- 배경 (2026-05-14 운영 사고):
--   - 222420 쎄노텍: 봇 BUY 78주 → broker 156주 (분할체결, 봇 DB 78주만 인식).
--                    SESSION_END forceExitAll 매도 78주 후 broker 78주 잔량 STUCK.
--   - 184230 SGA솔루션즈, 005880 대한해운(?) 등 4/17 이후 STUCK 누적.
--
-- 변경:
--   1) stuck_cleanup_max_value_krw 컬럼 추가 (default 500,000원).
--      hasBotBuyHistory==true 종목 중 (qty × avg) ≤ 한도 이면 화이트리스트 없이 자동매도.
--      큰 자산은 화이트리스트 명시 필요 (오작동 시 손해 제한).
--   2) qty_mismatch_auto_sync_enabled 컬럼 추가 (default TRUE).
--      brokerQty > dbQty + hasBotBuyHistory 인 경우 봇 DB qty → broker qty 동기화.
--      분할체결로 봇이 일부만 인식한 케이스 자동 복구.
--
-- 안전장치 (기존 + 추가):
--   1) auto_cleanup_stuck_enabled = TRUE (master switch)
--   2) hasBotBuyHistory 검증 (봇 BUY 이력 없으면 사용자 본인 매수로 판정 → 매도 안 함)
--   3) (qty × avgPrice) ≤ 500,000원 (V45 신규: 큰 자산 보호)
--   4) 시장 시간 09:00-15:30, LIVE 모드, API 설정 확인 (기존)
--   5) attemptedCleanupSymbols Set — 같은 세션 반복 방지 (기존)
--
-- 롤백:
--   UPDATE krx_morning_rush_config SET
--     stuck_cleanup_max_value_krw = 0,
--     qty_mismatch_auto_sync_enabled = FALSE
--   WHERE id = 1;
--   ALTER TABLE krx_morning_rush_config DROP COLUMN stuck_cleanup_max_value_krw;
--   ALTER TABLE krx_morning_rush_config DROP COLUMN qty_mismatch_auto_sync_enabled;

ALTER TABLE krx_morning_rush_config
  ADD COLUMN IF NOT EXISTS stuck_cleanup_max_value_krw BIGINT NOT NULL DEFAULT 500000;

ALTER TABLE krx_morning_rush_config
  ADD COLUMN IF NOT EXISTS qty_mismatch_auto_sync_enabled BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE krx_morning_rush_config SET
  stuck_cleanup_max_value_krw = 500000,
  qty_mismatch_auto_sync_enabled = TRUE
WHERE id = 1;
