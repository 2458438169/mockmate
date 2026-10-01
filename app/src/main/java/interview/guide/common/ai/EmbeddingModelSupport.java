package interview.guide.common.ai;

/**
 * Embedding 模型配置的共用判定逻辑。
 *
 * <h3>为什么要有这个类</h3>
 * 下列两条规则此前在三个服务中各有一份<b>完全相同的副本</b>：
 * <ul>
 *   <li>{@code LlmProviderRegistry}
 *   <li>{@code LlmProviderBootstrapService}
 *   <li>{@code LlmProviderConfigService}
 * </ul>
 * 修复「Embedding 模型被误判为聊天模型」时只改到了前两处，
 * 第三处被遗漏——<b>重复代码的直接后果</b>。统一到这里，避免再次分叉。
 */
public final class EmbeddingModelSupport {

    private EmbeddingModelSupport() {
    }

    /**
     * 判断模型名是否「像聊天模型」，用于拦截把聊天模型误配成 Embedding 模型的情况。
     *
     * <p>厂商前缀判定过于粗糙：它只看模型名开头，会把同一厂商的
     * <b>Embedding</b> 模型一并误伤。例如 {@code Qwen/Qwen3-Embedding-4B}
     * 以 {@code qwen} 开头，会被误判为聊天模型。</p>
     *
     * <p>因此先做一次显式排除：模型名中包含 {@code embed} 的
     * （如 {@code Qwen3-Embedding-4B}、{@code text-embedding-v3}、
     * {@code embedding-3}）一律视为 Embedding 模型。</p>
     *
     * @param model 模型名，可为 null
     * @return true 表示看起来是聊天模型
     */
    public static boolean looksLikeChatModel(String model) {
        if (model == null) {
            return false;
        }
        String lower = model.toLowerCase();
        // 名称含 embed 的一律视为 Embedding 模型，优先于厂商前缀判定
        if (lower.contains("embed")) {
            return false;
        }
        return lower.startsWith("glm-")
            || lower.startsWith("deepseek")
            || lower.startsWith("kimi")
            || lower.startsWith("moonshot")
            || lower.startsWith("qwen")
            || lower.startsWith("ernie");
    }

    /**
     * 归一化 provider 级向量维度配置。
     *
     * <p>返回值会作为 {@code dimensions} 参数发送给 Embedding API，
     * 因此语义必须严格：</p>
     * <ul>
     *   <li>显式配置了正整数 → 返回该值（供 MRL 模型降维，如
     *       {@code text-embedding-3-large} 3072 → 1024）</li>
     *   <li><b>未配置 → 返回 null，表示「不发送该参数、使用模型原生维度」</b>
     *       （固定维度模型如 bge-m3 收到该参数会返回 HTTP 400）</li>
     * </ul>
     *
     * <p><b>不要</b>在这里兜底到全局默认值——那会让参数永远非空、永远被发送，
     * 正是「向量化全部失败」的成因。向量表结构所需的维度由
     * {@code spring.ai.vectorstore.pgvector.dimensions} 与 Flyway 建表脚本决定，
     * 与这里无关。</p>
     *
     * @param configured 配置值，可为 null
     * @return 正整数，或 null 表示未配置
     */
    public static Integer normalizeDimensions(Integer configured) {
        return (configured != null && configured > 0) ? configured : null;
    }
}
