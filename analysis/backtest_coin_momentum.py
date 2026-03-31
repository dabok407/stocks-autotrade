#!/usr/bin/env python3
"""
코인봇 오프닝 전략 비교: 레인지 돌파 vs 모멘텀 스코어링
업비트 5분봉 데이터 기반
"""
import csv
import json
import urllib.request
import time
from collections import defaultdict, OrderedDict

def fetch_upbit_candles(market, interval=5, count=200):
    """업비트 API로 분봉 캔들 조회 (최대 200개)"""
    url = f"https://api.upbit.com/v1/candles/minutes/{interval}?market={market}&count={count}"
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode())
            # 업비트: 최신→과거 순 반환, 역순 정렬
            data.reverse()
            return data
    except Exception as e:
        print(f"  API error {market}: {e}")
        return []

def get_top_markets(count=30):
    """업비트 거래대금 상위 마켓"""
    url = "https://api.upbit.com/v1/ticker?markets=" + ",".join([
        "KRW-BTC","KRW-ETH","KRW-XRP","KRW-SOL","KRW-DOGE","KRW-ADA","KRW-AVAX",
        "KRW-LINK","KRW-DOT","KRW-MATIC","KRW-ATOM","KRW-ETC","KRW-NEAR","KRW-UNI",
        "KRW-AAVE","KRW-FIL","KRW-APT","KRW-ARB","KRW-OP","KRW-IMX","KRW-STX",
        "KRW-SUI","KRW-SEI","KRW-TIA","KRW-MANA","KRW-SAND","KRW-AXS","KRW-ENS",
        "KRW-CRV","KRW-COMP","KRW-ZRX","KRW-BAT","KRW-CHZ","KRW-ONT","KRW-BSV",
        "KRW-KITE","KRW-HBAR","KRW-ALGO","KRW-FLOW","KRW-THETA"
    ])
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode())
            # 거래대금순 정렬
            data.sort(key=lambda x: x.get('acc_trade_price_24h', 0), reverse=True)
            # 보유종목 제외
            exclude = {'KRW-BTC','KRW-XRP','KRW-DOGE','KRW-XLM','KRW-SHIB','KRW-GAS'}
            markets = [d['market'] for d in data if d['market'] not in exclude]
            return markets[:count]
    except Exception as e:
        print(f"Top markets error: {e}")
        return []

def simulate_range_breakout(candles, bo_min=0.01, vol_min=4.0, tp_pct=0.02, sl_pct=0.02):
    """레인지 돌파 시뮬레이션 (코인봇 현재 방식 근사)"""
    trades = []
    # 날짜별 그룹핑
    by_date = defaultdict(list)
    for c in candles:
        utc = c.get('candle_date_time_utc', '')
        date = utc[:10]
        hm = utc[11:16] if len(utc) > 15 else ''
        by_date[date].append({
            'ts': utc, 'time': hm,
            'open': c['opening_price'], 'high': c['high_price'],
            'low': c['low_price'], 'close': c['trade_price'],
            'vol': c.get('candle_acc_trade_volume', 0),
        })

    dates = sorted(by_date.keys())
    for date in dates:
        day = by_date[date]
        # 레인지: 08:00~09:00 고가
        range_high = 0
        for c in day:
            if '08:00' <= c['time'] < '09:00':
                if c['high'] > range_high:
                    range_high = c['high']
        if range_high <= 0:
            continue

        entry_price_target = range_high * (1 + bo_min)
        entered = False
        entry_price = 0

        for c in day:
            if c['time'] < '09:05' or c['time'] > '12:00':
                continue
            if not entered:
                # 거래량 체크 (간소화: 평균 대비)
                avg_vol = sum(x['vol'] for x in day[:day.index(c)]) / max(1, day.index(c))
                if avg_vol > 0 and c['vol'] < avg_vol * vol_min:
                    continue
                if c['high'] >= entry_price_target and c['close'] > c['open']:  # 양봉 돌파
                    entered = True
                    entry_price = entry_price_target
                    tp = entry_price * (1 + tp_pct)
                    sl = entry_price * (1 - sl_pct)
                    if c['low'] <= sl:
                        trades.append({'date': date, 'pnl': round((sl/entry_price-1)*100, 2), 'result': 'SL'})
                        entered = False; continue
                    if c['high'] >= tp:
                        trades.append({'date': date, 'pnl': round((tp/entry_price-1)*100, 2), 'result': 'TP'})
                        entered = False; continue
            else:
                tp = entry_price * (1 + tp_pct)
                sl = entry_price * (1 - sl_pct)
                if c['low'] <= sl:
                    trades.append({'date': date, 'pnl': round((sl/entry_price-1)*100, 2), 'result': 'SL'})
                    entered = False; continue
                if c['high'] >= tp:
                    trades.append({'date': date, 'pnl': round((tp/entry_price-1)*100, 2), 'result': 'TP'})
                    entered = False; continue

        if entered:
            last_close = day[-1]['close'] if day else entry_price
            trades.append({'date': date, 'pnl': round((last_close/entry_price-1)*100, 2), 'result': 'CLOSE'})

    return trades

