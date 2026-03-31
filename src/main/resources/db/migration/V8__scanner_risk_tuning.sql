-- V8: 스캐너 리스크 파라미터 최적화 (전문가 점검 반영)
-- 1. KRX AllDay Quick TP: 0.6% → 1.0% (수수료+세금 0.49% 고려)
-- 2. KRX Morning Rush TP/SL: 대칭 1.2:1.2 → 비대칭 1.5:1.0 (RR=1.5:1)
-- 3. NYSE Morning Rush TP/SL: 2.0:1.5 → 2.5:1.5 (RR 개선)
-- 4. NYSE Opening orderSizingValue: 45% → 35% (집중 리스크 완화)

-- KRX AllDay: Quick TP 상향
UPDATE krx_allday_config SET quick_tp_pct = 1.0 WHERE id = 1 AND quick_tp_pct = 0.6;

-- KRX Morning Rush: 비대칭 TP/SL
UPDATE krx_morning_rush_config SET tp_pct = 1.5, sl_pct = 1.0 WHERE id = 1 AND tp_pct = 1.2 AND sl_pct = 1.2;

-- NYSE Morning Rush: TP 상향
UPDATE nyse_morning_rush_config SET tp_pct = 2.5 WHERE id = 1 AND tp_pct = 2.0;

-- NYSE Opening: 주문 비중 완화
UPDATE nyse_opening_config SET order_sizing_value = 35.0 WHERE id = 1 AND order_sizing_value = 45.0;
