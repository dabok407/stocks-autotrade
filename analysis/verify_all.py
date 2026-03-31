import subprocess, json, time

ETF = ["122630","069500","252670","233740","396500","114800","229200","102110","379800","459580","360750","494310"]
STOCKS = ["000660","005930","046970","047040","091160","005380","000250","322000","263750","402340","373220","006400","032820","037030","034020","003280","066970"]
ALL = ETF + STOCKS

def bt(syms, strats, intv=5, f="2025-12-30", t="2026-03-31"):
    s_str = ",".join(['"%s"' % s for s in syms])
    st_str = ",".join(['"%s"' % s for s in strats])
    body = '{"symbols":[%s],"strategies":[%s],"candleUnitMin":%d,"fromDate":"%s","toDate":"%s","capitalKrw":1000000}' % (s_str, st_str, intv, f, t)
    r = subprocess.run(["curl","-s","--max-time","180","-X","POST",
        "http://localhost:8081/stocks/api/backtest/run",
        "-H","Content-Type: application/json","-d",body], capture_output=True, text=True)
    try: return json.loads(r.stdout)
    except: return {"totalTrades":0,"wins":0,"winRate":0,"roi":0,"totalPnl":0}

print("=" * 80)
print(" 국장 종합 검증 (3개월, 약 60거래일)")
print("=" * 80)

# 1. 단일 vs 복합
print("\n=== 1. 단일 vs 복합 (전체 29종목) ===")
print("%-32s %5s %6s %8s %10s %5s" % ("전략","거래","승률","ROI","PnL","일평균"))
print("-" * 72)

tests = [
    (["BOLLINGER_SQUEEZE_BREAKOUT"], "BOLLINGER 단독"),
    (["EMA_RSI_TREND"], "EMA_RSI 단독"),
    (["REGIME_PULLBACK"], "REGIME_PB 단독"),
    (["BOLLINGER_SQUEEZE_BREAKOUT","BEARISH_ENGULFING","THREE_BLACK_CROWS_SELL"], "BOLL+매도패턴"),
    (["EMA_RSI_TREND","BEARISH_ENGULFING","THREE_BLACK_CROWS_SELL"], "EMA+매도패턴"),
    (["BOLLINGER_SQUEEZE_BREAKOUT","EMA_RSI_TREND"], "BOLL+EMA"),
    (["BOLLINGER_SQUEEZE_BREAKOUT","EMA_RSI_TREND","BEARISH_ENGULFING","THREE_BLACK_CROWS_SELL"], "BOLL+EMA+매도"),
    (["EMA_RSI_TREND","REGIME_PULLBACK","BOLLINGER_SQUEEZE_BREAKOUT","BEARISH_ENGULFING","THREE_BLACK_CROWS_SELL"], "A+B+C+매도(이전최적)"),
]

for strats, label in tests:
    d = bt(ALL, strats)
    n = d.get("totalTrades",0)
    wr = d.get("winRate",0)
    roi = d.get("roi",0)
    pnl = d.get("totalPnl",0)
    daily = n / 60.0
    print("%-32s %5d %5.1f%% %+7.2f%% %+10.0f %5.1f" % (label, n, wr, roi, pnl, daily))
    time.sleep(1)

# 2. ETF 종목별
print("\n=== 2. ETF 종목별 (BOLLINGER 단독) ===")
etf_plus, etf_minus = [], []
for sym in ETF:
    d = bt([sym], ["BOLLINGER_SQUEEZE_BREAKOUT"])
    n = d.get("totalTrades",0)
    roi = d.get("roi",0)
    wr = d.get("winRate",0)
    if n > 0:
        print("  %s: %3d건 승률%5.1f%% ROI%+7.2f%%" % (sym, n, wr, roi))
        if roi > 0: etf_plus.append(sym)
        else: etf_minus.append(sym)
    time.sleep(0.3)

# 3. 일반주식 종목별
print("\n=== 3. 일반주식 종목별 (BOLLINGER 단독) ===")
stock_plus, stock_minus = [], []
for sym in STOCKS:
    d = bt([sym], ["BOLLINGER_SQUEEZE_BREAKOUT"])
    n = d.get("totalTrades",0)
    roi = d.get("roi",0)
    wr = d.get("winRate",0)
    if n > 0:
        print("  %s: %3d건 승률%5.1f%% ROI%+7.2f%%" % (sym, n, wr, roi))
        if roi > 0: stock_plus.append(sym)
        else: stock_minus.append(sym)
    time.sleep(0.3)

print("\n수익ETF(%d): %s" % (len(etf_plus), etf_plus))
print("손실ETF(%d): %s" % (len(etf_minus), etf_minus))
print("수익주식(%d): %s" % (len(stock_plus), stock_plus))
print("손실주식(%d): %s" % (len(stock_minus), stock_minus))

# 4. 수익 종목만으로 재검증
plus_all = etf_plus + stock_plus
if plus_all:
    print("\n=== 4. 수익 종목만 (%d개) BOLLINGER ===" % len(plus_all))
    d = bt(plus_all, ["BOLLINGER_SQUEEZE_BREAKOUT"])
    print("  %d종목: %d건, 승률%.1f%%, ROI%+.2f%%, PnL%+.0f" % (
        len(plus_all), d.get("totalTrades",0), d.get("winRate",0), d.get("roi",0), d.get("totalPnl",0)))

# 손실 종목 제외한 전체
minus_all = set(etf_minus + stock_minus)
filtered = [s for s in ALL if s not in minus_all]
if filtered:
    print("\n=== 5. 손실 종목 제외 (%d개) BOLLINGER ===" % len(filtered))
    d = bt(filtered, ["BOLLINGER_SQUEEZE_BREAKOUT"])
    print("  %d종목: %d건, 승률%.1f%%, ROI%+.2f%%, PnL%+.0f" % (
        len(filtered), d.get("totalTrades",0), d.get("winRate",0), d.get("roi",0), d.get("totalPnl",0)))
