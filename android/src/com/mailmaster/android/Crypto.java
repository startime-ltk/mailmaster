package com.mailmaster.android;

import android.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * AES 工具：用于把邮箱授权码在本地加密存储。
 * 说明：仅学习/演示用，密钥写死在代码里，生产环境应放到 native / Keystore。
 */
public final class Crypto {
    private static final String KEY = "MailMasterKey123"; // 16 字节
    private static final SecretKeySpec KS =
            new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "AES");

    public static String encrypt(String plain) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, KS);
            byte[] enc = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(enc, Base64.NO_WRAP);
        } catch (Exception e) {
            return plain;
        }
    }

    public static String decrypt(String enc) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, KS);
            byte[] dec = c.doFinal(Base64.decode(enc, Base64.NO_WRAP));
            return new String(dec, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return enc;
        }
    }
}
