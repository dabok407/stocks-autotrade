-- V37: 거래 심볼 -> 한글명 영속 캐시 (2026-04-16)
-- KIS inquire-price의 hts_kor_isnm 조회 결과를 저장해 재시작해도 유지.

CREATE TABLE IF NOT EXISTS symbol_name_cache (
  symbol      VARCHAR(20)  NOT NULL PRIMARY KEY,
  market_type VARCHAR(10)  NOT NULL,
  name        VARCHAR(100) NOT NULL,
  updated_at  BIGINT       NOT NULL
);
