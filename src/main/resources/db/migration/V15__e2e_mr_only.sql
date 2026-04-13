-- V15: E2E 테스트 — 모닝러쉬만 ON, 나머지 OFF
UPDATE krx_opening_config SET enabled = false WHERE id = 1;
UPDATE krx_allday_config SET enabled = false WHERE id = 1;
UPDATE krx_morning_rush_config SET session_end_hour = 15, session_end_min = 0 WHERE id = 1;
