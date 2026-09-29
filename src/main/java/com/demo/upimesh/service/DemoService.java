package com.demo.upimesh.service;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class DemoService {

    private final AccountRepository accountRepository;
    private final ServerKeyHolder serverKeyHolder;
    private final HybridCryptoService cryptoService;
    private final MeshSimulatorService meshSimulatorService;

    public DemoService(AccountRepository accountRepository,
                       ServerKeyHolder serverKeyHolder,
                       HybridCryptoService cryptoService,
                       MeshSimulatorService meshSimulatorService) {
        this.accountRepository = accountRepository;
        this.serverKeyHolder = serverKeyHolder;
        this.cryptoService = cryptoService;
        this.meshSimulatorService = meshSimulatorService;
    }

    @PostConstruct
    public void seedAccounts() {
        if (accountRepository.count() == 0) {
            accountRepository.save(new Account("alice@upi", "Alice Sharma", new BigDecimal("5000.00")));
            accountRepository.save(new Account("bob@upi", "Bob Verma", new BigDecimal("1000.00")));
            accountRepository.save(new Account("charlie@upi", "Charlie Patel", new BigDecimal("2000.00")));
            accountRepository.save(new Account("stranger@upi", "Stranger Relay", new BigDecimal("300.00")));
            accountRepository.save(new Account("bridge@upi", "Bridge Gateway", new BigDecimal("500.00")));
        }
    }

    public MeshPacket createAndInjectDemoPayment(String senderVpa, String receiverVpa, BigDecimal amount, String pin) throws Exception {
        String nonce = UUID.randomUUID().toString();
        long signedAt = System.currentTimeMillis();
        String pinHash = "PIN_" + pin.hashCode();

        PaymentInstruction instruction = new PaymentInstruction(
                senderVpa,
                receiverVpa,
                amount,
                pinHash,
                nonce,
                signedAt
        );

        String ciphertext = cryptoService.encrypt(instruction, serverKeyHolder.getPublicKey());
        String packetId = UUID.randomUUID().toString();
        int initialTtl = 5;

        MeshPacket packet = new MeshPacket(packetId, initialTtl, signedAt, ciphertext);
        meshSimulatorService.injectPacket(packet);
        return packet;
    }
}
