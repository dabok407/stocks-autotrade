-- V41 (2026-05-06): P2 리스크 가드 추가
--
-- 배경:
--   2026-05-06 분석에서 04-21 손실 -4.46%, 4종목 stuck (-36/-30/-2.7%) 확인.
--   05-06: 322310 매수 시도 3회 모두 "주문가능금액 초과" → stuck 자금 묶임 → 매수 회전율 마비.
--
-- 변경:
--   1) max_positions: 3 → 2 (자금 부족 빈발 → 동시 보유 종목 줄여 슬롯당 자금 확보)
--   2) daily_loss_limit_pct = -2.0 (NEW) — 당일 실현 PnL 합계가 BotConfig.capitalKrw 의 -2.0% 이하면
--      해당 거래일 추가 매수 차단. 04-21 -4.46% 손실 → -2% 근방에서 정지.
--   3) reserve_krw = 30000 (NEW) — 비상 매도 수수료 버퍼. 가용 예수금 - 30000 까지만 매수.
--
-- 의도적으로 빠진 항목:
--   - stale_hold_days 자동청산: 봇 DB 와 KIS 잔고가 일치하지 않으면 사용자 본인 보유와 봇 stuck 구분 불가.
--     P0-Fix#3 PositionReconciler 가 1분 주기 알람만 — 강제 청산은 사용자 수동 조치.
--
-- 롤백:
--   ALTER TABLE krx_morning_rush_config DROP COLUMN daily_loss_limit_pct;
--   ALTER TABLE krx_morning_rush_config DROP COLUMN reserve_krw;
--   UPDATE krx_morning_rush_config SET max_positions = 3 WHERE id = 1;

ALTER TABLE krx_morning_rush_config ADD COLUMN daily_loss_limit_pct DECIMAL(5,2) NOT NULL DEFAULT -2.0;
ALTER TABLE krx_morning_rush_config ADD COLUMN reserve_krw BIGINT NOT NULL DEFAULT 30000;

UPDATE krx_morning_rush_config SET
  max_positions = 2,
  daily_loss_limit_pct = -2.0,
  reserve_krw = 30000
WHERE id = 1;
