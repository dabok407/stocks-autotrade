-- 시간외 거래량 최소 기준 추가 (2026-04-11)
-- 시간외 등락률 +10%여도 거래량 1~50주면 "가짜 상한가" → 09:00 모멘텀 없음
-- 4/10 분석: WISCOM(1주), 이지트로닉스(39주) 등 거래량 미미 종목 탈락 필요
-- 기본값 10,000주: 엔피(114,151), 영화금속(59,862) 통과, WISCOM(1) 탈락
ALTER TABLE krx_morning_rush_config ADD COLUMN min_overtime_volume BIGINT DEFAULT 10000;
UPDATE krx_morning_rush_config SET min_overtime_volume = 10000 WHERE id = 1;
