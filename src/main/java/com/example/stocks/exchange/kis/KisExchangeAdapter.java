package com.example.stocks.exchange.kis;

import com.example.stocks.exchange.AccountPosition;
import com.example.stocks.exchange.ExchangeAdapter;
import com.example.stocks.exchange.OrderResult;
import com.example.stocks.kis.KisAccount;
import com.example.stocks.kis.KisAuth;
import com.example.stocks.kis.KisPrivateClient;
import com.example.stocks.kis.KisPublicClient;
import com.example.stocks.market.MarketType;
import com.example.stocks.market.StockCandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 한국투자증권(KIS) ExchangeAdapter 구현체.
 * KisAuth, KisPublicClient, KisPrivateClient에 위임하여
 * 공통 인터페이스로 변환한다.
 */
@Component
@Primary
public class KisExchangeAdapter implements ExchangeAdapter {

    private static final Logger log = LoggerFactory.getLogger(KisExchangeAdapter.class);

    private final KisAuth auth;
    private final KisPublicClient publicClient;
    private final KisPrivateClient privateClient;

    public KisExchangeAdapter(KisAuth auth, KisPublicClient publicClient, KisPrivateClient privateClient) {
        this.auth = auth;
        this.publicClient = publicClient;
        this.privateClient = privateClient;
    }

    public KisPublicClient getPublicClient() { return publicClient; }

    @Override
    public String getName() {
        return "KIS";
    }

    // =====================================================================
    // 인증
    // =====================================================================

    @Override
    public boolean isConfigured() {
        return privateClient.isConfigured();
    }

    @Override
    public void refreshAuth() {
        auth.getValidToken();
    }

    // =====================================================================
    // 시세
    // =====================================================================

    @Override
    public List<StockCandle> getMinuteCandles(String symbol, MarketType marketType, int unitMin, int count) {
        if (marketType == MarketType.KRX) {
            return getDomesticMinuteCandles(symbol, unitMin, count);
        } else {
            return getOverseasMinuteCandles(symbol, marketType, unitMin, count);
        }
    }

    @Override
    public StockCandle getCurrentPrice(String symbol, MarketType marketType) {
        Map<String, Object> data;
        if (marketType == MarketType.KRX) {
            data = publicClient.getDomesticCurrentPrice(symbol);
        } else {
            String exchangeCode = resolvePublicExchangeCode(marketType);
            data = publicClient.getOverseasCurrentPrice(exchangeCode, symbol);
        }

        StockCandle candle = new StockCandle();
        candle.symbol = symbol;
        candle.marketType = marketType.name();

        if (data != null && !data.isEmpty()) {
            double price;
            if (marketType == MarketType.KRX) {
                price = parseDoubleSafe(data.get("stck_prpr"));
            } else {
                price = parseDoubleSafe(data.get("last"));
                if (price == 0.0) {
                    price = parseDoubleSafe(data.get("base"));
                }
            }
            candle.opening_price = price;
            candle.high_price = price;
            candle.low_price = price;
            candle.trade_price = price;
            candle.candle_date_time_utc = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        }
        return candle;
    }

    @Override
    public List<StockCandle> getDayCandles(String symbol, MarketType marketType, int count) {
        if (marketType == MarketType.KRX) {
            return getDomesticDayCandles(symbol, count);
        } else {
            return getOverseasDayCandles(symbol, marketType, count);
        }
    }

    @Override
    public Map<String, String> getMarketCatalog(MarketType marketType) {
        // KIS 종목 마스터 API: 별도 파일 다운로드 방식이라 REST API로 직접 구현 불가
        // 거래량 순위 API를 통해 종목 코드+이름을 가져올 수 있음
        return Collections.emptyMap();
    }

