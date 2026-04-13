-- ===========================================
-- V28: KRX 모닝러쉬 LIVE 복원 (긴급)
-- ===========================================
-- 사고 배경:
--   V27__e2e_round2.sql (Apr 7 12:06)이 PAPER 모드 + session_end 12:35 +
--   gap_threshold -10% + tp 0.3% / sl 0.5% 로 변경 후 운영에 남아있음.
--   며칠째 모닝러쉬 LIVE 매수 미동작 → 즉시 정상값 복원.
--
-- 또한 enabled = true 명시 보장 (V27 이전부터 enabled 미설정 위험 차단)
-- exclude_symbols 보유 종목 강제 제외 (005930 삼성전자, 012330 현대모비스)
-- ===========================================

UPDATE krx_morning_rush_config SET
  enabled = TRUE,
  mode = 'LIVE',
  gap_threshold_pct = 2.0,
  volume_mult = 3.0,
  confirm_count = 3,
  tp_pct = 1.5,
  sl_pct = 1.0,
  entry_delay_sec = 30,
  session_end_hour = 10,
  session_end_min = 0,
  max_positions = 3,
  order_sizing_mode = 'PCT',
  order_sizing_value = 5,
  time_stop_min = 30,
  min_trade_amount = 5000000000,
  min_price_krw = 1000,
  exclude_symbols = '005930,012330',
  top_n = 15
WHERE id = 1;

-- 검증 쿼리 (배포 후 H2 콘솔에서 실행)
-- SELECT enabled, mode, gap_threshold_pct, volume_mult, confirm_count,
--        tp_pct, sl_pct, session_end_hour, session_end_min, exclude_symbols
-- FROM krx_morning_rush_config WHERE id = 1;
