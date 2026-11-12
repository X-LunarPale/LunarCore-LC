package com.lunarboreal.chat;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AiMemoryPolicyTest {
    @Test void retainsPreferencesWithoutRetainingChatOrCredentials() {
        assertEquals("玩家偏好：我喜欢建造树屋", AiMemoryPolicy.extract("@月灵 我喜欢建造树屋。"));
        assertNotNull(AiMemoryPolicy.extract("I prefer building tree houses"));
        for (String text : List.of("你好呀", "/login secret", "我喜欢用密码 secret 登录", "我喜欢联系 foo@example.com",
                "我喜欢这个验证码 123456", "我喜欢<red>红色", "我喜欢我的地址", "我们去挖矿吧")) {
            assertNull(AiMemoryPolicy.extract(text), text);
        }
    }

    @Test void recallRejectsLegacyDialogueAndChangedEmbeddingSpaces() {
        double[] vector = {1, 0, 0, 0, 0, 0, 0, 0};
        String first = AiGateway.memorySpace("https://example.test/v1", "embed-a");
        String second = AiGateway.memorySpace("https://example.test/v1", "embed-b");
        var preference = new ChatStore.AiMemory(1, "玩家偏好：我喜欢建造树屋", AiGateway.vectorJson(vector, first));
        var raw = new ChatStore.AiMemory(2, "玩家: secret | bot: hello", AiGateway.vectorJson(vector, first));
        assertEquals(List.of(preference.text()), AiGateway.recall(List.of(preference, raw), vector, first));
        assertTrue(AiGateway.recall(List.of(preference), vector, second).isEmpty());
    }

    @Test void embeddingUrlChangesRequireANonemptyNewKey() {
        var original = new AiChatService.Settings(false, "月灵", "友善地聊天", "https://example.test/v1",
                "", "mention", 100, 20, "");
        var saved = AiChatService.update(original, Map.of("embeddingApiKey", "saved-key"));
        assertThrows(RuntimeException.class, () -> AiChatService.update(saved,
                Map.of("embeddingBaseUrl", "https://other.test/v1", "embeddingApiKey", "")));
        assertEquals("new-key", AiChatService.update(saved, Map.of("embeddingBaseUrl", "https://other.test/v1",
                "embeddingApiKey", "new-key")).embeddingApiKey());
    }
}
