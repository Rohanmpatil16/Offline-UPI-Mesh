package com.demo.upimesh.model;

public class IngestionResponse {
    private String outcome; // SETTLED, DUPLICATE_DROPPED, INVALID
    private String packetHash;
    private String reason;
    private Long transactionId;

    public IngestionResponse() {}

    public IngestionResponse(String outcome, String packetHash, String reason, Long transactionId) {
        this.outcome = outcome;
        this.packetHash = packetHash;
        this.reason = reason;
        this.transactionId = transactionId;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getPacketHash() {
        return packetHash;
    }

    public void setPacketHash(String packetHash) {
        this.packetHash = packetHash;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Long getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(Long transactionId) {
        this.transactionId = transactionId;
    }
}
