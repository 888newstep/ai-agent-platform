package com.aiagent.infrastructure.cache;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CacheKeyUtilTest {
    @Test void shouldGenerateSha256() {
        String hash = CacheKeyUtil.hash("test");
        assertNotNull(hash); assertEquals(64, hash.length());
    }
    @Test void shouldGenerateConsistentHash() {
        assertEquals(CacheKeyUtil.hash("hello"), CacheKeyUtil.hash("hello"));
    }
    @Test void shouldGenerateDifferentHashes() {
        assertNotEquals(CacheKeyUtil.hash("a"), CacheKeyUtil.hash("b"));
    }
    @Test void shouldBuildKey() {
        assertTrue(CacheKeyUtil.buildKey("prefix:", "text").startsWith("prefix:"));
    }
}
