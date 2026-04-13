-- ===========================================
-- V29: KRX 모닝러쉬 주문 금액 3만원 → 10만원
-- ===========================================
-- 사용자 요청 (2026-04-09): 3만원은 너무 작아 10만원으로 상향
-- FIXED 모드로 명시 (PCT 모드 + bot_config capital 의존성 회피)
-- ===========================================

UPDATE krx_morning_rush_config SET
  order_sizing_mode = 'FIXED',
  order_sizing_value = 100000
WHERE id = 1;
