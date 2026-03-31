package com.example.stocks.dashboard;

import com.example.stocks.db.PositionEntity;
import com.example.stocks.db.PositionRepository;
import com.example.stocks.db.TradeEntity;
import com.example.stocks.db.TradeRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 포트폴리오 요약 서비스.
 * 대시보드에서 총 PnL, ROI, 승률 등을 표시할 때 사용한다.
 */
@Service
public class AssetSummaryService {

    private final TradeRepository tradeRepo;
    private final PositionRepository positionRepo;

    public AssetSummaryService(TradeRepository tradeRepo, PositionRepository positionRepo) {
        this.tradeRepo = tradeRepo;
        this.positionRepo = positionRepo;
    }

    /**
     * 실현 손익 합계 (SELL 거래의 pnl_krw 합산).
     *
     * @param mode 모드 필터 ("PAPER" / "LIVE", null이면 전체)
     * @return 실현 PnL (원화)
     */
    public double getTotalPnl(String mode) {
        List<TradeEntity> trades;
        if (mode != null && !mode.isEmpty()) {
            trades = tradeRepo.findSellsByMode(mode);
        } else {
            trades = tradeRepo.findAll();
        }

        double sum = 0.0;
        for (TradeEntity t : trades) {
            if (!"SELL".equals(t.getAction())) continue;
            sum += bd(t.getPnlKrw());
        }
        return sum;
    }

    /**
     * 총 ROI (%).
     *
     * @param mode      모드 필터
     * @param capitalKrw 투입 자본금
     * @return ROI 퍼센트
     */
    public double getTotalRoi(String mode, double capitalKrw) {
        if (capitalKrw <= 0) return 0.0;
        return (getTotalPnl(mode) / capitalKrw) * 100.0;
    }

    /**
     * 승률 (%).
     * SELL 거래 중 pnl > 0인 비율.
     *
     * @param mode 모드 필터
     * @return 승률 퍼센트 (0~100)
     */
    public double getWinRate(String mode) {
        List<TradeEntity> trades;
        if (mode != null && !mode.isEmpty()) {
            trades = tradeRepo.findSellsByMode(mode);
        } else {
            trades = tradeRepo.findAll();
        }

        int totalSells = 0;
        int wins = 0;
        for (TradeEntity t : trades) {
            if (!"SELL".equals(t.getAction())) continue;
            totalSells++;
            if (bd(t.getPnlKrw()) > 0) wins++;
        }

        if (totalSells == 0) return 0.0;
        return (wins * 100.0) / totalSells;
    }

    /**
     * 승/패 수를 반환한다.
     *
     * @param mode 모드 필터
     * @return int[0]=승, int[1]=패, int[2]=총 SELL 수
     */
    public int[] getWinLoss(String mode) {
        List<TradeEntity> trades;
        if (mode != null && !mode.isEmpty()) {
            trades = tradeRepo.findSellsByMode(mode);
        } else {
            trades = tradeRepo.findAll();
        }

        int wins = 0;
        int losses = 0;
        for (TradeEntity t : trades) {
            if (!"SELL".equals(t.getAction())) continue;
            if (bd(t.getPnlKrw()) > 0) wins++;
            else losses++;
        }
        return new int[]{wins, losses, wins + losses};
    }

    /**
     * 현재 보유 포지션 목록.
     */
    public List<PositionEntity> getCurrentPositions() {
        return positionRepo.findAll();
    }

    /**
     * 현재 보유 포지션 수.
     */
    public long getPositionCount() {
        long count = 0;
        for (PositionEntity p : positionRepo.findAll()) {
            if (p.getQty() > 0) count++;
        }
        return count;
    }

    /**
     * 사용 중인 자본금 합계 (포지션별 avgPrice * qty).
     */
    public double getUsedCapital() {
        double sum = 0.0;
        for (PositionEntity p : positionRepo.findAll()) {
            if (p.getQty() > 0 && p.getAvgPrice() != null) {
                sum += p.getAvgPrice().doubleValue() * p.getQty();
            }
        }
        return sum;
    }

    private double bd(BigDecimal v) {
        return v == null ? 0.0 : v.doubleValue();
    }
}