    // US large-cap stock pool (S&P 500 top by market cap + trading volume)
    private static final String[] US_LARGE_CAP_POOL = {
        "AAPL", "MSFT", "AMZN", "NVDA", "GOOGL", "META", "TSLA", "BRK.B", "JPM", "V",
        "UNH", "XOM", "MA", "JNJ", "PG", "HD", "COST", "ABBV", "MRK", "AVGO",
        "CRM", "KO", "PEP", "CVX", "WMT", "BAC", "LLY", "TMO", "MCD", "CSCO",
        "ABT", "ACN", "DHR", "ADBE", "TXN", "NEE", "PM", "CMCSA", "NKE", "LIN",
        "INTC", "AMD", "QCOM", "ORCL", "NFLX", "INTU", "T", "IBM", "UPS", "GS",
        "CAT", "MS", "BLK", "LOW", "SPGI", "RTX", "AXP", "ISRG", "BKNG", "PLD",
        "MDLZ", "SYK", "ADI", "CB", "GILD", "VRTX", "MMC", "DE", "CI", "LRCX",
        "SO", "DUK", "ZTS", "MO", "CL", "PYPL", "ABNB", "MRVL", "PANW", "SNPS",
        "CDNS", "CRWD", "FTNT", "DDOG", "WDAY", "NOW", "SNOW", "COIN", "SQ", "SHOP",
        "ROKU", "ZM", "DOCU", "U", "NET", "BILL", "TTD", "PINS", "TWLO", "SNAP"
    };

    @Override
    public List<String> getTopSymbolsByVolume(int topN, MarketType marketType) {
        if (marketType != MarketType.KRX) {
            // US market: return from hardcoded large-cap pool
            List<String> result = new ArrayList<String>();
            for (String sym : US_LARGE_CAP_POOL) {
                result.add(sym);
                if (result.size() >= topN) break;
            }
            log.info("[KIS] US stock pool returned {} symbols (requested {})", result.size(), topN);
            return result;
        }

        List<Map<String, Object>> rawList = publicClient.getDomesticVolumeRanking(topN);
        List<String> symbols = new ArrayList<String>();
        for (Map<String, Object> item : rawList) {
            String symbol = strSafe(item.get("mksc_shrn_iscd"));  // 단축종목코드
            if (symbol.isEmpty()) continue;

            // 우선주(끝자리 5,7,8,9,L,K), ETF/ETN, 스팩 등 필터링
            // 보통주만 선택 (끝자리 0)
            if (symbol.length() == 6) {
                char lastChar = symbol.charAt(5);
                if (lastChar != '0') continue;
            }

            symbols.add(symbol);
            if (symbols.size() >= topN) break;
        }
        log.info("[KIS] Volume ranking returned {} symbols (requested {})", symbols.size(), topN);
        return symbols;
    }

    @Override
    public List<Map<String, String>> getTopSymbolsWithName(int topN, MarketType marketType) {
        if (marketType != MarketType.KRX) {
            // US market: return from hardcoded pool
            List<Map<String, String>> result = new ArrayList<Map<String, String>>();
            for (String sym : US_LARGE_CAP_POOL) {
                Map<String, String> entry = new HashMap<String, String>();
                entry.put("symbol", sym);
                entry.put("name", sym);
                result.add(entry);
                if (result.size() >= topN) break;
            }
            return result;
        }

        List<Map<String, Object>> rawList = publicClient.getDomesticVolumeRanking(Math.min(topN * 2, 200));
        List<Map<String, String>> result = new ArrayList<Map<String, String>>();
        for (Map<String, Object> item : rawList) {
            String symbol = strSafe(item.get("mksc_shrn_iscd"));
            String name = strSafe(item.get("hts_kor_isnm"));
            if (symbol.isEmpty()) continue;

            // 보통주만 (끝자리 0)
            if (symbol.length() == 6) {
                char lastChar = symbol.charAt(5);
                if (lastChar != '0') continue;
            }

            Map<String, String> entry = new HashMap<String, String>();
            entry.put("symbol", symbol);
            entry.put("name", name.isEmpty() ? symbol : name);
            result.add(entry);
            if (result.size() >= topN) break;
        }
        log.info("[KIS] Volume ranking with names returned {} symbols (requested {})", result.size(), topN);
        return result;
    }

    // =====================================================================
    // 주문
    // =====================================================================

