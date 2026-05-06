-- V44 (2026-05-06): stuck 자동 청산 활성화 — 사용자 명시 3종목만
--
-- 사용자 요청: "봇에서 자동 청산될수있도록 진행해줘야지"
--
-- 화이트리스트 등록 근거 (KIS 90일 주문이력 직접 조회 결과):
--   - 073540 에프알텍   : 04-16 BUY 10주 체결, SELL @6120 → 체결량 0/10주 (미체결 stuck)
--   - 184230 SGA솔루션즈: 04-17 BUY 46주 체결, SELL @1047 → 체결량 0/46주 (미체결 stuck)
--   - 047040 대우건설   : 05-04 BUY  1주 체결, SELL @37150→ 체결량 0/1주  (미체결 stuck)
--
-- 화이트리스트 의도적 제외:
--   - 005880 대한해운 24주 (avg 3057): 04-16 BUY 24/SELL 24 모두 정상 체결 완료.
--     현재 24주는 봇 거래가(2740/2795) 와 다른 평단(3057) — 사용자가 별도 시점 추가 매수한
--     본인 보유분으로 판단. 절대 봇이 매도하면 안 됨.
--   - 005930 삼성전자, 012330 현대모비스, 036030 케이티알파: trade_log 에 봇 BUY 이력 자체 없음.
--
-- 자동 청산 흐름 (다음 거래일 09:00 KST):
--   1. PositionReconciler 1분 cycle 가동
--   2. KIS 잔고 조회 → ORPHAN_BROKER 7건 검출
--   3. trade_log 에 봇 BUY 이력 있는 4건 → STUCK_BOT_POSITION 분류
--   4. 화이트리스트 검증: 073540, 184230, 047040 만 통과 (005880 차단)
--   5. 시장 시간 체크 OK + LIVE 모드 OK + API 설정 OK
--   6. attemptedCleanupSymbols 체크 OK (첫 시도)
--   7. liveOrders.placeSellOrder(symbol, KRX, qty, 0.0, "01") — 시장가 매도
--   8. P0-Fix#1 의 inquire-daily-ccld 폴링으로 실제 체결 검증
--   9. 체결 성공 시 trade_log 에 SELL 기록 (RECONCILE_STUCK_CLEANUP)
--
-- 예상 회수 자금:
--   - 073540: 10주 × 시장가 (현재 ~3,965) ≈ 39,650 원
--   - 184230: 46주 × 시장가 (현재 ~754)  ≈ 34,684 원
--   - 047040:  1주 × 시장가 (현재 ~32,250) ≈ 32,250 원
--   - 합계: 약 106,584 원 (각 종목 손실 실현 + 회수)
--
-- 안전장치 6중:
--   1) auto_cleanup_stuck_enabled = TRUE (master switch)
--   2) stuck_cleanup_whitelist = '073540,184230,047040' (명시 등록만)
--   3) trade_log BUY 이력 검증 (사용자 본인 매수 차단)
--   4) 시장 시간 09:00-15:30 KST 만
--   5) LIVE 모드 + KIS API 설정 확인
--   6) attemptedCleanupSymbols Set — 같은 세션 반복 방지
--
-- 롤백 (자동청산 비활성화 — 매도 멈추기):
--   UPDATE krx_morning_rush_config SET
--     auto_cleanup_stuck_enabled = FALSE,
--     stuck_cleanup_whitelist = ''
--   WHERE id = 1;

UPDATE krx_morning_rush_config SET
  stuck_cleanup_whitelist = '073540,184230,047040',
  auto_cleanup_stuck_enabled = TRUE
WHERE id = 1;
