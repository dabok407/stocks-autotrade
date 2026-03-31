-- V9: 오프닝 스캐너 진입 시작 시간 앞당기기
-- 근거: 모멘텀 전략은 레인지 수집이 불필요, 전일 캔들로 EMA50 충분히 확보
-- 장 시작 직후 5분 노이즈 회피 후 즉시 진입
-- 백테스트 결과 모멘텀(E안) 승률 65.5%로 레인지 돌파 대비 압도적

-- KRX 오프닝: 09:20 → 09:05
UPDATE krx_opening_config SET entry_start_hour = 9, entry_start_min = 5 WHERE id = 1;

-- NYSE 오프닝: ET 10:05 → 09:35 (개장 09:30 + 5분)
UPDATE nyse_opening_config SET entry_start_hour = 9, entry_start_min = 35 WHERE id = 1;
