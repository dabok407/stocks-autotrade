package com.example.stocks;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StocksAutotradeApplication {
    public static void main(String[] args) {
        SpringApplication.run(StocksAutotradeApplication.class, args);
    }
}
