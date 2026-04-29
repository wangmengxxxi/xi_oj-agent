package com.XI.xi_oj.utils;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;

import java.nio.charset.StandardCharsets;

public class AiEncryptUtil {

    public static String encrypt(String aesKey, String plainText) {
        AES aes = SecureUtil.aes(aesKey.getBytes(StandardCharsets.UTF_8));
        return aes.encryptHex(plainText);
    }

    public static String decrypt(String aesKey, String encryptedHex) {
        AES aes = SecureUtil.aes(aesKey.getBytes(StandardCharsets.UTF_8));
        return aes.decryptStr(encryptedHex);
    }
}