    @Override
    public OrderResult placeBuyOrder(String symbol, MarketType marketType, int qty, double price) {
        return placeBuyOrder(symbol, marketType, qty, price, "00");
    }

    @Override
    public OrderResult placeBuyOrder(String symbol, MarketType marketType, int qty, double price, String ordType) {
        Map<String, Object> result;
        long krxPrice = "01".equals(ordType) ? 0L : (long) price;
        if (marketType == MarketType.KRX) {
            result = privateClient.placeDomesticOrder(symbol, "BUY", qty, krxPrice, ordType);
        } else {
            String exchangeCode = resolveOrderExchangeCode(marketType);
            double overseasPrice = "01".equals(ordType) ? 0.0 : price;
            result = privateClient.placeOverseasOrder(exchangeCode, symbol, "BUY", qty, overseasPrice, ordType);
        }
        return convertOrderResult(symbol, "BUY", qty, price, result);
    }

    @Override
    public OrderResult placeSellOrder(String symbol, MarketType marketType, int qty, double price) {
        return placeSellOrder(symbol, marketType, qty, price, "00");
    }

    @Override
    public OrderResult placeSellOrder(String symbol, MarketType marketType, int qty, double price, String ordType) {
        Map<String, Object> result;
        long krxPrice = "01".equals(ordType) ? 0L : (long) price;
        if (marketType == MarketType.KRX) {
            result = privateClient.placeDomesticOrder(symbol, "SELL", qty, krxPrice, ordType);
        } else {
            String exchangeCode = resolveOrderExchangeCode(marketType);
            double overseasPrice = "01".equals(ordType) ? 0.0 : price;
            result = privateClient.placeOverseasOrder(exchangeCode, symbol, "SELL", qty, overseasPrice, ordType);
        }
        return convertOrderResult(symbol, "SELL", qty, price, result);
    }

    @Override
    public OrderResult getOrderStatus(String orderNo) {
        Map<String, Object> data = privateClient.getDomesticOrderStatus(orderNo);
        if (data == null || data.isEmpty()) {
            return new OrderResult(orderNo, null, null, 0, 0.0,
                    OrderResult.Status.PENDING, "order not found or API error");
        }

        String symbol = strSafe(data.get("pdno"));
        String side = "1".equals(strSafe(data.get("sll_buy_dvsn_cd"))) ? "SELL" : "BUY";
        int totalQty = (int) parseDoubleSafe(data.get("ord_qty"));         // 주문수량
        int filledQty = (int) parseDoubleSafe(data.get("tot_ccld_qty"));   // 총체결수량
        double avgPrice = parseDoubleSafe(data.get("avg_prvs"));           // 체결평균가

        OrderResult.Status status;
        String message;
        if (filledQty >= totalQty && totalQty > 0) {
            status = OrderResult.Status.FILLED;
            message = "fully filled";
        } else if (filledQty > 0) {
            status = OrderResult.Status.PENDING;
            message = String.format("partial %d/%d", filledQty, totalQty);
        } else {
            String cancelYn = strSafe(data.get("cncl_yn"));
            if ("Y".equals(cancelYn)) {
                status = OrderResult.Status.CANCELLED;
                message = "cancelled";
            } else {
                status = OrderResult.Status.PENDING;
                message = "not yet filled";
            }
        }

        return new OrderResult(orderNo, symbol, side, filledQty, avgPrice, status, message);
    }

    // =====================================================================
    // 잔고
    // =====================================================================

    @Override
    public List<AccountPosition> getBalance(MarketType marketType) {
        List<KisAccount> accounts;
        if (marketType == MarketType.KRX) {
            accounts = privateClient.getDomesticBalance();
        } else {
            accounts = privateClient.getOverseasBalance();
        }

        List<AccountPosition> positions = new ArrayList<AccountPosition>();
        for (KisAccount acc : accounts) {
            positions.add(new AccountPosition(
                    acc.getSymbol(),
                    acc.getName(),
                    acc.getQty(),
                    acc.getAvgPrice(),
                    acc.getCurrentPrice(),
                    acc.getPnl(),
                    acc.getCurrency()
            ));
        }
        return positions;
    }

