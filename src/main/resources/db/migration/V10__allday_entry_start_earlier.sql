-- V10: 올데이 스캐너 진입 시작 앞당기기
-- 오프닝 종료(10:00) + 5분 갭 → 10:05 진입 시작
-- 기존 35분 갭(10:35)은 불필요 (scanner_source로 포지션 구분됨)

-- KRX 올데이: 10:35 → 10:05
UPDATE krx_allday_config SET entry_start_hour = 10, entry_start_min = 5 WHERE id = 1;

-- NYSE 올데이: ET 11:30 → ET 09:40 (오프닝 종료 09:35 + 5분)
-- 단, NYSE 오프닝 entry_end가 ET 11:00이므로 11:05로 조정
UPDATE nyse_allday_config SET entry_start_hour = 11, entry_start_min = 5 WHERE id = 1;
