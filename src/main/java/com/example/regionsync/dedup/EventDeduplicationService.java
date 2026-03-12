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
public class EventDeduplicationService {

    private static final String KEY_PREFIX = "event:dedup:";
    private static final Duration TTL = Duration.ofDays(7);

    private final RedissonClient redissonClient;

    public boolean isDuplicate(String eventId) {
        RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + eventId);
        return bucket.isExists();
    }

    public void markProcessed(String eventId) {
        RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + eventId);
        bucket.set("processed", TTL);
        log.debug("Marked event as processed: {}", eventId);
    }
}