    // =====================================================================
    // Internal helpers
    // =====================================================================

    private List<StockCandle> getDomesticMinuteCandles(String symbol, int unitMin, int count) {
        // KIS 국내 분봉 API는 시간 파라미터를 요구함 (HHMMSS)
        // 현재 시각 기준으로 조회
        String hourStr = String.format("%02d%02d00",
                java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul")).getHour(),
                java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul")).getMinute());

        List<Map<String, Object>> rawList = publicClient.getDomesticMinuteCandles(symbol, hourStr);
        List<StockCandle> candles = new ArrayList<StockCandle>();

        for (Map<String, Object> raw : rawList) {
            StockCandle c = new StockCandle();
            c.symbol = symbol;
            c.marketType = MarketType.KRX.name();

            // KIS 국내 분봉: stck_bsop_date(yyyyMMdd) + stck_cntg_hour(HHmmss)
            String date = strSafe(raw.get("stck_bsop_date"));
            String time = strSafe(raw.get("stck_cntg_hour"));
            if (!date.isEmpty() && !time.isEmpty()) {
                c.candle_date_time_utc = date.substring(0, 4) + "-" + date.substring(4, 6) + "-"
                        + date.substring(6, 8) + "T" + time.substring(0, 2) + ":" + time.substring(2, 4)
                        + ":" + time.substring(4, 6);
            }

            c.opening_price = parseDoubleSafe(raw.get("stck_oprc"));
            c.high_price = parseDoubleSafe(raw.get("stck_hgpr"));
            c.low_price = parseDoubleSafe(raw.get("stck_lwpr"));
            c.trade_price = parseDoubleSafe(raw.get("stck_prpr"));
            c.candle_acc_trade_volume = parseDoubleSafe(raw.get("cntg_vol"));

            candles.add(c);
        }

        // KIS는 최신->오래된 순 → 역순으로 정렬
        Collections.reverse(candles);

        // count 제한
        if (candles.size() > count) {
            return new ArrayList<StockCandle>(candles.subList(candles.size() - count, candles.size()));
        }
        return candles;
    }

    private List<StockCandle> getOverseasMinuteCandles(String symbol, MarketType marketType,
                                                       int unitMin, int count) {
        String exchangeCode = resolvePublicExchangeCode(marketType);
        List<Map<String, Object>> rawList = publicClient.getOverseasMinuteCandles(
                exchangeCode, symbol, unitMin, count);

        List<StockCandle> candles = new ArrayList<StockCandle>();
        for (Map<String, Object> raw : rawList) {
            StockCandle c = new StockCandle();
            c.symbol = symbol;
            c.marketType = marketType.name();

            // KIS 해외 분봉: xymd(yyyyMMdd) + xhms(HHmmss)
            String date = strSafe(raw.get("xymd"));
            String time = strSafe(raw.get("xhms"));
            if (!date.isEmpty() && !time.isEmpty()) {
                c.candle_date_time_utc = date.substring(0, 4) + "-" + date.substring(4, 6) + "-"
                        + date.substring(6, 8) + "T" + time.substring(0, 2) + ":" + time.substring(2, 4)
                        + ":" + time.substring(4, 6);
            }

            c.opening_price = parseDoubleSafe(raw.get("open"));
            c.high_price = parseDoubleSafe(raw.get("high"));
            c.low_price = parseDoubleSafe(raw.get("low"));
            c.trade_price = parseDoubleSafe(raw.get("last"));
            c.candle_acc_trade_volume = parseDoubleSafe(raw.get("evol"));

            candles.add(c);
        }

        // KIS는 최신->오래된 순 → 역순으로 정렬
        Collections.reverse(candles);

        if (candles.size() > count) {
            return new ArrayList<StockCandle>(candles.subList(candles.size() - count, candles.size()));
        }
        return candles;
    }

    @SuppressWarnings("unchecked")
    private OrderResult convertOrderResult(String symbol, String side, int qty, double price,
                                           Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return new OrderResult(null, symbol, side, 0, 0.0,
                    OrderResult.Status.FAILED, "empty response from KIS");
        }

        String rtCd = (String) result.get("rt_cd");
        if (!"0".equals(rtCd)) {
            String msg = result.get("msg1") != null ? result.get("msg1").toString() : "unknown error";
            return new OrderResult(null, symbol, side, 0, 0.0,
                    OrderResult.Status.FAILED, msg);
        }

        Map<String, Object> output = (Map<String, Object>) result.get("output");
        String ordNo = null;
        if (output != null) {
            Object ordNoObj = output.get("ODNO");
            if (ordNoObj == null) ordNoObj = output.get("KRX_FWDG_ORD_ORGNO");
            if (ordNoObj != null) ordNo = ordNoObj.toString();
        }
        if (ordNo == null) {
            return new OrderResult(null, symbol, side, 0, 0.0,
                    OrderResult.Status.FAILED, "no order number in KIS response");
        }

        // P0-Fix#1 (V41 2026-05-06): 접수 성공(rt_cd=0)이라도 실제 체결 여부는 inquire-daily-ccld 로 검증.
        // 시세가 빠르게 빠지는 SL 상황에서 지정가 매도가 체결 0주로 끝나는 케이스를 잡는다.
        // 200ms × 최대 3회 폴링하여 실제 fill 여부를 확인.
        OrderResult verified = pollFillStatus(ordNo, symbol, side, qty, FILL_POLL_ATTEMPTS, FILL_POLL_DELAY_MS);
        if (verified != null) {
            return verified;
        }

        // 폴링 timeout — 미체결로 보고 호출자가 ORDER_NOT_FILLED 처리하도록 한다.
        log.warn("[KIS] order accepted but fill unverified: ordNo={}, symbol={}, side={}, qty={}",
                ordNo, symbol, side, qty);
        return new OrderResult(ordNo, symbol, side, 0, 0.0,
                OrderResult.Status.PENDING, "accepted but fill not yet confirmed");
    }

