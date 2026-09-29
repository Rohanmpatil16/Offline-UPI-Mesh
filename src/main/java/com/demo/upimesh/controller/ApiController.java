package com.demo.upimesh.controller;

import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.*;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.DemoService;
import com.demo.upimesh.service.MeshSimulatorService;
import com.demo.upimesh.service.VirtualDevice;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final ServerKeyHolder serverKeyHolder;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final MeshSimulatorService meshSimulatorService;
    private final BridgeIngestionService bridgeIngestionService;
    private final DemoService demoService;

    public ApiController(ServerKeyHolder serverKeyHolder,
                         AccountRepository accountRepository,
                         TransactionRepository transactionRepository,
                         MeshSimulatorService meshSimulatorService,
                         BridgeIngestionService bridgeIngestionService,
                         DemoService demoService) {
        this.serverKeyHolder = serverKeyHolder;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.meshSimulatorService = meshSimulatorService;
        this.bridgeIngestionService = bridgeIngestionService;
        this.demoService = demoService;
    }

    @GetMapping("/server-key")
    public ResponseEntity<Map<String, String>> getServerKey() {
        Map<String, String> response = new HashMap<>();
        response.put("publicKey", serverKeyHolder.getPublicKeyBase64());
        response.put("algorithm", "RSA-2048 / OAEPWithSHA-256");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/accounts")
    public ResponseEntity<List<Account>> getAccounts() {
        return ResponseEntity.ok(accountRepository.findAll());
    }

    @GetMapping("/transactions")
    public ResponseEntity<List<Transaction>> getTransactions() {
        return ResponseEntity.ok(transactionRepository.findTop20ByOrderByIdDesc());
    }

    @GetMapping("/mesh/state")
    public ResponseEntity<Map<String, VirtualDevice>> getMeshState() {
        return ResponseEntity.ok(meshSimulatorService.getDevices());
    }

    public static class SendRequest {
        private String sender;
        private String receiver;
        private BigDecimal amount;
        private String pin;

        public String getSender() { return sender; }
        public void setSender(String sender) { this.sender = sender; }
        public String getReceiver() { return receiver; }
        public void setReceiver(String receiver) { this.receiver = receiver; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        public String getPin() { return pin; }
        public void setPin(String pin) { this.pin = pin; }
    }

    @PostMapping("/demo/send")
    public ResponseEntity<Map<String, Object>> sendDemoPayment(@RequestBody SendRequest request) {
        try {
            String sender = request.getSender() != null ? request.getSender() : "alice@upi";
            String receiver = request.getReceiver() != null ? request.getReceiver() : "bob@upi";
            BigDecimal amount = request.getAmount() != null ? request.getAmount() : new BigDecimal("500.00");
            String pin = request.getPin() != null ? request.getPin() : "1234";

            MeshPacket packet = demoService.createAndInjectDemoPayment(sender, receiver, amount, pin);
            Map<String, Object> response = new HashMap<>();
            response.put("status", "SUCCESS");
            response.put("message", "Payment encrypted with RSA+AES-GCM and injected into Alice's phone mesh storage");
            response.put("packetId", packet.getPacketId());
            response.put("initialTtl", packet.getTtl());
            response.put("ciphertextSnippet", packet.getCiphertext().substring(0, 40) + "...");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "ERROR");
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    @PostMapping("/mesh/gossip")
    public ResponseEntity<Map<String, Object>> runGossip() {
        int transmissions = meshSimulatorService.runGossipRound();
        Map<String, Object> response = new HashMap<>();
        response.put("status", "SUCCESS");
        response.put("transmissionsCount", transmissions);
        response.put("devices", meshSimulatorService.getDevices());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/mesh/flush")
    public ResponseEntity<List<IngestionResponse>> flushBridges() {
        List<IngestionResponse> responses = meshSimulatorService.flushBridges();
        return ResponseEntity.ok(responses);
    }

    @PostMapping("/mesh/reset")
    public ResponseEntity<Map<String, String>> resetMesh() {
        meshSimulatorService.resetMesh();
        Map<String, String> response = new HashMap<>();
        response.put("status", "SUCCESS");
        response.put("message", "Mesh state and idempotency cache reset");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/bridge/ingest")
    public ResponseEntity<IngestionResponse> ingestPacket(
            @RequestHeader(value = "X-Bridge-Node-Id", defaultValue = "phone-bridge-external") String bridgeNodeId,
            @RequestHeader(value = "X-Hop-Count", defaultValue = "1") Integer hopCount,
            @RequestBody MeshPacket packet) {
        
        IngestionResponse response = bridgeIngestionService.ingest(packet, bridgeNodeId, hopCount);
        return ResponseEntity.ok(response);
    }
}
