package com.aiagent.domain;

import org.junit.jupiter.api.Test;
import com.aiagent.agent.domain.MessageClassifyLog;
import com.aiagent.agent.domain.VisionAnalysisCache;
import com.aiagent.auth.domain.User;
import com.aiagent.chat.domain.Conversation;
import com.aiagent.chat.domain.Message;
import com.aiagent.ecommerce.domain.EcommerceFeedback;
import com.aiagent.ecommerce.domain.EcommerceQaPair;
import com.aiagent.knowledge.domain.Document;
import com.aiagent.knowledge.domain.DocumentChunk;
import com.aiagent.knowledge.domain.DocumentProcessingStatus;
import static org.junit.jupiter.api.Assertions.*;

class EntityBuilderTest {
    @Test void shouldBuildConversation() {
        Conversation c = Conversation.builder().sessionId("s1").title("T").messageCount(5).build();
        assertEquals("s1", c.getSessionId()); invokeLifecycle(c, "onCreate"); assertNotNull(c.getCreatedAt()); invokeLifecycle(c, "onUpdate");
    }
    @Test void shouldBuildMessage() {
        Message m = Message.builder().sessionId("s1").role("user").content("hello").build();
        assertEquals("user", m.getRole()); invokeLifecycle(m, "onCreate"); assertNotNull(m.getCreatedAt());
    }
    @Test void shouldBuildUser() {
        User u = User.builder().username("test").password("pass").email("a@b.com").build();
        assertTrue(u.getEnabled()); invokeLifecycle(u, "onCreate"); assertNotNull(u.getCreatedAt());
    }
    @Test void shouldBuildDocument() {
        Document d = Document.builder().fileName("test.pdf").fileType("pdf").fileSize(1024L).build();
        assertEquals(DocumentProcessingStatus.PENDING, d.getProcessingStatus()); invokeLifecycle(d, "onCreate");
    }
    @Test void shouldBuildDocumentChunk() {
        DocumentChunk dc = DocumentChunk.builder().documentId(1L).chunkIndex(0).content("text").build();
        assertEquals(0, dc.getChunkIndex()); invokeLifecycle(dc, "onCreate");
    }
    @Test void shouldBuildEcommerceQaPair() {
        EcommerceQaPair qa = EcommerceQaPair.builder().question("Q").answer("A").category("c").build();
        assertEquals(1, qa.getStatus()); invokeLifecycle(qa, "onCreate");
    }
    @Test void shouldBuildEcommerceFeedback() {
        EcommerceFeedback fb = EcommerceFeedback.builder().sessionId("s1").question("Q").answer("A").rating(5).build();
        assertEquals(5, fb.getRating()); invokeLifecycle(fb, "onCreate");
    }
    @Test void shouldBuildMessageClassifyLog() {
        MessageClassifyLog l = MessageClassifyLog.builder().sessionId("s1").questionHash("h").question("Q").classifiedType("t").build();
        assertFalse(l.getHasImage()); invokeLifecycle(l, "onCreate");
    }
    @Test void shouldBuildVisionAnalysisCache() {
        VisionAnalysisCache v = VisionAnalysisCache.builder().imageHash("h").modelName("m").resultJson("{}").build();
        assertEquals("h", v.getImageHash()); invokeLifecycle(v, "onCreate");
    }
    @Test void shouldTestDocumentProcessingStatus() {
        assertEquals(4, DocumentProcessingStatus.values().length);
    }

    private static void invokeLifecycle(Object target, String methodName) {
        try {
            var method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException exception) {
            fail(exception);
        }
    }
}
