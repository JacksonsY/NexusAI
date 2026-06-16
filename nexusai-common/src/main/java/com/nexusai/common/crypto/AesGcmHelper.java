package com.nexusai.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 加解密（上游凭证存储用；随机 12B nonce + 128bit tag 防篡改）。
 * <p>密钥来源：32 字节（256bit）。<b>密钥强制由环境变量注入，禁硬编码/yml 明文</b>（避免内置默认密钥被泄露利用）。
 * 输出格式：Base64( nonce(12B) || ciphertext+tag )。抽象可切 SM4。
 *
 * @author nexusai
 */
public final class AesGcmHelper {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    /** AES-256 密钥长度（字节） */
    private static final int KEY_LEN = 32;
    private static final int NONCE_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec keySpec;

    /**
     * @param key256 32 字节密钥（AES-256）
     */
    public AesGcmHelper(byte[] key256) {
        if (key256 == null || key256.length != KEY_LEN) {
            throw new IllegalArgumentException("AES-256-GCM 需要 " + KEY_LEN + " 字节密钥，实际=" + (key256 == null ? "null" : key256.length));
        }
        this.keySpec = new SecretKeySpec(key256, KEY_ALGORITHM);
    }

    /** 从 Base64 编码的 256bit 密钥构造（环境变量常用 Base64 注入）。 */
    public static AesGcmHelper fromBase64Key(String base64Key) {
        return new AesGcmHelper(Base64.getDecoder().decode(base64Key));
    }

    public String encrypt(String plain) {
        try {
            byte[] nonce = new byte[NONCE_LEN];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(nonce.length + ct.length).put(nonce).put(ct).array();
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM 加密失败", e);
        }
    }

    public String decrypt(String cipherTextB64) {
        try {
            byte[] all = Base64.getDecoder().decode(cipherTextB64);
            ByteBuffer bb = ByteBuffer.wrap(all);
            byte[] nonce = new byte[NONCE_LEN];
            bb.get(nonce);
            byte[] ct = new byte[bb.remaining()];
            bb.get(ct);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM 解密失败", e);
        }
    }
}
