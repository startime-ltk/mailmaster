package com.mailmaster.core.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * AES 工具：用于加密存储邮箱凭证，避免明文落盘。
 *
 * 安全说明（重要）：
 *  - 生产环境务必通过环境变量 MAILMASTER_AES_KEY 提供一个≥16字节的密钥；
 *  - 未设置时回退到内置默认值并告警，仅用于本地开发，切勿用于真实部署。
 *  - 多端（Web/小程序后端）复用同一套加密方案时，只需要共享同一个密钥即可。
 */
public class AesUtil {
    private static final String ALGO = "AES/CBC/PKCS5Padding";
    /**
     * 固定 IV，必须严格 16 字节（AES 分组长度）。
     * 旧值 "MailMasterInitVec" 是 17 字节，IvParameterSpec 构造即抛
     * “Wrong IV length: must be 16 bytes long”，导致桌面端添加账户/解密全线失败。
     * 因旧值从未成功加密过（每次都在 init 阶段抛异常），改为 16 字节不存在历史密文兼容问题。
     */
    private static final byte[] IV = iv16("MailMasterInit16");

    /** 把任意长度字符串裁/补成严格 16 字节，杜绝 IvParameterSpec 长度异常 */
    private static byte[] iv16(String s) {
        byte[] out = new byte[16];
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(raw, 0, out, 0, Math.min(raw.length, 16));
        return out;
    }

    private static SecretKeySpec keySpec() {
        String raw = System.getenv("MAILMASTER_AES_KEY");
        if (raw == null || raw.isEmpty()) {
            raw = "dev-only-default-key";
            System.err.println("[WARN] 未设置环境变量 MAILMASTER_AES_KEY，已使用不安全的内置默认密钥，请尽快设置。");
        }
        byte[] kb = raw.getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[16];
        System.arraycopy(kb, 0, key, 0, Math.min(kb.length, 16));
        return new SecretKeySpec(key, "AES");
    }

    public static String encrypt(String plain) throws Exception {
        Cipher c = Cipher.getInstance(ALGO);
        c.init(Cipher.ENCRYPT_MODE, keySpec(), new IvParameterSpec(IV));
        return Base64.getEncoder().encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    }

    public static String decrypt(String cipherText) throws Exception {
        Cipher c = Cipher.getInstance(ALGO);
        c.init(Cipher.DECRYPT_MODE, keySpec(), new IvParameterSpec(IV));
        return new String(c.doFinal(Base64.getDecoder().decode(cipherText)), StandardCharsets.UTF_8);
    }
}
