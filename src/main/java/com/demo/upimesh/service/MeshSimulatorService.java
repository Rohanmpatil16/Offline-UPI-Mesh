package com.demo.upimesh.service;

import com.demo.upimesh.model.IngestionResponse;
import com.demo.upimesh.model.MeshPacket;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MeshSimulatorService {

    private final Map<String, VirtualDevice> devices = new ConcurrentHashMap<>();
    private final BridgeIngestionService bridgeIngestionService;
    private final IdempotencyService idempotencyService;

    public MeshSimulatorService(BridgeIngestionService bridgeIngestionService, IdempotencyService idempotencyService) {
        this.bridgeIngestionService = bridgeIngestionService;
        this.idempotencyService = idempotencyService;
    }

    @PostConstruct
    public void initMeshTopology() {
        devices.put("phone-alice", new VirtualDevice("phone-alice", "Alice's Phone (Sender)", false, "SENDER"));
        devices.put("phone-bob", new VirtualDevice("phone-bob", "Bob's Phone (Receiver)", false, "RECEIVER"));
        devices.put("phone-charlie", new VirtualDevice("phone-charlie", "Charlie's Phone (Stranger)", false, "STRANGER"));
        devices.put("phone-stranger", new VirtualDevice("phone-stranger", "Stranger's Phone", false, "STRANGER"));
        devices.put("phone-bridge", new VirtualDevice("phone-bridge", "Bridge Phone (Walks Outside)", true, "BRIDGE"));
    }

    public Map<String, VirtualDevice> getDevices() {
        return devices;
    }

    public void injectPacket(MeshPacket packet) {
        VirtualDevice alice = devices.get("phone-alice");
        if (alice != null) {
            alice.addPacket(packet);
        }
    }

    public int runGossipRound() {
        int newTransmissions = 0;
        List<VirtualDevice> allDevices = new ArrayList<>(devices.values());

        // Snapshot current packet holdings before this round
        Map<String, List<MeshPacket>> currentHoldings = new HashMap<>();
        for (VirtualDevice device : allDevices) {
            currentHoldings.put(device.getDeviceId(), new ArrayList<>(device.getPackets()));
        }

        // Broadcast packets from holding devices to nearby devices
        for (VirtualDevice senderDevice : allDevices) {
            List<MeshPacket> heldPackets = currentHoldings.get(senderDevice.getDeviceId());
            for (MeshPacket packet : heldPackets) {
                if (packet.getTtl() <= 0) {
                    continue; // Packet TTL expired, won't forward further
                }

                // Broadcast to all other devices in Bluetooth range
                for (VirtualDevice targetDevice : allDevices) {
                    if (targetDevice.getDeviceId().equals(senderDevice.getDeviceId())) {
                        continue;
                    }

                    // Create forwarded hop packet with TTL decremented by 1
                    MeshPacket hopPacket = new MeshPacket(
                            packet.getPacketId(),
                            packet.getTtl() - 1,
                            packet.getCreatedAt(),
                            packet.getCiphertext()
                    );

                    boolean existed = targetDevice.getPackets().stream()
                            .anyMatch(p -> p.getPacketId().equals(packet.getPacketId()));
                    if (!existed) {
                        targetDevice.addPacket(hopPacket);
                        newTransmissions++;
                    }
                }
            }
        }
        return newTransmissions;
    }

    public List<IngestionResponse> flushBridges() {
        List<IngestionResponse> responses = new ArrayList<>();
        for (VirtualDevice device : devices.values()) {
            if (device.isHasInternet() && !device.getPackets().isEmpty()) {
                for (MeshPacket packet : new ArrayList<>(device.getPackets())) {
                    int hopCount = 5 - packet.getTtl();
                    IngestionResponse response = bridgeIngestionService.ingest(packet, device.getDeviceId(), Math.max(1, hopCount));
                    responses.add(response);
                }
            }
        }
        return responses;
    }

    public void resetMesh() {
        for (VirtualDevice device : devices.values()) {
            device.clearPackets();
        }
        idempotencyService.reset();
    }
}
