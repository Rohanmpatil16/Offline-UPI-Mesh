# Offline UPI Mesh Payment & Deferred Settlement Engine

A Spring Boot backend that demonstrates offline UPI payments routed through a Bluetooth-style mesh network. You're in a basement with zero connectivity. You send your friend ₹500. Your phone encrypts the payment, broadcasts it to nearby phones, and the packet hops device-to-device until some phone walks outside, gets 4G, and silently uploads it to this backend. The backend decrypts, deduplicates, and settles.

This repository is the server side of that system, plus a software simulator of the mesh so you can demo the whole flow on a single laptop without any real Bluetooth hardware.

---

## Table of Contents
- [What this demo proves](#what-this-demo-proves)
- [How to run it](#how-to-run-it)
- [The demo flow (step by step)](#the-demo-flow-step-by-step)
- [Architecture](#architecture)
- [The three hard problems and how they're solved](#the-three-hard-problems-and-how-theyre-solved)
- [File-by-file walkthrough](#file-by-file-walkthrough)
- [API reference](#api-reference)
- [Tests](#tests)
- [What's NOT real (and what would change for production)](#whats-not-real-and-what-would-change-for-production)
- [Honest limitations of the concept](#honest-limitations-of-the-concept)

---

## What this demo proves
The system shows three things working end to end:
1. **Zero-Trust Relay Confidentiality**: A payment can travel from sender to backend through untrusted intermediaries without any of them being able to read or tamper with it. (Hybrid RSA-2048 + AES-256-GCM encryption.)
2. **Atomic Idempotency Engine**: Even if the same payment reaches the backend simultaneously through multiple bridge nodes, it settles exactly once. (Atomic compare-and-set on SHA-256 ciphertext hash.)
3. **Anti-Tampering Integrity**: A tampered or replayed packet is rejected before it touches the database ledger.

---

## How to run it

### Prerequisites
- JDK 17 or newer installed and on PATH. Check with `java -version`.
- MySQL Server running on `localhost:3306` with credentials configured in `src/main/resources/application.properties`.

### Run on Windows
```cmd
mvn spring-boot:run
```

### Open the dashboard
Once started, open:
`http://localhost:8080`

---

## Architecture
```
┌─────────────────────────────────────────────────────────────────────────┐
│                         SENDER PHONE (offline)                          │
│  PaymentInstruction { sender, receiver, amount, pinHash, nonce, time }  │
│              │                                                          │
│              ▼ encrypt with server's RSA public key                     │
│   MeshPacket { packetId, ttl, createdAt, ciphertext }                   │
└──────────────────────────────────────┬──────────────────────────────────┘
                                       │ Bluetooth gossip
                                       ▼
        ┌─────────┐  hop   ┌─────────┐  hop   ┌─────────┐
        │stranger1│ ─────▶ │stranger2│ ─────▶ │ bridge  │ ◀── walks outside
        └─────────┘        └─────────┘        └────┬────┘     gets 4G
                                                   │
                                                   ▼ HTTPS POST
┌─────────────────────────────────────────────────────────────────────────┐
│                     SPRING BOOT BACKEND (this project)                  │
│                                                                         │
│  /api/bridge/ingest                                                     │
│       │                                                                 │
│       ▼                                                                 │
│  [1] hash ciphertext (SHA-256)                                          │
│       │                                                                 │
│       ▼                                                                 │
│  [2] IdempotencyService.claim(hash)  ◀── atomic putIfAbsent             │
│       │                                  Duplicates rejected here        │
│       ▼                                                                 │
│  [3] HybridCryptoService.decrypt(ciphertext)                            │
│       │       (RSA-OAEP unwraps AES key, AES-GCM decrypts payload       │
│       │        AND verifies the auth tag — tampering = exception)       │
│       ▼                                                                 │
│  [4] Freshness check: signedAt within last 24h                          │
│       │                                                                 │
│       ▼                                                                 │
│  [5] SettlementService.settle()                                         │
│       @Transactional: debit sender, credit receiver, write ledger       │
│       @Version on Account = optimistic locking (defense in depth)       │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Tests
Run all unit and concurrency tests:
```cmd
mvn test
```

Included test suite (`IdempotencyConcurrencyTest.java`):
- `encryptDecryptRoundTrip`: Verifies hybrid encryption symmetry.
- `tamperedCiphertextIsRejected`: Flips ciphertext byte, verifies rejection as `INVALID`.
- `singlePacketDeliveredByThreeBridgesSettlesExactlyOnce`: Fires 3 concurrent threads delivering the same packet, asserting 1 `SETTLED` and 2 `DUPLICATE_DROPPED`.
