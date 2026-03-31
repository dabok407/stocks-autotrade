-- V12: NYSE 스캐너 비활성화 + KRX 손실 종목 제외 추가

-- NYSE 스캐너 비활성화
UPDATE nyse_opening_config SET enabled = false WHERE id = 1;
UPDATE nyse_allday_config SET enabled = false WHERE id = 1;
UPDATE nyse_morning_rush_config SET enabled = false WHERE id = 1;

-- KRX 스캐너: 보유종목 + 인버스ETF만 제외 (전문가 권고: 나머지 6종목은 과적합 위험으로 포함 유지)
UPDATE krx_opening_config SET exclude_symbols = '005930,012330,252670,114800' WHERE id = 1;
UPDATE krx_allday_config SET exclude_symbols = '005930,012330,252670,114800' WHERE id = 1;