    // 폴링 파라미터 — package-private 으로 노출하여 테스트에서 단축 가능.
    static int FILL_POLL_ATTEMPTS = 3;
    static long FILL_POLL_DELAY_MS = 200L;

    /**
     * P0-Fix#1: KIS 주문 접수 직후 실제 체결 상태를 폴링.
     *
     * @return FILLED (full) / PENDING (partial or none) / CANCELLED / null (모든 시도 실패)
     */
    private OrderResult pollFillStatus(String ordNo, String symbol, String side, int requestedQty,
                                       int maxAttempts, long delayMs) {
        OrderResult lastSeen = null;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            OrderResult st = getOrderStatus(ordNo);
            if (st == null) continue;
            lastSeen = st;
            if (st.getStatus() == OrderResult.Status.FILLED && st.getQty() >= requestedQty) {
                double fillPrice = st.getPrice() > 0 ? st.getPrice() : 0.0;
                return new OrderResult(ordNo, symbol, side, st.getQty(), fillPrice,
                        OrderResult.Status.FILLED, "filled");
            }
            if (st.getStatus() == OrderResult.Status.CANCELLED) {
                return new OrderResult(ordNo, symbol, side, 0, 0.0,
                        OrderResult.Status.CANCELLED, "cancelled");
            }
        }
        if (lastSeen != null) {
            // 부분 체결이거나 미체결: PENDING. executedQty=0 으로 두어
            // 호출자(LiveOrderService → KrxMorningRushService)가 isFilled()=false 로 인식.
            return new OrderResult(ordNo, symbol, side, 0, 0.0,
                    OrderResult.Status.PENDING,
                    lastSeen.getMessage() != null ? lastSeen.getMessage() : "not filled within poll timeout");
        }
        return null;
    }

    private List<StockCandle> getDomesticDayCandles(String symbol, int count) {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        String endDate = String.format("%04d%02d%02d", today.getYear(), today.getMonthValue(), today.getDayOfMonth());
        java.time.LocalDate startDay = today.minusDays(Math.min(count * 2, 730));  // 여유있게 조회
        String startDate = String.format("%04d%02d%02d", startDay.getYear(), startDay.getMonthValue(), startDay.getDayOfMonth());

        List<Map<String, Object>> rawList = publicClient.getDomesticDayCandles(symbol, startDate, endDate);
        List<StockCandle> candles = new ArrayList<StockCandle>();

        for (Map<String, Object> raw : rawList) {
            StockCandle c = new StockCandle();
            c.symbol = symbol;
            c.marketType = MarketType.KRX.name();

            String date = strSafe(raw.get("stck_bsop_date"));
            if (!date.isEmpty() && date.length() == 8) {
                c.candle_date_time_utc = date.substring(0, 4) + "-" + date.substring(4, 6) + "-"
                        + date.substring(6, 8) + "T00:00:00";
            }

            c.opening_price = parseDoubleSafe(raw.get("stck_oprc"));
            c.high_price = parseDoubleSafe(raw.get("stck_hgpr"));
            c.low_price = parseDoubleSafe(raw.get("stck_lwpr"));
            c.trade_price = parseDoubleSafe(raw.get("stck_clpr"));
            c.candle_acc_trade_volume = parseDoubleSafe(raw.get("acml_vol"));

            if (c.trade_price > 0) candles.add(c);
        }

        Collections.reverse(candles);  // KIS는 최신→오래된 순

        if (candles.size() > count) {
            return new ArrayList<StockCandle>(candles.subList(candles.size() - count, candles.size()));
        }
        return candles;
    }

    private List<StockCandle> getOverseasDayCandles(String symbol, MarketType marketType, int count) {
        String exchangeCode = resolvePublicExchangeCode(marketType);
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("America/New_York"));
        String endDate = String.format("%04d%02d%02d", today.getYear(), today.getMonthValue(), today.getDayOfMonth());

        List<Map<String, Object>> rawList = publicClient.getOverseasDayCandles(exchangeCode, symbol, "", endDate);
        List<StockCandle> candles = new ArrayList<StockCandle>();

        for (Map<String, Object> raw : rawList) {
            StockCandle c = new StockCandle();
            c.symbol = symbol;
            c.marketType = marketType.name();

            String date = strSafe(raw.get("xymd"));
            if (!date.isEmpty() && date.length() == 8) {
                c.candle_date_time_utc = date.substring(0, 4) + "-" + date.substring(4, 6) + "-"
                        + date.substring(6, 8) + "T00:00:00";
            }

            c.opening_price = parseDoubleSafe(raw.get("open"));
            c.high_price = parseDoubleSafe(raw.get("high"));
            c.low_price = parseDoubleSafe(raw.get("low"));
            c.trade_price = parseDoubleSafe(raw.get("clos"));
            c.candle_acc_trade_volume = parseDoubleSafe(raw.get("tvol"));

            if (c.trade_price > 0) candles.add(c);
        }

        Collections.reverse(candles);  // KIS는 최신→오래된 순

        if (candles.size() > count) {
            return new ArrayList<StockCandle>(candles.subList(candles.size() - count, candles.size()));
        }
        return candles;
    }

    /**
     * 주문 API용 거래소 코드 (NASD, NYSE).
     */
    private String resolveOrderExchangeCode(MarketType marketType) {
        switch (marketType) {
            case NYSE:
                return "NYSE";
            case NASDAQ:
                return "NASD";
            default:
                return "NASD";
        }
    }

    /**
     * 시세 API용 거래소 코드 (NAS, NYS).
     */
    private String resolvePublicExchangeCode(MarketType marketType) {
        switch (marketType) {
            case NYSE:
                return "NYS";
            case NASDAQ:
                return "NAS";
            default:
                return "NAS";
        }
    }

    private double parseDoubleSafe(Object val) {
        if (val == null) return 0.0;
        try {
            return Double.parseDouble(val.toString().trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private String strSafe(Object val) {
        return val != null ? val.toString().trim() : "";
    }
}
