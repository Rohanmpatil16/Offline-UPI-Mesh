package com.demo.upimesh;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.IngestionResponse;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.IdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:upimesh_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
public class IdempotencyConcurrencyTest {

    @Autowired
    private HybridCryptoService cryptoService;

    @Autowired
    private ServerKeyHolder serverKeyHolder;

    @Autowired
    private BridgeIngestionService bridgeIngestionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private IdempotencyService idempotencyService;

    @BeforeEach
    public void setup() {
        idempotencyService.reset();
        accountRepository.deleteAll();
        accountRepository.save(new Account("alice@upi", "Alice", new BigDecimal("1000.00")));
        accountRepository.save(new Account("bob@upi", "Bob", new BigDecimal("500.00")));
    }

    @Test
    public void encryptDecryptRoundTrip() throws Exception {
        PaymentInstruction instruction = new PaymentInstruction(
                "alice@upi", "bob@upi", new BigDecimal("100.00"), "PIN_1234", UUID.randomUUID().toString(), System.currentTimeMillis()
        );

        String ciphertext = cryptoService.encrypt(instruction, serverKeyHolder.getPublicKey());
        assertNotNull(ciphertext);

        PaymentInstruction decrypted = cryptoService.decrypt(ciphertext, serverKeyHolder.getPrivateKey());
        assertEquals("alice@upi", decrypted.getSenderVpa());
        assertEquals("bob@upi", decrypted.getReceiverVpa());
        assertEquals(0, new BigDecimal("100.00").compareTo(decrypted.getAmount()));
    }

    @Test
    public void tamperedCiphertextIsRejected() throws Exception {
        PaymentInstruction instruction = new PaymentInstruction(
                "alice@upi", "bob@upi", new BigDecimal("100.00"), "PIN_1234", UUID.randomUUID().toString(), System.currentTimeMillis()
        );

        String originalCiphertext = cryptoService.encrypt(instruction, serverKeyHolder.getPublicKey());
        byte[] bytes = Base64.getDecoder().decode(originalCiphertext);
        
        // Flip one byte in the AES ciphertext section to simulate tampering
        bytes[bytes.length - 1] = (byte) (bytes[bytes.length - 1] ^ 0xFF);
        String tamperedCiphertext = Base64.getEncoder().encodeToString(bytes);

        MeshPacket packet = new MeshPacket(UUID.randomUUID().toString(), 5, System.currentTimeMillis(), tamperedCiphertext);
        IngestionResponse response = bridgeIngestionService.ingest(packet, "phone-bridge-1", 1);

        assertEquals("INVALID", response.getOutcome());
        assertTrue(response.getReason().contains("failed"));
    }

    @Test
    public void singlePacketDeliveredByThreeBridgesSettlesExactlyOnce() throws Exception {
        // Create 1 valid payment instruction packet for ₹200.00
        PaymentInstruction instruction = new PaymentInstruction(
                "alice@upi", "bob@upi", new BigDecimal("200.00"), "PIN_1234", UUID.randomUUID().toString(), System.currentTimeMillis()
        );

        String ciphertext = cryptoService.encrypt(instruction, serverKeyHolder.getPublicKey());
        MeshPacket packet = new MeshPacket(UUID.randomUUID().toString(), 3, System.currentTimeMillis(), ciphertext);

        // Fire 3 concurrent threads simulating 3 bridges uploading the identical packet simultaneously
        int threadCount = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Future<IngestionResponse>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final String bridgeId = "phone-bridge-" + (i + 1);
            futures.add(executor.submit(() -> {
                barrier.await(); // Synchronize all threads to fire simultaneously
                return bridgeIngestionService.ingest(packet, bridgeId, 2);
            }));
        }

        List<IngestionResponse> results = new ArrayList<>();
        for (Future<IngestionResponse> future : futures) {
            results.add(future.get(10, TimeUnit.SECONDS));
        }
        executor.shutdown();

        // Count outcomes
        long settledCount = results.stream().filter(r -> "SETTLED".equals(r.getOutcome())).count();
        long duplicateCount = results.stream().filter(r -> "DUPLICATE_DROPPED".equals(r.getOutcome())).count();

        assertEquals(1, settledCount, "Exactly one bridge upload should settle");
        assertEquals(2, duplicateCount, "Two concurrent bridge uploads should be dropped as duplicates");

        // Verify account balances changed exactly once (Alice ₹1000 - ₹200 = ₹800, Bob ₹500 + ₹200 = ₹700)
        Account alice = accountRepository.findByVpa("alice@upi").orElseThrow();
        Account bob = accountRepository.findByVpa("bob@upi").orElseThrow();

        assertEquals(0, new BigDecimal("800.00").compareTo(alice.getBalance()), "Alice balance should be debited exactly once");
        assertEquals(0, new BigDecimal("700.00").compareTo(bob.getBalance()), "Bob balance should be credited exactly once");
    }
}
