package com.example.stocks.db;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "api_key_store")
public class ApiKeyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider", nullable = false, length = 20)
    private String provider = "KIS";

    @Column(name = "enc_key1", nullable = false, columnDefinition = "TEXT")
    private String encKey1;

    @Column(name = "enc_key2", nullable = false, columnDefinition = "TEXT")
    private String encKey2;

    @Column(name = "account_no", length = 20)
    private String accountNo;

    @Column(name = "is_paper", nullable = false)
    private boolean isPaper = true;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    // --- Getters & Setters ---

    public Long getId() { return id; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getEncKey1() { return encKey1; }
    public void setEncKey1(String encKey1) { this.encKey1 = encKey1; }

    public String getEncKey2() { return encKey2; }
    public void setEncKey2(String encKey2) { this.encKey2 = encKey2; }

    public String getAccountNo() { return accountNo; }
    public void setAccountNo(String accountNo) { this.accountNo = accountNo; }

    public boolean isPaper() { return isPaper; }
    public void setPaper(boolean paper) { isPaper = paper; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
