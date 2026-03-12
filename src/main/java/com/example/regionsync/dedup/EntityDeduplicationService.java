package com.example.regionsync.dedup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class EntityDeduplicationService {

    private static final String REG_PREFIX = "entity:reg:";
    private static final String CONFIRMED_PREFIX = "entity:confirmed:";
    private static final Duration REG_TTL = Duration.ofMinutes(30);
    private static final Duration CONFIRMED_TTL = Duration.ofDays(7);

    private final RedissonClient redissonClient;

    /**
     * Attempt to register an entity creation claim. Returns true if this region
     * successfully claimed the registration (SETNX semantics via setIfAbsent).
     */
    public boolean tryRegister(String tableName, String businessKey, String region) {
        String key = REG_PREFIX + tableName + ":" + businessKey;
        RBucket<String> bucket = redissonClient.getBucket(key);
        boolean registered = bucket.setIfAbsent(region, REG_TTL);
        log.debug("tryRegister table={} key={} region={} success={}", tableName, businessKey, region, registered);
        return registered;
    }

    public void confirm(String tableName, String businessKey) {
        String key = CONFIRMED_PREFIX + tableName + ":" + businessKey;
        RBucket<String> bucket = redissonClient.getBucket(key);
        bucket.set("confirmed", CONFIRMED_TTL);
        log.debug("Confirmed entity registration: table={} key={}", tableName, businessKey);
    }

    public void release(String tableName, String businessKey) {
        String key = REG_PREFIX + tableName + ":" + businessKey;
        redissonClient.getBucket(key).delete();
        log.debug("Released entity registration: table={} key={}", tableName, businessKey);
    }

    public boolean isConfirmed(String tableName, String businessKey) {
        String key = CONFIRMED_PREFIX + tableName + ":" + businessKey;
        return redissonClient.getBucket(key).isExists();
    }
}
