#!/usr/bin/env python3
"""
오프닝 돌파 전략 A/B/C/D 비교 백테스트
일봉 데이터 기반 시뮬레이션

A안: 당일 레인지 20분 (시가 기준 근사)
B안: 전일 고가
C안: 전일 고가 + 갭 보정
D안: 전일 고가 + 당일 10분 보정 (시가 근사)
"""
import csv
import sys
from collections import defaultdict

def load_daily_candles(filepath):
    """CSV에서 일봉 데이터 로드"""
    data = defaultdict(list)  # symbol -> [candles]
    with open(filepath, 'r', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        for row in reader:
            sym = row['symbol']
            data[sym].append({
                'timestamp': row['timestamp'],
                'open': float(row['open']),
                'high': float(row['high']),
                'low': float(row['low']),
                'close': float(row['close']),
                'volume': float(row['volume']),
            })
    # 날짜순 정렬
    for sym in data:
        data[sym].sort(key=lambda x: x['timestamp'])
    return data

def simulate_strategy(candles, strategy, tp_pct=0.02, sl_pct=0.02, bo_min=0.01):
    """
    일봉 기반 오프닝 돌파 시뮬레이션

    진입 조건: 당일 고가 > rangeHigh * (1 + bo_min)
    진입 가격: rangeHigh * (1 + bo_min) (돌파 시점)

    청산: TP or SL or 당일 종가
    - TP: 진입가 * (1 + tp_pct)
    - SL: 진입가 * (1 - sl_pct)
    - 당일 종가: 위 둘 다 안 걸리면
    """
    trades = []

    for i in range(1, len(candles)):
        prev = candles[i - 1]
        cur = candles[i]

        # 레인지 계산 (전략별)
        if strategy == 'A':
            # 당일 레인지: 시가 근사 (실제로는 09:00~09:20 고가이지만 일봉에서는 시가로 근사)
            range_high = cur['open'] * 1.002  # 시가 + 0.2% (20분 레인지 고가 근사)
        elif strategy == 'B':
            # 전일 고가
            range_high = prev['high']
        elif strategy == 'C':
            # 전일 고가 + 갭 보정
            range_high = max(prev['high'], cur['open'] * 1.005)
        elif strategy == 'D':
            # 하이브리드: 전일 고가 + 10분 보정 (시가 근사)
            if cur['open'] > prev['high']:
                # 갭업: max(시가+0.3%, 10분고가 근사=시가+0.5%)
                range_high = max(cur['open'] * 1.003, cur['open'] * 1.005)
            elif prev['high'] / cur['open'] > 1.02:
                # 갭다운 2%+: 당일 시가 기준
                range_high = cur['open'] * 1.003
            else:
                # 보합: 전일 고가
                range_high = max(prev['high'], cur['open'] * 1.001)
        else:
            continue

        # 돌파 확인: 당일 고가가 range_high * (1 + bo_min)을 넘었는지
        entry_price = range_high * (1 + bo_min)

        if cur['high'] < entry_price:
            continue  # 돌파 안 됨

        # 거래량 필터 (전일 대비 1.5배 이상)
        if i >= 2:
            prev_vol = candles[i - 2]['volume']
            if prev_vol > 0 and cur['volume'] < prev_vol * 1.5:
                continue  # 거래량 부족

        # 청산 시뮬레이션
        tp_price = entry_price * (1 + tp_pct)
        sl_price = entry_price * (1 - sl_pct)

        # 일봉에서의 근사:
        # - 고가가 TP에 도달했으면 TP 체결
        # - 저가가 SL에 도달했으면 SL 체결
        # - 둘 다 도달했으면 먼저 SL 체결로 보수적 가정

        hit_tp = cur['high'] >= tp_price
        hit_sl = cur['low'] <= sl_price

        if hit_sl and hit_tp:
            # 둘 다 도달 - 보수적으로 SL 가정
            exit_price = sl_price
            result = 'SL'
        elif hit_tp:
            exit_price = tp_price
            result = 'TP'
        elif hit_sl:
            exit_price = sl_price
            result = 'SL'
        else:
            # 종가 청산
            exit_price = cur['close']
            result = 'CLOSE'

        pnl_pct = (exit_price - entry_price) / entry_price * 100

        trades.append({
            'date': cur['timestamp'][:10],
            'entry': round(entry_price, 2),
            'exit': round(exit_price, 2),
            'pnl_pct': round(pnl_pct, 2),
            'result': result,
            'range_high': round(range_high, 2),
            'gap_pct': round((cur['open'] / prev['close'] - 1) * 100, 2),
        })

    return trades


def print_summary(strategy_name, all_trades):
    """전략별 요약 출력"""
    total = len(all_trades)
    if total == 0:
        print(f"\n{strategy_name}: 거래 0건")
        return

    wins = sum(1 for t in all_trades if t['pnl_pct'] > 0)
    losses = sum(1 for t in all_trades if t['pnl_pct'] <= 0)
    win_rate = wins / total * 100 if total > 0 else 0
    avg_pnl = sum(t['pnl_pct'] for t in all_trades) / total
    total_pnl = sum(t['pnl_pct'] for t in all_trades)
    avg_win = sum(t['pnl_pct'] for t in all_trades if t['pnl_pct'] > 0) / wins if wins > 0 else 0
    avg_loss = sum(t['pnl_pct'] for t in all_trades if t['pnl_pct'] <= 0) / losses if losses > 0 else 0

    tp_count = sum(1 for t in all_trades if t['result'] == 'TP')
    sl_count = sum(1 for t in all_trades if t['result'] == 'SL')
    close_count = sum(1 for t in all_trades if t['result'] == 'CLOSE')

    print(f"\n{'='*60}")
    print(f" {strategy_name}")
    print(f"{'='*60}")
    print(f" 총 거래: {total}건 (승 {wins} / 패 {losses})")
    print(f" 승률: {win_rate:.1f}%")
    print(f" 평균 수익: {avg_pnl:.2f}%")
    print(f" 총 수익: {total_pnl:.2f}%")
    print(f" 평균 승: +{avg_win:.2f}% / 평균 패: {avg_loss:.2f}%")
    print(f" 청산: TP {tp_count}건, SL {sl_count}건, 종가 {close_count}건")
    print(f" RR비율: {abs(avg_win/avg_loss):.2f}" if avg_loss != 0 else " RR비율: N/A")


def main():
    filepath = 'C:/workspace/stocks-autotrade-java8/analysis/candle-data/krx_top30_daily.csv'

    print("=" * 60)
    print(" 오프닝 돌파 전략 A/B/C/D 비교 백테스트")
    print(" 데이터: KRX 거래대금 TOP 30, 일봉 60일")
    print(" TP: 2%, SL: 2%, 최소 돌파폭: 1.0%")
    print("=" * 60)

    data = load_daily_candles(filepath)
    print(f"\n종목 수: {len(data)}")
    total_candles = sum(len(v) for v in data.values())
    print(f"총 캔들: {total_candles}")

    strategies = {
        'A) 당일 레인지 20분': 'A',
        'B) 전일 고가': 'B',
        'C) 전일고가 + 갭보정': 'C',
        'D) 하이브리드 (전일+10분)': 'D',
    }

    results = {}

    for name, code in strategies.items():
        all_trades = []
        for symbol, candles in data.items():
            trades = simulate_strategy(candles, code, tp_pct=0.02, sl_pct=0.02, bo_min=0.01)
            for t in trades:
                t['symbol'] = symbol
            all_trades.extend(trades)

        results[name] = all_trades
        print_summary(name, all_trades)

    # 비교표
    print(f"\n{'='*60}")
    print(" 종합 비교")
    print(f"{'='*60}")
    print(f"{'전략':<30} {'거래수':>6} {'승률':>8} {'평균PnL':>10} {'총PnL':>10}")
    print("-" * 70)
    for name in strategies:
        trades = results[name]
        total = len(trades)
        if total == 0:
            print(f"{name:<30} {'0':>6} {'N/A':>8} {'N/A':>10} {'N/A':>10}")
            continue
        wins = sum(1 for t in trades if t['pnl_pct'] > 0)
        win_rate = wins / total * 100
        avg_pnl = sum(t['pnl_pct'] for t in trades) / total
        total_pnl = sum(t['pnl_pct'] for t in trades)
        print(f"{name:<30} {total:>6} {win_rate:>7.1f}% {avg_pnl:>9.2f}% {total_pnl:>9.2f}%")

    # 상위 수익 거래 (D안 기준)
    d_trades = results.get('D) 하이브리드 (전일+10분)', [])
    if d_trades:
        d_trades_sorted = sorted(d_trades, key=lambda x: x['pnl_pct'], reverse=True)
        print(f"\n{'='*60}")
        print(" D안 상위 수익 거래 TOP 10")
        print(f"{'='*60}")
        for t in d_trades_sorted[:10]:
            print(f" {t['date']} {t['symbol']} gap={t['gap_pct']:+.1f}% entry={t['entry']} → {t['result']} {t['pnl_pct']:+.2f}%")

        print(f"\n D안 최대 손실 거래 TOP 5")
        for t in d_trades_sorted[-5:]:
            print(f" {t['date']} {t['symbol']} gap={t['gap_pct']:+.1f}% entry={t['entry']} → {t['result']} {t['pnl_pct']:+.2f}%")


if __name__ == '__main__':
    main()
