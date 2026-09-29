package com.demo.upimesh.service;

import com.demo.upimesh.model.MeshPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class VirtualDevice {
    private String deviceId;
    private String name;
    private boolean hasInternet;
    private String role; // SENDER, RECEIVER, STRANGER, BRIDGE
    private final List<MeshPacket> packets = new CopyOnWriteArrayList<>();

    public VirtualDevice() {}

    public VirtualDevice(String deviceId, String name, boolean hasInternet, String role) {
        this.deviceId = deviceId;
        this.name = name;
        this.hasInternet = hasInternet;
        this.role = role;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getName() {
        return name;
    }

    public boolean isHasInternet() {
        return hasInternet;
    }

    public void setHasInternet(boolean hasInternet) {
        this.hasInternet = hasInternet;
    }

    public String getRole() {
        return role;
    }

    public List<MeshPacket> getPackets() {
        return packets;
    }

    public void addPacket(MeshPacket packet) {
        boolean exists = packets.stream().anyMatch(p -> p.getPacketId().equals(packet.getPacketId()));
        if (!exists) {
            packets.add(packet);
        }
    }

    public void clearPackets() {
        packets.clear();
    }
}
