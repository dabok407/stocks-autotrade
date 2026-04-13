-- 모닝러쉬 주문 방식: FIXED 100,000 → PCT 33% (capital의 33%, 사용자 요청 2026-04-09)
UPDATE krx_morning_rush_config SET order_sizing_mode = 'PCT', order_sizing_value = 33 WHERE id = 1;
