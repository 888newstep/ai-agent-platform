package com.aiagent.cache;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CacheKeyUtilTest {
    @Test void shouldGenerateMd5() {
        String hash = CacheKeyUtil.md5("test");
        assertNotNull(hash); assertEquals(32, hash.length());
    }
    @Test void shouldGenerateConsistentHash() {
        assertEquals(CacheKeyUtil.md5("hello"), CacheKeyUtil.md5("hello"));
    }
    @Test void shouldGenerateDifferentHashes() {
        assertNotEquals(CacheKeyUtil.md5("a"), CacheKeyUtil.md5("b"));
    }
    @Test void shouldBuildKey() {
        assertTrue(CacheKeyUtil.buildKey("prefix:", "text").startsWith("prefix:"));
    }
}
