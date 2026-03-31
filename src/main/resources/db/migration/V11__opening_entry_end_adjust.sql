-- V11: 오프닝 진입 종료 시간 조정 (마지막 캔들 체결 시점 고려)
-- KRX: 10:00 → 09:55 (마지막 진입 캔들 09:55, 체결 10:00)
-- NYSE: 11:00 → 10:55 (마지막 진입 캔들 10:55, 체결 11:00)

UPDATE krx_opening_config SET entry_end_hour = 9, entry_end_min = 55 WHERE id = 1;
UPDATE nyse_opening_config SET entry_end_hour = 10, entry_end_min = 55 WHERE id = 1;
