package com.example.regionsync.dedup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class GlobalLockService {

    private static final long WAIT_SECONDS = 5;
    private static final long LEASE_SECONDS = 30;

    private final RedissonClient redissonClient;

    public RLock getLock(String lockKey) {
        return redissonClient.getLock(lockKey);
    }

    public void executeWithLock(String lockKey, Runnable action) {
        RLock lock = getLock("lock:" + lockKey);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(WAIT_SECONDS, LEASE_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new IllegalStateException("Could not acquire lock for key: " + lockKey);
            }
            action.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while acquiring lock for key: " + lockKey, e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
