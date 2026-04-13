-- ===========================================
-- V30: 시간외거래순위 일일 수집 로그 테이블
-- ===========================================
-- 매일 18:05 KST에 KIS API(FHPST02340000) 시간외 등락률 순위 조회 후 저장.
-- 다음날 08:50 collectRange()에서 이 테이블 로드하여 모닝러쉬 target 선정.
-- 30일 누적 후 백테스트 가능.
-- ===========================================

CREATE TABLE krx_overtime_rank_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_date      DATE NOT NULL,              -- 거래일 (KST yyyy-MM-dd)
    collected_at    TIMESTAMP NOT NULL,          -- 수집 시각
    rank_no         INT NOT NULL,                -- 순위 (1~30)
    symbol          VARCHAR(20) NOT NULL,        -- 종목코드 (e.g. 005930)
    symbol_name     VARCHAR(50),                 -- 종목명
    current_price   DECIMAL(15,2),               -- 시간외 현재가
    change_pct      DECIMAL(8,4),                -- 등락률 %
    volume          BIGINT,                      -- 거래량
    trade_amount    BIGINT,                      -- 거래대금
    raw_json        TEXT                          -- 원본 API 응답 (디버깅)
);

CREATE INDEX idx_overtime_trade_date ON krx_overtime_rank_log(trade_date);
CREATE INDEX idx_overtime_symbol ON krx_overtime_rank_log(symbol);
CREATE UNIQUE INDEX uq_overtime_date_rank ON krx_overtime_rank_log(trade_date, rank_no);
