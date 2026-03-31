import csv, sys
from collections import defaultdict
from datetime import datetime, timedelta

# 1. 변동성 비교
print("=" * 75)
print(" 미장 vs 국장 변동성 비교")
print("=" * 75)

krx_ranges, nyse_ranges = [], []
with open("/tmp/krx_top30_5min_yahoo.csv") as f:
    for row in csv.DictReader(f):
        v = int(row["volume"]) if row["volume"] else 0
        if v == 0: continue
        h, l, c = float(row["high"]), float(row["low"]), float(row["close"])
        if c > 0: krx_ranges.append((h-l)/c*100)

with open("/tmp/nyse_top30_5min_yahoo.csv") as f:
    for row in csv.DictReader(f):
        v = int(row["volume"]) if row["volume"] else 0
        if v == 0: continue
        h, l, c = float(row["high"]), float(row["low"]), float(row["close"])
        if c > 0: nyse_ranges.append((h-l)/c*100)

krx_avg = sum(krx_ranges)/len(krx_ranges) if krx_ranges else 0
nyse_avg = sum(nyse_ranges)/len(nyse_ranges) if nyse_ranges else 0
print("5분봉 평균 변동성: 국장 %.3f%% / 미장 %.3f%% / 비율 %.1fx" % (
    krx_avg, nyse_avg, krx_avg/nyse_avg if nyse_avg > 0 else 0))

# 2. 60분봉 스윙 시뮬레이션
print("\n" + "=" * 75)
print(" 미장 60분봉 스윙 전략 시뮬레이션 (30종목 x 3개월)")
print("=" * 75)

# 세션별 그룹핑 (22:00~06:00 = 하나의 세션)
sessions = defaultdict(list)
with open("/tmp/nyse_top30_5min_yahoo.csv") as f:
    for row in csv.DictReader(f):
        sym, ts = row["symbol"], row["timestamp"]
        hm = ts[11:16]; date = ts[:10]
        v = int(row["volume"]) if row["volume"] else 0
        if v == 0: continue
        if hm >= "22:00":
            sk = sym + "|" + date
        elif hm < "06:00":
            d = datetime.strptime(date, "%Y-%m-%d")
            sk = sym + "|" + (d - timedelta(days=1)).strftime("%Y-%m-%d")
        else: continue
        sessions[sk].append({
            "time": hm, "open": float(row["open"]), "high": float(row["high"]),
            "low": float(row["low"]), "close": float(row["close"]), "vol": v})

for k in sessions:
    sessions[k].sort(key=lambda x: (0 if x["time"] >= "22:00" else 1, x["time"]))

def to_60min(candles):
    hourly = []
    bkt = None
    for c in candles:
        hk = c["time"][:2]
        if bkt is None or bkt["hk"] != hk:
            if bkt: hourly.append(bkt)
            bkt = {"hk": hk, "time": c["time"], "open": c["open"],
                   "high": c["high"], "low": c["low"], "close": c["close"], "vol": c["vol"]}
        else:
            bkt["high"] = max(bkt["high"], c["high"])
            bkt["low"] = min(bkt["low"], c["low"])
            bkt["close"] = c["close"]
            bkt["vol"] += c["vol"]
    if bkt: hourly.append(bkt)
    return hourly

def ema(prices, period):
    if len(prices) < period: return prices[-1] if prices else 0
    m = 2.0/(period+1); v = sum(prices[:period])/period
    for p in prices[period:]: v = p*m + v*(1-m)
    return v

def rsi(prices, period=14):
    if len(prices) < period+1: return 50
    g = [max(0, prices[i]-prices[i-1]) for i in range(1, len(prices))]
    l = [max(0, prices[i-1]-prices[i]) for i in range(1, len(prices))]
    ag = sum(g[-period:])/period; al = sum(l[-period:])/period
    return 100 - (100/(1+ag/al)) if al > 0 else 100

FEE = 0.002
by_sym = defaultdict(list)
for k, candles in sessions.items():
    sym = k.split("|")[0]
    by_sym[sym].append(candles)