def simulate_momentum(candles, tp_pct=0.02, sl_pct=0.02):
    """모멘텀 스코어링 시뮬레이션"""
    trades = []
    closes = []
    vols = []

    def ema(prices, period):
        if len(prices) < period:
            return prices[-1] if prices else 0
        mult = 2.0 / (period + 1)
        val = sum(prices[:period]) / period
        for p in prices[period:]:
            val = p * mult + val * (1 - mult)
        return val

    by_date = defaultdict(list)
    for c in candles:
        utc = c.get('candle_date_time_utc', '')
        date = utc[:10]
        hm = utc[11:16] if len(utc) > 15 else ''
        by_date[date].append({
            'ts': utc, 'time': hm,
            'open': c['opening_price'], 'high': c['high_price'],
            'low': c['low_price'], 'close': c['trade_price'],
            'vol': c.get('candle_acc_trade_volume', 0),
        })

    # 전체 캔들을 시간순으로
    all_candles = []
    for date in sorted(by_date.keys()):
        all_candles.extend(by_date[date])

    entered = False
    entry_price = 0
    entry_date = ''

    for i in range(50, len(all_candles)):
        c = all_candles[i]
        recent_closes = [x['close'] for x in all_candles[max(0,i-50):i+1]]
        recent_vols = [x['vol'] for x in all_candles[max(0,i-20):i]]

        if c['time'] < '09:05' or c['time'] > '12:00':
            if entered and c['time'] > '12:00':
                # 세션 종료 청산
                pnl = (c['close'] / entry_price - 1) * 100
                trades.append({'date': entry_date, 'pnl': round(pnl, 2), 'result': 'CLOSE'})
                entered = False
            continue

        if not entered:
            ema8 = ema(recent_closes, 8)
            ema21 = ema(recent_closes, 21)
            ema50 = ema(recent_closes, 50)
            is_bullish = c['close'] > c['open']
            avg_vol = sum(recent_vols) / max(1, len(recent_vols))
            vol_surge = c['vol'] > avg_vol * 2.0 if avg_vol > 0 else False

            # RSI 근사
            gains = [max(0, recent_closes[j]-recent_closes[j-1]) for j in range(1, len(recent_closes))]
            losses = [max(0, recent_closes[j-1]-recent_closes[j]) for j in range(1, len(recent_closes))]
            avg_gain = sum(gains[-14:]) / 14 if len(gains) >= 14 else 0
            avg_loss = sum(losses[-14:]) / 14 if len(losses) >= 14 else 0.001
            rsi = 100 - (100 / (1 + avg_gain / avg_loss))

            # 3필수 + 보너스
            if ema8 > ema21 > ema50 and 45 <= rsi <= 78 and is_bullish:
                score = 5.0
                if vol_surge: score += 1.5
                if c['close'] > ema8 * 1.001: score += 0.5
                body = abs(c['close'] - c['open'])
                rng = c['high'] - c['low']
                if rng > 0 and body / rng > 0.6: score += 1.0

                if score >= 6.5:
                    entered = True
                    entry_price = c['close']
                    entry_date = c['ts'][:10]
        else:
            tp = entry_price * (1 + tp_pct)
            sl = entry_price * (1 - sl_pct)
            if c['low'] <= sl:
                trades.append({'date': entry_date, 'pnl': round((sl/entry_price-1)*100, 2), 'result': 'SL'})
                entered = False; continue
            if c['high'] >= tp:
                trades.append({'date': entry_date, 'pnl': round((tp/entry_price-1)*100, 2), 'result': 'TP'})
                entered = False; continue

    if entered:
        last = all_candles[-1]
        pnl = (last['close'] / entry_price - 1) * 100
        trades.append({'date': entry_date, 'pnl': round(pnl, 2), 'result': 'CLOSE'})

    return trades


