package com.demo.upimesh.service;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.IngestionResponse;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
import org.springframework.stereotype.Service;

@Service
public class BridgeIngestionService {

    private final HybridCryptoService cryptoService;
    private final ServerKeyHolder serverKeyHolder;
    private final IdempotencyService idempotencyService;
    private final SettlementService settlementService;

    public BridgeIngestionService(HybridCryptoService cryptoService,
                                  ServerKeyHolder serverKeyHolder,
                                  IdempotencyService idempotencyService,
                                  SettlementService settlementService) {
        this.cryptoService = cryptoService;
        this.serverKeyHolder = serverKeyHolder;
        this.idempotencyService = idempotencyService;
        this.settlementService = settlementService;
    }

    public IngestionResponse ingest(MeshPacket packet, String bridgeNodeId, Integer hopCount) {
        if (packet == null || packet.getCiphertext() == null) {
            return new IngestionResponse("INVALID", null, "Empty packet or ciphertext", null);
        }

        // 1. Hash the ciphertext (SHA-256)
        String packetHash = cryptoService.hashCiphertext(packet.getCiphertext());

        // 2. Claim in Idempotency Engine (atomic putIfAbsent)
        boolean claimed = idempotencyService.claim(packetHash);
        if (!claimed) {
            return new IngestionResponse("DUPLICATE_DROPPED", packetHash, "Duplicate packet detected in idempotency cache", null);
        }

        // 3. Hybrid RSA + AES-GCM Decryption & MAC Verification
        PaymentInstruction instruction;
        try {
            instruction = cryptoService.decrypt(packet.getCiphertext(), serverKeyHolder.getPrivateKey());
        } catch (Exception e) {
            return new IngestionResponse("INVALID", packetHash, "Decryption or GCM authentication tag check failed: Tampered or invalid ciphertext (" + e.getMessage() + ")", null);
        }

        // 4. Freshness check (signedAt within last 24 hours)
        long now = System.currentTimeMillis();
        long maxAgeMs = 24 * 3600 * 1000L;
        if (now - instruction.getSignedAt() > maxAgeMs) {
            return new IngestionResponse("INVALID", packetHash, "Packet instruction expired (> 24 hours old)", null);
        }
        if (instruction.getSignedAt() > now + 60000L) { // Allow 1 minute clock skew
            return new IngestionResponse("INVALID", packetHash, "Packet timestamp is in the future", null);
        }

        // 5. Database Settlement
        try {
            Transaction tx = settlementService.settle(instruction, packetHash, bridgeNodeId, hopCount);
            return new IngestionResponse("SETTLED", packetHash, null, tx.getId());
        } catch (Exception e) {
            return new IngestionResponse("INVALID", packetHash, "Settlement failed: " + e.getMessage(), null);
        }
    }
}