# 전략1: 모멘텀 (EMA8>EMA21 + RSI 40~70 + 양봉 + vol)
print("\n=== 전략1: 모멘텀 스윙 (60분봉) ===")
print("%-14s %4s %6s %8s %4s %4s" % ("TP/SL", "거래", "승률", "총PnL", "TP", "SL"))
for tp in [2.0, 3.0, 5.0, 7.0]:
    for sl in [1.5, 2.0, 3.0, 5.0]:
        trades = []
        for sym in by_sym:
            all_c = []
            for sess in sorted(by_sym[sym], key=lambda x: x[0]["time"] if x else ""):
                all_c.extend(sess)
            h60 = to_60min(all_c)
            if len(h60) < 21: continue
            for i in range(21, len(h60)):
                c = h60[i]
                rc = [h60[j]["close"] for j in range(max(0,i-21),i+1)]
                e8=ema(rc,8); e21=ema(rc,21); r=rsi(rc)
                if not (e8>e21 and 40<=r<=70 and c["close"]>c["open"]): continue
                rv = [h60[j]["vol"] for j in range(max(0,i-10),i)]
                av = sum(rv)/max(1,len(rv))
                if av>0 and c["vol"]<av*1.5: continue
                ep=c["close"]; res="TIME"; xp=ep
                for j in range(i+1, min(i+10, len(h60))):
                    if h60[j]["high"]>=ep*(1+tp/100): xp=ep*(1+tp/100); res="TP"; break
                    if h60[j]["low"]<=ep*(1-sl/100): xp=ep*(1-sl/100); res="SL"; break
                if res=="TIME" and i+10<len(h60): xp=h60[min(i+10,len(h60)-1)]["close"]
                elif res=="TIME": continue
                trades.append({"pnl":round((xp/ep-1)*100-FEE*100,2), "r":res})
        n=len(trades)
        if n<3: continue
        w=sum(1 for t in trades if t["pnl"]>0)
        tot=sum(t["pnl"] for t in trades)
        tc=sum(1 for t in trades if t["r"]=="TP")
        sc=sum(1 for t in trades if t["r"]=="SL")
        print("TP%.0f%%/SL%.0f%%   %4d %5.1f%% %+7.1f%% %4d %4d" % (tp,sl,n,w/n*100,tot,tc,sc))

# 전략2: 추세 풀백 (EMA20>EMA50 + RSI 30~45)
print("\n=== 전략2: 추세 풀백 스윙 (60분봉) ===")
print("%-14s %4s %6s %8s" % ("TP/SL", "거래", "승률", "총PnL"))
for tp in [3.0, 5.0, 7.0, 10.0]:
    for sl in [2.0, 3.0, 5.0]:
        trades = []
        for sym in by_sym:
            all_c = []
            for sess in sorted(by_sym[sym], key=lambda x: x[0]["time"] if x else ""):
                all_c.extend(sess)
            h60 = to_60min(all_c)
            if len(h60) < 50: continue
            for i in range(50, len(h60)):
                c = h60[i]
                rc = [h60[j]["close"] for j in range(max(0,i-50),i+1)]
                e20=ema(rc,20); e50=ema(rc,50); r=rsi(rc)
                if not (e20>e50 and 30<=r<=45 and c["close"]>c["open"]): continue
                ep=c["close"]; res="TIME"; xp=ep
                for j in range(i+1, min(i+20, len(h60))):
                    if h60[j]["high"]>=ep*(1+tp/100): xp=ep*(1+tp/100); res="TP"; break
                    if h60[j]["low"]<=ep*(1-sl/100): xp=ep*(1-sl/100); res="SL"; break
                if res=="TIME" and i+20<len(h60): xp=h60[min(i+20,len(h60)-1)]["close"]
                elif res=="TIME": continue
                trades.append({"pnl":round((xp/ep-1)*100-FEE*100,2), "r":res})
        n=len(trades)
        if n<3: continue
        w=sum(1 for t in trades if t["pnl"]>0)
        tot=sum(t["pnl"] for t in trades)
        print("TP%.0f%%/SL%.0f%%   %4d %5.1f%% %+7.1f%%" % (tp,sl,n,w/n*100,tot))

# 전략3: 볼린저 돌파 (60분봉)
print("\n=== 전략3: 볼린저 돌파 스윙 (60분봉) ===")
print("%-14s %4s %6s %8s" % ("TP/SL", "거래", "승률", "총PnL"))
for tp in [2.0, 3.0, 5.0]:
    for sl in [1.5, 2.0, 3.0]:
        trades = []
        for sym in by_sym:
            all_c = []
            for sess in sorted(by_sym[sym], key=lambda x: x[0]["time"] if x else ""):
                all_c.extend(sess)
            h60 = to_60min(all_c)
            if len(h60) < 20: continue
            for i in range(20, len(h60)):
                c = h60[i]
                rc = [h60[j]["close"] for j in range(i-20,i+1)]
                sma = sum(rc)/len(rc)
                std = (sum((x-sma)**2 for x in rc)/len(rc))**0.5
                upper = sma + 2*std
                if c["close"] <= upper or c["close"] <= c["open"]: continue
                rv = [h60[j]["vol"] for j in range(max(0,i-10),i)]
                av = sum(rv)/max(1,len(rv))
                if av>0 and c["vol"]<av*1.3: continue
                ep=c["close"]; res="TIME"; xp=ep
                for j in range(i+1, min(i+10, len(h60))):
                    if h60[j]["high"]>=ep*(1+tp/100): xp=ep*(1+tp/100); res="TP"; break
                    if h60[j]["low"]<=ep*(1-sl/100): xp=ep*(1-sl/100); res="SL"; break
                if res=="TIME" and i+10<len(h60): xp=h60[min(i+10,len(h60)-1)]["close"]
                elif res=="TIME": continue
                trades.append({"pnl":round((xp/ep-1)*100-FEE*100,2), "r":res})
        n=len(trades)
        if n<3: continue
        w=sum(1 for t in trades if t["pnl"]>0)
        tot=sum(t["pnl"] for t in trades)
        print("TP%.0f%%/SL%.0f%%   %4d %5.1f%% %+7.1f%%" % (tp,sl,n,w/n*100,tot))
