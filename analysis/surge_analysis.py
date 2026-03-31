import csv, sys
from collections import defaultdict

data = defaultdict(lambda: defaultdict(list))
with open("/tmp/coin_top30_5min_10d.csv") as f:
    reader = csv.DictReader(f)
    for row in reader:
        sym = row["symbol"]
        ts = row["timestamp"]
        date = ts[:10]
        hm = ts[11:16]
        data[sym][date].append({
            "time": hm, "open": float(row["open"]), "high": float(row["high"]),
            "low": float(row["low"]), "close": float(row["close"]),
            "vol": float(row["volume"]),
        })

for sym in data:
    for d in data[sym]:
        data[sym][d].sort(key=lambda x: x["time"])

surges = []
for sym in sorted(data.keys()):
    for date in sorted(data[sym].keys()):
        day = data[sym][date]
        if len(day) < 20: continue
        for i in range(5, len(day)):
            c = day[i]
            prev_closes = [day[j]["close"] for j in range(max(0, i-5), i)]
            if not prev_closes: continue
            prev_avg = sum(prev_closes) / len(prev_closes)
            if prev_avg <= 0: continue
            surge_pct = (c["close"] - prev_avg) / prev_avg * 100
            if surge_pct >= 3.0:
                prev_vols = [day[j]["vol"] for j in range(max(0, i-20), i)]
                avg_vol = sum(prev_vols) / max(1, len(prev_vols))
                vol_ratio = c["vol"] / avg_vol if avg_vol > 0 else 0
                after_price = None
                for j in range(i+1, min(i+7, len(day))):
                    after_price = day[j]["close"]
                after_pct = ((after_price - c["close"]) / c["close"] * 100) if after_price else None
                after_60_price = None
                for j in range(i+1, min(i+13, len(day))):
                    after_60_price = day[j]["close"]
                after_60_pct = ((after_60_price - c["close"]) / c["close"] * 100) if after_60_price else None
                surges.append({
                    "sym": sym.replace("KRW-",""), "date": date, "time": c["time"],
                    "surge": round(surge_pct, 2), "vol_r": round(vol_ratio, 1),
                    "after_30m": round(after_pct, 2) if after_pct is not None else None,
                    "after_60m": round(after_60_pct, 2) if after_60_pct is not None else None,
                })

print("=" * 80)
print(" TOP 30 코인 급등 패턴 분석 (10일, 87,000 캔들)")
print("=" * 80)
print("총 급등 이벤트 (25분간 3%%+): %d건" % len(surges))

morning = [s for s in surges if "08:30" <= s["time"] <= "09:30"]
midday = [s for s in surges if "09:30" < s["time"] <= "12:00"]
afternoon = [s for s in surges if "12:00" < s["time"] <= "18:00"]
evening = [s for s in surges if "18:00" < s["time"]]

print("\n시간대별:")
print("  오프닝(08:30~09:30): %d건" % len(morning))
print("  오전(09:30~12:00): %d건" % len(midday))
print("  오후(12:00~18:00): %d건" % len(afternoon))
print("  저녁(18:00~): %d건" % len(evening))

print("\n" + "=" * 80)
print(" 오프닝 급등 상세")
print("=" * 80)
for s in sorted(morning, key=lambda x: (x["date"], x["time"])):
    a30 = "%+.2f%%" % s["after_30m"] if s["after_30m"] is not None else "N/A"
    a60 = "%+.2f%%" % s["after_60m"] if s["after_60m"] is not None else "N/A"
    print("  %s %-8s %s %+.2f%% vol=%.1fx -> 30m:%s 60m:%s" % (s["date"], s["sym"], s["time"], s["surge"], s["vol_r"], a30, a60))

print("\n" + "=" * 80)
print(" 급등 후 수익성")
print("=" * 80)
for label, group in [("오프닝", morning), ("오전", midday), ("오후", afternoon), ("저녁", evening)]:
    if not group: continue
    v30 = [s for s in group if s["after_30m"] is not None]
    v60 = [s for s in group if s["after_60m"] is not None]
    if v30:
        w = sum(1 for s in v30 if s["after_30m"] > 0)
        a = sum(s["after_30m"] for s in v30) / len(v30)
        print("  %s 30분후: %d건 | 상승확률 %d%% | 평균 %+.2f%%" % (label, len(v30), w*100//len(v30), a))
    if v60:
        w = sum(1 for s in v60 if s["after_60m"] > 0)
        a = sum(s["after_60m"] for s in v60) / len(v60)
        print("  %s 60분후: %d건 | 상승확률 %d%% | 평균 %+.2f%%" % (label, len(v60), w*100//len(v60), a))

print("\n" + "=" * 80)
print(" gap 기준별 모닝러쉬 시뮬 (오프닝 시간대)")
print("=" * 80)
for gap in [2.0, 3.0, 4.0, 5.0, 7.0, 10.0]:
    f = [s for s in morning if s["surge"] >= gap and s["after_30m"] is not None]
    if not f:
        print("  gap >= %d%%: 0건" % gap)
        continue
    w = sum(1 for s in f if s["after_30m"] > 0)
    a = sum(s["after_30m"] for s in f) / len(f)
    print("  gap >= %d%%: %d건 | 30분후 상승 %d%% | 평균 %+.2f%%" % (gap, len(f), w*100//len(f), a))

print("\n" + "=" * 80)
print(" 전체 급등 TOP 20")
print("=" * 80)
for s in sorted(surges, key=lambda x: x["surge"], reverse=True)[:20]:
    a30 = "%+.2f%%" % s["after_30m"] if s["after_30m"] is not None else "N/A"
    a60 = "%+.2f%%" % s["after_60m"] if s["after_60m"] is not None else "N/A"
    print("  %s %-8s %s %+.2f%% vol=%.1fx -> 30m:%s 60m:%s" % (s["date"], s["sym"], s["time"], s["surge"], s["vol_r"], a30, a60))

# 패턴: vol 높으면 급등 후 유지되는지?
print("\n" + "=" * 80)
print(" vol 기준별 급등 후 30분 유지율")
print("=" * 80)
for vol_th in [1.0, 2.0, 3.0, 5.0, 10.0]:
    f = [s for s in surges if s["vol_r"] >= vol_th and s["after_30m"] is not None]
    if not f: continue
    w = sum(1 for s in f if s["after_30m"] > 0)
    a = sum(s["after_30m"] for s in f) / len(f)
    print("  vol >= %.0fx: %d건 | 유지율 %d%% | 30분후 평균 %+.2f%%" % (vol_th, len(f), w*100//len(f), a))
