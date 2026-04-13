-- E2E: 주문 금액 FIXED 100,000원, TP 0.3%, SL 0.5%
UPDATE krx_morning_rush_config SET
  order_sizing_mode = 'FIXED',
  order_sizing_value = 100000,
  tp_pct = 0.3,
  sl_pct = 0.5
WHERE id = 1;
