package com.aiagent.entity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntityBuilderTest {
    @Test void shouldBuildConversation() {
        Conversation c = Conversation.builder().sessionId("s1").title("T").messageCount(5).build();
        assertEquals("s1", c.getSessionId()); c.onCreate(); assertNotNull(c.getCreatedAt()); c.onUpdate();
    }
    @Test void shouldBuildMessage() {
        Message m = Message.builder().sessionId("s1").role("user").content("hello").build();
        assertEquals("user", m.getRole()); m.onCreate(); assertNotNull(m.getCreatedAt());
    }
    @Test void shouldBuildUser() {
        User u = User.builder().username("test").password("pass").email("a@b.com").build();
        assertTrue(u.getEnabled()); u.onCreate(); assertNotNull(u.getCreatedAt());
    }
    @Test void shouldBuildDocument() {
        Document d = Document.builder().fileName("test.pdf").fileType("pdf").fileSize(1024L).build();
        assertEquals(DocumentProcessingStatus.PENDING, d.getProcessingStatus()); d.onCreate();
    }
    @Test void shouldBuildDocumentChunk() {
        DocumentChunk dc = DocumentChunk.builder().documentId(1L).chunkIndex(0).content("text").build();
        assertEquals(0, dc.getChunkIndex()); dc.onCreate();
    }
    @Test void shouldBuildEcommerceQaPair() {
        EcommerceQaPair qa = EcommerceQaPair.builder().question("Q").answer("A").category("c").build();
        assertEquals(1, qa.getStatus()); qa.onCreate();
    }
    @Test void shouldBuildEcommerceFeedback() {
        EcommerceFeedback fb = EcommerceFeedback.builder().sessionId("s1").question("Q").answer("A").rating(5).build();
        assertEquals(5, fb.getRating()); fb.onCreate();
    }
    @Test void shouldBuildMessageClassifyLog() {
        MessageClassifyLog l = MessageClassifyLog.builder().sessionId("s1").questionHash("h").question("Q").classifiedType("t").build();
        assertFalse(l.getHasImage()); l.onCreate();
    }
    @Test void shouldBuildVisionAnalysisCache() {
        VisionAnalysisCache v = VisionAnalysisCache.builder().imageHash("h").modelName("m").resultJson("{}").build();
        assertEquals("h", v.getImageHash()); v.onCreate();
    }
    @Test void shouldTestDocumentProcessingStatus() {
        assertEquals(4, DocumentProcessingStatus.values().length);
    }
}
