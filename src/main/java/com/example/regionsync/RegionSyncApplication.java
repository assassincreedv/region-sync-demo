package com.example.regionsync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RegionSyncApplication {
    public static void main(String[] args) {
        SpringApplication.run(RegionSyncApplication.class, args);
    }
}
