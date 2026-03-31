package com.example.stocks.security;

import com.example.stocks.db.ApiKeyEntity;
import com.example.stocks.db.ApiKeyRepository;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.Optional;

@Service
public class ApiKeyStoreService {

    private static final String PROVIDER = "KIS";

    private final ApiKeyRepository repo;
    private final AesGcmCrypto crypto;

    public ApiKeyStoreService(ApiKeyRepository repo, AesGcmCrypto crypto) {
        this.repo = repo;
        this.crypto = crypto;
    }

    public boolean isConfigured() {
        Optional<ApiKeyEntity> e = repo.findTopByProviderOrderByIdDesc(PROVIDER);
        return e.isPresent() && crypto.isConfigured();
    }

    /**
     * KIS API 키를 암호화하여 저장.
     *
     * @param appKey    한국투자증권 앱 키
     * @param appSecret 한국투자증권 앱 시크릿
     * @param accountNo 계좌번호 (예: "5012345601")
     * @param isPaper   모의투자 여부 (true=모의, false=실전)
     */
    public void saveKisKeys(String appKey, String appSecret, String accountNo, boolean isPaper) {
        if (!crypto.isConfigured()) {
            throw new IllegalStateException("KIS_KEYSTORE_MASTER (base64) not configured on server.");
        }
        ApiKeyEntity e = repo.findTopByProviderOrderByIdDesc(PROVIDER).orElseGet(ApiKeyEntity::new);
        e.setProvider(PROVIDER);
        e.setEncKey1(Base64.getEncoder().encodeToString(crypto.encrypt(appKey)));
        e.setEncKey2(Base64.getEncoder().encodeToString(crypto.encrypt(appSecret)));
        e.setAccountNo(accountNo);
        e.setPaper(isPaper);
        repo.save(e);
    }

    /**
     * 저장된 KIS 키를 복호화하여 반환.
     *
     * @return KisKeys 또는 null (미설정 시)
     */
    public KisKeys getKisKeys() {
        if (!crypto.isConfigured()) return null;
        Optional<ApiKeyEntity> e = repo.findTopByProviderOrderByIdDesc(PROVIDER);
        if (!e.isPresent()) return null;
        ApiKeyEntity entity = e.get();
        String ak = crypto.decrypt(Base64.getDecoder().decode(entity.getEncKey1()));
        String as = crypto.decrypt(Base64.getDecoder().decode(entity.getEncKey2()));
        if (ak == null || ak.isEmpty() || as == null || as.isEmpty()) return null;
        return new KisKeys(ak, as, entity.getAccountNo(), entity.isPaper());
    }

    /**
     * 저장된 KIS 키를 삭제.
     */
    public void deleteKisKeys() {
        Optional<ApiKeyEntity> e = repo.findTopByProviderOrderByIdDesc(PROVIDER);
        e.ifPresent(repo::delete);
    }

    public static class KisKeys {
        public final String appKey;
        public final String appSecret;
        public final String accountNo;
        public final boolean paper;

        public KisKeys(String appKey, String appSecret, String accountNo, boolean paper) {
            this.appKey = appKey;
            this.appSecret = appSecret;
            this.accountNo = accountNo;
            this.paper = paper;
        }
    }
}
