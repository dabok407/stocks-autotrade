-- V40: TP_TRAIL 단독 운영 전환 + WebSocket 단독 SL + SL -2.4% (2026-04-20)
-- 2026-04-20 운영 손실 4건/수익 3건 분석 결과 반영.
--
-- 배경:
--   4/20 거래 7건 중 4건 손실(승률 43%, 순손익 -1,400원).
--   - SPLIT_1ST 전량 청산 이슈: qty=3/17/3 → 40% 부분청산 안 되고 전량 매도되어 수익 상한 +1.6% 고정.
--   - SL -2.0% 과민: 456010 최저 -2.42%만 찍고 -1.30% 회복했으나 -2.05%에서 컷.
--   - REST monitorPositions SL 판정: Grace 종료 직후 WS 틱보다 5초 폴링이 먼저 -3% 포착하여 매도.
--   - entry_delay_sec=30: 09:00:30부터 스캔 → 09:00부터 즉시 진입 요구.
--
-- 변경:
--   - split_exit_enabled: true → false (TP_TRAIL 단독, 전량 매도)
--   - sl_pct / wide_sl_pct: 2.0 → 2.4 (V자 반등 구간 보존)
--   - grace_period_sec: 45 → 60 (초기 변동성 보호 확대)
--   - entry_delay_sec: 30 → 0 (09:00 시초가부터 즉시 스캔)
--
-- 병행 코드 변경:
--   - KrxMorningRushService.monitorPositions(): SL/TP 판정 블록 제거 → TIME_STOP만 남김.
--     WebSocket realtime(checkRealtimeTpSl)이 SL/TP 단독 책임.
--
-- 롤백:
--   UPDATE krx_morning_rush_config SET
--     split_exit_enabled = TRUE, sl_pct = 2.0, wide_sl_pct = 2.0,
--     grace_period_sec = 45, entry_delay_sec = 30
--   WHERE id = 1;

UPDATE krx_morning_rush_config SET
  split_exit_enabled = FALSE,
  sl_pct = 2.4,
  wide_sl_pct = 2.4,
  grace_period_sec = 60,
  entry_delay_sec = 0
WHERE id = 1;
