package interview.guide.infrastructure.file;

import org.springframework.stereotype.Service;

/**
 * 文档提取质量评估。
 *
 * <h3>为什么需要它</h3>
 * PDF 等格式的字体编码可能损坏，导致提取出的文本出现字符级损坏——例如
 * 数字被映射为 {@code U+0000} 控制符、汉字被映射为形近的康熙部首。
 * 这类损坏<b>不会抛异常</b>，若无校验会静默流入下游，最终让 LLM
 * 基于残缺内容给出自信但错误的结论（实测中曾出现「简历存在乱码」这类
 * 指向用户的误判——实际是解析环节的数据丢失）。
 *
 * <h3>为什么必须在清洗前评估</h3>
 * {@link TextCleaningService#cleanText} 会直接删除控制字符。
 * 一旦清洗完成，损坏的证据就消失了，无法再判断文本是否完整。
 * 因此本评估必须在清洗<b>之前</b>执行。
 *
 * <h3>阈值设定依据</h3>
 * 正常文档的三项指标应全部为 0；实测的损坏样本中控制符占比 3.6%、
 * 兼容部首占比 5.6%。阈值取在两者之间并留出余量。
 */
@Service
public class TextQualityAssessor {

    /**
     * 最小样本量：短文本的比例波动大，统计无意义。
     * 低于此长度直接判定为正常，避免误报。
     */
    private static final int MIN_SAMPLE_SIZE = 100;

    /** 控制符占比阈值。正常文档应为 0。 */
    private static final double CONTROL_CHAR_RATIO_THRESHOLD = 0.01;

    /** CJK 兼容部首占比阈值。正常文档应为 0（出现即说明映射异常）。 */
    private static final double COMPAT_RADICAL_RATIO_THRESHOLD = 0.02;

    /** 替换字符（U+FFFD）占比阈值。 */
    private static final double REPLACEMENT_CHAR_RATIO_THRESHOLD = 0.005;

    /**
     * 提取质量报告。
     *
     * @param totalChars      参与评估的字符总数
     * @param controlChars    控制符数量（不含 \t \n \r）
     * @param compatRadicals  CJK 兼容部首区字符数量（U+2E80–U+2FDF）
     * @param replacementChars 替换字符 U+FFFD 数量
     * @param suspicious      是否判定为质量异常
     * @param reason          判定理由（正常时为空串）
     */
    public record QualityReport(
            int totalChars,
            int controlChars,
            int compatRadicals,
            int replacementChars,
            boolean suspicious,
            String reason
    ) {
        /** 是否包含任何损坏信号（用于日志与调试） */
        public boolean hasDamageSignal() {
            return controlChars > 0 || compatRadicals > 0 || replacementChars > 0;
        }
    }

    /**
     * 评估文本提取质量（字符级检查）。
     *
     * @param text 解析器输出的原始文本（清洗<b>之前</b>）
     * @return 质量报告，永不为 null
     */
    public QualityReport assess(String text) {
        return assess(text, -1);
    }

    /**
     * 评估文本提取质量，并额外检查「源非空但提取为空」。
     *
     * <p>源文件有内容、提取结果却是空文本，是解析失败的强信号
     * （例如格式不被支持、字体完全无法解析）。这类情况不能因为
     * 「样本太短」而被放行。</p>
     *
     * @param text           解析器输出的原始文本（清洗<b>之前</b>）
     * @param sourceByteSize 源文件字节数；传负数表示未知，跳过该检查
     * @return 质量报告，永不为 null
     */
    public QualityReport assess(String text, long sourceByteSize) {
        int total = text == null ? 0 : text.length();

        // 空结果检查：源文件非空但提取为空 —— 解析失败，需优先于样本量守卫
        if (total == 0) {
            if (sourceByteSize > 0) {
                return new QualityReport(0, 0, 0, 0, true,
                        String.format("源文件 %d 字节，但提取文本为空（解析失败）", sourceByteSize));
            }
            return new QualityReport(0, 0, 0, 0, false, "");
        }

        int controlChars = 0;
        int compatRadicals = 0;
        int replacementChars = 0;

        for (int i = 0; i < total; i++) {
            char c = text.charAt(i);
            if (isSuspiciousControlChar(c)) {
                controlChars++;
            } else if (isCompatRadical(c)) {
                compatRadicals++;
            } else if (c == '�') {
                replacementChars++;
            }
        }

        // 样本过短时不判定字符比例，避免小样本比例失真导致误报
        if (total < MIN_SAMPLE_SIZE) {
            return new QualityReport(total, controlChars, compatRadicals, replacementChars, false, "");
        }

        double controlRatio = (double) controlChars / total;
        double radicalRatio = (double) compatRadicals / total;
        double replacementRatio = (double) replacementChars / total;

        StringBuilder reason = new StringBuilder();
        if (controlRatio > CONTROL_CHAR_RATIO_THRESHOLD) {
            reason.append(String.format("控制符占比异常 %.2f%%（%d/%d）；",
                    controlRatio * 100, controlChars, total));
        }
        if (radicalRatio > COMPAT_RADICAL_RATIO_THRESHOLD) {
            reason.append(String.format("兼容部首占比异常 %.2f%%（%d/%d）；",
                    radicalRatio * 100, compatRadicals, total));
        }
        if (replacementRatio > REPLACEMENT_CHAR_RATIO_THRESHOLD) {
            reason.append(String.format("替换字符占比异常 %.2f%%（%d/%d）；",
                    replacementRatio * 100, replacementChars, total));
        }

        boolean suspicious = reason.length() > 0;
        return new QualityReport(total, controlChars, compatRadicals, replacementChars,
                suspicious, reason.toString());
    }

    /**
     * 控制字符判定，与 {@link TextCleaningService} 的清理范围保持一致，
     * 但保留 \t(0x09) \n(0x0A) \r(0x0D) —— 这三个是正常换行与缩进。
     */
    private boolean isSuspiciousControlChar(char c) {
        return c <= 0x1F && c != '\t' && c != '\n' && c != '\r';
    }

    /**
     * CJK 兼容部首区判定：U+2E80–U+2FDF。
     *
     * <p>包含 CJK 部首补充（U+2E80–U+2EFF）与康熙部首（U+2F00–U+2FDF）。
     * 正常中文文本不会使用这些码位——它们只在字体编码损坏、
     * 汉字被映射到形近的部首字符时出现。</p>
     */
    private boolean isCompatRadical(char c) {
        return c >= 0x2E80 && c <= 0x2FDF;
    }
}
