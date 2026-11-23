package com.aiagent.infrastructure.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class CacheKeyUtil {

    private CacheKeyUtil() {
    }

    public static String hash(String text) {
        try {
            // SHA-256：MD5 已不满足现代安全基线（cache key 虽非密码场景，仍统一升级）
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    public static String buildKey(String prefix, String text) {
        return prefix + hash(text);
    }
}
