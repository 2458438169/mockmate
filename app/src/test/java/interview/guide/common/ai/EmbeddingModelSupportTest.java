package interview.guide.common.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EmbeddingModelSupport 单元测试
 *
 * <p>这两条规则此前在三处各有一份副本，修复时曾遗漏其中一处，
 * 故对其行为做完整覆盖。</p>
 */
@DisplayName("Embedding 配置判定")
class EmbeddingModelSupportTest {

    @Nested
    @DisplayName("looksLikeChatModel —— 拦截聊天模型误配")
    class LooksLikeChatModelTests {

        @ParameterizedTest
        @DisplayName("Embedding 模型不应被误判为聊天模型")
        @ValueSource(strings = {
            "Qwen/Qwen3-Embedding-4B",   // 曾因前缀 'qwen' 被误判，导致向量化全部失败
            "Nebius/Qwen3-Embedding-8B",
            "text-embedding-v3",
            "text-embedding-3-small",
            "embedding-3",
            "BAAI/bge-m3",
            "cf/qwen-embedding-0.6b"
        })
        void embeddingModelsNotFlagged(String model) {
            assertFalse(EmbeddingModelSupport.looksLikeChatModel(model),
                model + " 是 Embedding 模型，不应被判为聊天模型");
        }

        @ParameterizedTest
        @DisplayName("聊天模型应被识别出来")
        @ValueSource(strings = {
            "qwen3.5-flash",
            "glm-5",
            "deepseek-v4-flash",
            "kimi-k3",
            "moonshot-v1-8k",
            "ernie-4.0"
        })
        void chatModelsFlagged(String model) {
            assertTrue(EmbeddingModelSupport.looksLikeChatModel(model),
                model + " 是聊天模型，应被识别出来以拦截误配");
        }

        @Test
        @DisplayName("null 不应抛异常")
        void nullIsSafe() {
            assertFalse(EmbeddingModelSupport.looksLikeChatModel(null));
        }

        @Test
        @DisplayName("大小写不敏感")
        void caseInsensitive() {
            assertTrue(EmbeddingModelSupport.looksLikeChatModel("Qwen3.5-Flash"));
            assertFalse(EmbeddingModelSupport.looksLikeChatModel("QWEN3-EMBEDDING-4B"));
        }
    }

    @Nested
    @DisplayName("normalizeDimensions —— 向量维度归一化")
    class NormalizeDimensionsTests {

        @Test
        @DisplayName("未配置时返回 null（表示不发送 dimensions、使用模型原生维度）")
        void nullStaysNull() {
            assertNull(EmbeddingModelSupport.normalizeDimensions(null),
                "未配置不能兜底到全局默认值——否则参数永远被发送，"
                    + "固定维度模型会返回 HTTP 400");
        }

        @Test
        @DisplayName("显式配置的正整数原样保留")
        void positiveValueKept() {
            assertEquals(1024, EmbeddingModelSupport.normalizeDimensions(1024));
            assertEquals(3072, EmbeddingModelSupport.normalizeDimensions(3072));
        }

        @ParameterizedTest
        @DisplayName("非正数视为未配置")
        @ValueSource(ints = {0, -1, -1024})
        void nonPositiveBecomesNull(int value) {
            assertNull(EmbeddingModelSupport.normalizeDimensions(value));
        }
    }
}
