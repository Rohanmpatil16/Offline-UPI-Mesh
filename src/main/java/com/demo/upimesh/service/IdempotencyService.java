package com.demo.upimesh.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IdempotencyService {

    private final ConcurrentHashMap<String, Instant> seenHashes = new ConcurrentHashMap<>();

    /**
     * Tries to claim a packet hash atomically.
     * @param packetHash SHA-256 hash of ciphertext
     * @return true if claimed for the first time, false if duplicate
     */
    public boolean claim(String packetHash) {
        Instant prev = seenHashes.putIfAbsent(packetHash, Instant.now());
        return prev == null;
    }

    public boolean isSeen(String packetHash) {
        return seenHashes.containsKey(packetHash);
    }

    public void reset() {
        seenHashes.clear();
    }

    public int getSeenCount() {
        return seenHashes.size();
    }

    @Scheduled(fixedRate = 3600000) // Cleanup every hour
    public void evictExpiredHashes() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        seenHashes.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
    }
}
