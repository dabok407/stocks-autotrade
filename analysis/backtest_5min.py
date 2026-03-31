#!/usr/bin/env python3
"""
5분봉 기반 오프닝 돌파 전략 A/B/C/D + 모멘텀 비교 백테스트
"""
import csv
from collections import defaultdict, OrderedDict

def load_5min(filepath):
    data = defaultdict(lambda: defaultdict(list))  # symbol -> date -> [candles]
    with open(filepath, 'r', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        for row in reader:
            sym = row['symbol']
            ts = row['timestamp']
            date = ts[:10]
            data[sym][date].append({
                'ts': ts,
                'time': ts[11:16],  # HH:MM
                'open': float(row['open']),
                'high': float(row['high']),
                'low': float(row['low']),
                'close': float(row['close']),
                'vol': float(row['volume']),
            })
    # 정렬
    for sym in data:
        for date in data[sym]:
            data[sym][date].sort(key=lambda x: x['ts'])
    return data

def load_daily(filepath):
    data = defaultdict(dict)  # symbol -> date -> candle
    with open(filepath, 'r', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        for row in reader:
            sym = row['symbol']
            date = row['timestamp'][:10]
            data[sym][date] = {
                'open': float(row['open']),
                'high': float(row['high']),
                'low': float(row['low']),
                'close': float(row['close']),
                'vol': float(row['volume']),
            }
    return data

def get_prev_day(daily, symbol, date):
    """해당 날짜의 전일 데이터 가져오기"""
    dates = sorted(daily.get(symbol, {}).keys())
    for i, d in enumerate(dates):
        if d == date and i > 0:
            return daily[symbol][dates[i-1]]
    return None

def simulate_opening(candles_5min, strategy, range_high, tp_pct=0.02, sl_pct=0.02, bo_min=0.01, entry_start='09:10'):
    """5분봉 기반 오프닝 돌파 시뮬레이션 (1일치)"""
    if range_high <= 0:
        return None

    entry_price = range_high * (1 + bo_min)
    entered = False
    exit_price = 0
    result = ''
    entry_time = ''
    exit_time = ''

    for c in candles_5min:
        if c['time'] < entry_start:
            continue
        if c['time'] > '14:30':
            break

        if not entered:
            # 돌파 진입 확인
            if c['high'] >= entry_price:
                entered = True
                entry_time = c['time']
                # TP/SL 체크
                tp_price = entry_price * (1 + tp_pct)
                sl_price = entry_price * (1 - sl_pct)
                # 같은 캔들에서 TP/SL 체크
                if c['low'] <= sl_price:
                    exit_price = sl_price
                    result = 'SL'
                    exit_time = c['time']
                    break
                if c['high'] >= tp_price:
                    exit_price = tp_price
                    result = 'TP'
                    exit_time = c['time']
                    break
        else:
            # 이미 진입 - TP/SL 체크
            tp_price = entry_price * (1 + tp_pct)
            sl_price = entry_price * (1 - sl_pct)
            if c['low'] <= sl_price:
                exit_price = sl_price
                result = 'SL'
                exit_time = c['time']
                break
            if c['high'] >= tp_price:
                exit_price = tp_price
                result = 'TP'
                exit_time = c['time']
                break

    if entered and not result:
        # 종가 청산
        last = candles_5min[-1] if candles_5min else None
        if last:
            exit_price = last['close']
            result = 'CLOSE'
            exit_time = last['time']

    if not entered:
        return None

    pnl_pct = (exit_price - entry_price) / entry_price * 100
    return {
        'entry_price': round(entry_price, 2),
        'exit_price': round(exit_price, 2),
        'pnl_pct': round(pnl_pct, 2),
        'result': result,
        'entry_time': entry_time,
        'exit_time': exit_time,
        'range_high': round(range_high, 2),
    }

def simulate_momentum(candles_5min, entry_start='09:20'):
    """모멘텀 스코어링 근사 시뮬레이션
    EMA8>EMA21 + RSI 50~70 + 양봉 + 거래량 증가 → 진입
    """
    if len(candles_5min) < 25:
        return None

    # 간단한 EMA 계산
    def ema(prices, period):
        if len(prices) < period:
            return prices[-1] if prices else 0
        mult = 2.0 / (period + 1)
        val = sum(prices[:period]) / period
        for p in prices[period:]:
            val = p * mult + val * (1 - mult)
        return val

    closes = [c['close'] for c in candles_5min]
    vols = [c['vol'] for c in candles_5min]

    entered = False
    entry_price = 0
    entry_time = ''

    for i in range(21, len(candles_5min)):
        c = candles_5min[i]
        if c['time'] < entry_start or c['time'] > '14:00':
            continue

        if not entered:
            # 모멘텀 조건 체크 (간소화)
            ema8 = ema(closes[:i+1], 8)
            ema21 = ema(closes[:i+1], 21)
            is_bullish = c['close'] > c['open']
            vol_avg = sum(vols[max(0,i-20):i]) / min(20, i) if i > 0 else 1
            vol_surge = c['vol'] > vol_avg * 1.5 if vol_avg > 0 else False

            if ema8 > ema21 and is_bullish and vol_surge:
                entered = True
                entry_price = c['close']
                entry_time = c['time']
        else:
            # TP 2% / SL 2%
            tp_price = entry_price * 1.02
            sl_price = entry_price * 0.98
            if c['low'] <= sl_price:
                pnl = (sl_price - entry_price) / entry_price * 100
                return {'entry_price': entry_price, 'exit_price': sl_price, 'pnl_pct': round(pnl, 2),
                        'result': 'SL', 'entry_time': entry_time, 'exit_time': c['time'], 'range_high': 0}
            if c['high'] >= tp_price:
                pnl = (tp_price - entry_price) / entry_price * 100
                return {'entry_price': entry_price, 'exit_price': tp_price, 'pnl_pct': round(pnl, 2),
                        'result': 'TP', 'entry_time': entry_time, 'exit_time': c['time'], 'range_high': 0}

    if entered:
        last = candles_5min[-1]
        pnl = (last['close'] - entry_price) / entry_price * 100
        return {'entry_price': entry_price, 'exit_price': last['close'], 'pnl_pct': round(pnl, 2),
                'result': 'CLOSE', 'entry_time': entry_time, 'exit_time': last['time'], 'range_high': 0}
    return None


def print_summary(name, trades):
    total = len(trades)
    if total == 0:
        print(f"  {name}: 거래 0건")
        return
    wins = sum(1 for t in trades if t['pnl_pct'] > 0)
    wr = wins / total * 100
    avg = sum(t['pnl_pct'] for t in trades) / total
    tot = sum(t['pnl_pct'] for t in trades)
    tp = sum(1 for t in trades if t['result'] == 'TP')
    sl = sum(1 for t in trades if t['result'] == 'SL')
    cl = sum(1 for t in trades if t['result'] == 'CLOSE')
    print(f"  {name}: {total}건 | 승률 {wr:.1f}% | 평균 {avg:+.2f}% | 총 {tot:+.1f}% | TP:{tp} SL:{sl} CL:{cl}")


def main():
    print("=" * 70)
    print(" 5분봉 기반 오프닝 전략 비교 백테스트")
    print(" KRX TOP 29 × 최근 5거래일")
    print("=" * 70)

    data_5min = load_5min('C:/workspace/stocks-autotrade-java8/analysis/candle-data/krx_top30_5min_deep.csv')
    data_daily = load_daily('C:/workspace/stocks-autotrade-java8/analysis/candle-data/krx_top30_daily.csv')

    print(f"종목 수: {len(data_5min)}")
    dates_all = set()
    for sym in data_5min:
        dates_all.update(data_5min[sym].keys())
    dates_sorted = sorted(dates_all)
    print(f"거래일: {dates_sorted}")

    strategies = OrderedDict([
        ('A) 당일 20분 레인지', {'entry_start': '09:20'}),
        ('B) 전일 고가', {'entry_start': '09:00'}),
        ('C) 전일고가+갭보정', {'entry_start': '09:00'}),
        ('D) 하이브리드', {'entry_start': '09:10'}),
        ('E) 모멘텀(현재)', {'entry_start': '09:20'}),
    ])

    results = {k: [] for k in strategies}

    for sym in data_5min:
        for date in sorted(data_5min[sym].keys()):
            candles = data_5min[sym][date]
            if len(candles) < 10:
                continue

            prev_day = get_prev_day(data_daily, sym, date)
            day_open = candles[0]['open'] if candles else 0

            # A) 당일 20분 레인지: 09:00~09:20 고가
            range_a = 0
            for c in candles:
                if '09:00' <= c['time'] <= '09:20':
                    if c['high'] > range_a:
                        range_a = c['high']

            # B) 전일 고가
            range_b = prev_day['high'] if prev_day else 0

            # C) 전일 고가 + 갭 보정
            if prev_day and day_open > 0:
                range_c = max(prev_day['high'], day_open * 1.005)
            else:
                range_c = 0

            # D) 하이브리드
            range_10min = 0
            for c in candles:
                if '09:00' <= c['time'] <= '09:10':
                    if c['high'] > range_10min:
                        range_10min = c['high']

            if prev_day and day_open > 0:
                if day_open > prev_day['high']:
                    range_d = max(range_10min, day_open * 1.003)
                elif prev_day['high'] / day_open > 1.02:
                    range_d = range_10min * 1.003 if range_10min > 0 else 0
                else:
                    range_d = max(prev_day['high'], range_10min)
            else:
                range_d = range_10min

            ranges = {'A) 당일 20분 레인지': range_a, 'B) 전일 고가': range_b,
                      'C) 전일고가+갭보정': range_c, 'D) 하이브리드': range_d}

            for name, cfg in strategies.items():
                if name == 'E) 모멘텀(현재)':
                    trade = simulate_momentum(candles, cfg['entry_start'])
                else:
                    rh = ranges.get(name, 0)
                    if rh <= 0:
                        continue
                    trade = simulate_opening(candles, name, rh, tp_pct=0.02, sl_pct=0.02,
                                            bo_min=0.01, entry_start=cfg['entry_start'])
                if trade:
                    trade['symbol'] = sym
                    trade['date'] = date
                    results[name].append(trade)

    print(f"\n{'='*70}")
    print(" 결과 비교")
    print(f"{'='*70}")
    for name in strategies:
        print_summary(name, results[name])

    # 상세 비교표
    print(f"\n{'='*70}")
    print(f"{'전략':<25} {'거래':>5} {'승률':>7} {'평균PnL':>9} {'총PnL':>9} {'TP':>4} {'SL':>4}")
    print("-" * 70)
    for name in strategies:
        trades = results[name]
        total = len(trades)
        if total == 0:
            print(f"{name:<25} {'0':>5}")
            continue
        wins = sum(1 for t in trades if t['pnl_pct'] > 0)
        wr = wins / total * 100
        avg = sum(t['pnl_pct'] for t in trades) / total
        tot = sum(t['pnl_pct'] for t in trades)
        tp = sum(1 for t in trades if t['result'] == 'TP')
        sl = sum(1 for t in trades if t['result'] == 'SL')
        print(f"{name:<25} {total:>5} {wr:>6.1f}% {avg:>+8.2f}% {tot:>+8.1f}% {tp:>4} {sl:>4}")


if __name__ == '__main__':
    main()
