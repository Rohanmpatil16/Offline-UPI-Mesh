package com.demo.upimesh.crypto;

import com.demo.upimesh.model.PaymentInstruction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class HybridCryptoService {

    private static final String RSA_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final int RSA_KEY_SIZE_BYTES = 256; // 2048-bit RSA output size

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom secureRandom = new SecureRandom();

    public String encrypt(PaymentInstruction payload, PublicKey publicKey) throws Exception {
        byte[] jsonBytes = objectMapper.writeValueAsBytes(payload);

        // 1. Generate AES-256 key
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256, secureRandom);
        SecretKey aesKey = keyGen.generateKey();

        // 2. Generate 12-byte IV for GCM
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);

        // 3. Encrypt JSON payload with AES-GCM
        Cipher aesCipher = Cipher.getInstance(AES_TRANSFORMATION);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, gcmSpec);
        byte[] aesCiphertext = aesCipher.doFinal(jsonBytes);

        // 4. Encrypt AES key with RSA-OAEP
        Cipher rsaCipher = Cipher.getInstance(RSA_TRANSFORMATION);
        rsaCipher.init(Cipher.ENCRYPT_MODE, publicKey);
        byte[] encryptedAesKey = rsaCipher.doFinal(aesKey.getEncoded());

        // 5. Concatenate: [256 bytes RSA Key][12 bytes IV][AES Ciphertext + Tag]
        byte[] packed = new byte[encryptedAesKey.length + iv.length + aesCiphertext.length];
        ByteBuffer buffer = ByteBuffer.wrap(packed);
        buffer.put(encryptedAesKey);
        buffer.put(iv);
        buffer.put(aesCiphertext);

        return Base64.getEncoder().encodeToString(packed);
    }

    public PaymentInstruction decrypt(String ciphertextBase64, PrivateKey privateKey) throws Exception {
        byte[] packed = Base64.getDecoder().decode(ciphertextBase64);

        if (packed.length < RSA_KEY_SIZE_BYTES + GCM_IV_LENGTH + 16) {
            throw new IllegalArgumentException("Invalid payload length");
        }

        ByteBuffer buffer = ByteBuffer.wrap(packed);

        // 1. Extract RSA encrypted AES key (first 256 bytes)
        byte[] encryptedAesKey = new byte[RSA_KEY_SIZE_BYTES];
        buffer.get(encryptedAesKey);

        // 2. Extract IV (next 12 bytes)
        byte[] iv = new byte[GCM_IV_LENGTH];
        buffer.get(iv);

        // 3. Extract AES Ciphertext + GCM Tag
        byte[] aesCiphertext = new byte[buffer.remaining()];
        buffer.get(aesCiphertext);

        // 4. Decrypt AES key using RSA private key
        Cipher rsaCipher = Cipher.getInstance(RSA_TRANSFORMATION);
        rsaCipher.init(Cipher.DECRYPT_MODE, privateKey);
        byte[] aesKeyBytes = rsaCipher.doFinal(encryptedAesKey);
        SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");

        // 5. Decrypt AES-GCM ciphertext
        Cipher aesCipher = Cipher.getInstance(AES_TRANSFORMATION);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        aesCipher.init(Cipher.DECRYPT_MODE, aesKey, gcmSpec);
        byte[] jsonBytes = aesCipher.doFinal(aesCiphertext);

        return objectMapper.readValue(jsonBytes, PaymentInstruction.class);
    }

    public String hashCiphertext(String ciphertextBase64) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(ciphertextBase64.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 digest failed", e);
        }
    }
}