def print_result(name, trades):
    total = len(trades)
    if total == 0:
        print(f"  {name}: 거래 0건")
        return
    wins = sum(1 for t in trades if t['pnl'] > 0)
    wr = wins / total * 100
    avg = sum(t['pnl'] for t in trades) / total
    tot = sum(t['pnl'] for t in trades)
    tp = sum(1 for t in trades if t['result'] == 'TP')
    sl = sum(1 for t in trades if t['result'] == 'SL')
    print(f"  {name}: {total}건 | 승률 {wr:.1f}% | 평균 {avg:+.2f}% | 총 {tot:+.1f}% | TP:{tp} SL:{sl}")


def main():
    print("=" * 70)
    print(" 코인봇 오프닝 전략 비교: 레인지 돌파 vs 모멘텀")
    print(" 업비트 5분봉 200개 (약 2.5일) × TOP 20 마켓")
    print("=" * 70)

    markets = get_top_markets(20)
    print(f"TOP 20 마켓: {markets[:5]}... (총 {len(markets)}개)")

    range_trades = []
    momentum_trades = []

    for i, market in enumerate(markets):
        print(f"  [{i+1}/{len(markets)}] {market} 데이터 조회...")
        candles = fetch_upbit_candles(market, interval=5, count=200)
        time.sleep(0.2)

        if len(candles) < 50:
            print(f"    캔들 부족: {len(candles)}개")
            continue

        rt = simulate_range_breakout(candles, bo_min=0.01, vol_min=4.0)
        mt = simulate_momentum(candles)

        for t in rt: t['market'] = market
        for t in mt: t['market'] = market

        range_trades.extend(rt)
        momentum_trades.extend(mt)

    print(f"\n{'='*70}")
    print(" 결과")
    print(f"{'='*70}")
    print_result("레인지 돌파 (bo≥1.0%, vol≥4.0x)", range_trades)
    print_result("모멘텀 스코어링 (MCM 근사)", momentum_trades)

    print(f"\n{'='*70}")
    print(f"{'전략':<35} {'거래':>5} {'승률':>7} {'평균':>8} {'총PnL':>8}")
    print("-" * 65)
    for name, trades in [("레인지 돌파 (현재 코인봇)", range_trades), ("모멘텀 스코어링", momentum_trades)]:
        total = len(trades)
        if total == 0:
            print(f"{name:<35} {'0':>5}")
            continue
        wins = sum(1 for t in trades if t['pnl'] > 0)
        wr = wins / total * 100
        avg = sum(t['pnl'] for t in trades) / total
        tot = sum(t['pnl'] for t in trades)
        print(f"{name:<35} {total:>5} {wr:>6.1f}% {avg:>+7.2f}% {tot:>+7.1f}%")


if __name__ == '__main__':
    main()
