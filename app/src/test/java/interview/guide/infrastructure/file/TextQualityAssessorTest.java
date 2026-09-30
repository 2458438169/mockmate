package interview.guide.infrastructure.file;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TextQualityAssessor 单元测试
 *
 * <p>测试数据基于真实故障样本构造：某字体编码损坏的 PDF 用 PDFBox 2.x
 * 提取后，数字全部变为 U+0000，汉字被映射为形近的康熙部首。</p>
 */
@DisplayName("文本质量评估测试")
class TextQualityAssessorTest {

    private TextQualityAssessor assessor;

    @BeforeEach
    void setUp() {
        assessor = new TextQualityAssessor();
    }

    @Nested
    @DisplayName("正常文本")
    class NormalTextTests {

        @Test
        @DisplayName("正常中文简历内容不应判定为异常")
        void testHealthyChineseText() {
            String text = "陈伟豪\n后端开发实习生\n"
                    + "13266250613 2458438169@qq.com 毕业院校: 广州大学 出生年月: 2003.6\n"
                    + "个人情况\n广州大学研二在读｜无课程安排｜可立即到岗\n"
                    + "技术项目\n微服务内容资讯平台 ｜ Java · Spring Cloud · Kafka · Redis · ES\n"
                    + "2026.05 - 2026.07\n基于 Spring Boot、Spring Cloud 构建资讯后端，覆盖文章提交、审核、检索。";

            TextQualityAssessor.QualityReport report = assessor.assess(text);

            assertFalse(report.suspicious(), "正常文本不应判定为异常");
            assertFalse(report.hasDamageSignal(), "正常文本不应有损坏信号");
            assertEquals(0, report.controlChars());
            assertEquals(0, report.compatRadicals());
            assertEquals(0, report.replacementChars());
        }

        @Test
        @DisplayName("换行、制表符不应被计为控制符")
        void testWhitespaceNotCounted() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 60; i++) {
                sb.append("字段").append(i).append("\t值\n");
            }

            TextQualityAssessor.QualityReport report = assessor.assess(sb.toString());

            assertEquals(0, report.controlChars(), "\\t \\n \\r 属于正常空白，不应计入损坏");
            assertFalse(report.suspicious());
        }

        @Test
        @DisplayName("短文本不做判定，避免小样本比例失真")
        void testShortTextSkipped() {
            // 10 个字符里有 1 个控制符 = 10%，但样本太小，不应告警
            String text = "ab\u0000cd\u0000ef\u0000gh";

            TextQualityAssessor.QualityReport report = assessor.assess(text);

            assertFalse(report.suspicious(), "低于最小样本量时不判定");
            assertEquals(3, report.controlChars(), "统计数据仍应返回，仅不做判定");
        }
    }

    @Nested
    @DisplayName("损坏文本")
    class DamagedTextTests {

        @Test
        @DisplayName("控制符超阈值应判定为异常 —— 数字被映射为 U+0000")
        void testControlCharFlood() {
            // 模拟：正常内容 + 57 个 U+0000（占比超阈值）
            String damaged = buildText(800, "\u0000", 60);

            TextQualityAssessor.QualityReport report = assessor.assess(damaged);

            assertTrue(report.suspicious(), "控制符占比异常应告警");
            assertTrue(report.reason().contains("控制符"), "理由应指明控制符异常");
        }

        @Test
        @DisplayName("兼容部首超阈值应判定为异常 —— 汉字被映射为康熙部首")
        void testCompatRadicalFlood() {
            // ⼴ U+2F34、⼤ U+2F24、⽣ U+2F63 等均为康熙部首区字符
            String damaged = buildText(800, "⼴⼤⽣⼈⼆⾏", 30);

            TextQualityAssessor.QualityReport report = assessor.assess(damaged);

            assertTrue(report.suspicious(), "兼容部首占比异常应告警");
            assertTrue(report.reason().contains("兼容部首"));
            assertTrue(report.compatRadicals() > 0);
        }

        @Test
        @DisplayName("替换字符超阈值应判定为异常")
        void testReplacementCharFlood() {
            String damaged = buildText(800, "�", 20);

            TextQualityAssessor.QualityReport report = assessor.assess(damaged);

            assertTrue(report.suspicious(), "替换字符占比异常应告警");
            assertTrue(report.reason().contains("替换字符"));
        }

        @Test
        @DisplayName("复现真实故障样本的统计特征")
        void testRealWorldSample() {
            // 真实样本：1595 字符中 57 个控制符(3.6%) + 90 个兼容部首(5.6%)
            StringBuilder sb = new StringBuilder();
            sb.append("陈伟豪\n后端开发实习生\n");
            for (int i = 0; i < 57; i++) sb.append('\u0000');
            sb.append("@qq.com 毕业院校: ");
            for (int i = 0; i < 90; i++) sb.append('⼴');
            while (sb.length() < 1595) sb.append("填充内容用于补足长度。");

            TextQualityAssessor.QualityReport report = assessor.assess(sb.toString());

            assertTrue(report.suspicious());
            assertEquals(57, report.controlChars());
            assertEquals(90, report.compatRadicals());
            assertTrue(report.reason().contains("控制符") && report.reason().contains("兼容部首"),
                    "两项指标都应出现在理由中，实际: " + report.reason());
        }
    }

    @Nested
    @DisplayName("边界情况")
    class EdgeCaseTests {

        @Test
        @DisplayName("null 与空字符串不应抛异常")
        void testNullAndEmpty() {
            assertFalse(assessor.assess(null).suspicious());
            assertFalse(assessor.assess("").suspicious());
            assertEquals(0, assessor.assess(null).totalChars());
        }

        @Test
        @DisplayName("源文件非空但提取为空 —— 应判定为异常")
        void testEmptyExtractionFromNonEmptySource() {
            // 实测场景：.md 文件上传后 Tika 提取出 0 字符
            TextQualityAssessor.QualityReport report = assessor.assess("", 1450L);

            assertTrue(report.suspicious(), "源非空而提取为空是解析失败的强信号");
            assertEquals(0, report.totalChars());
            assertTrue(report.reason().contains("解析失败"), "理由应说明解析失败");
        }

        @Test
        @DisplayName("源文件本身为空时不应告警")
        void testEmptySourceNotFlagged() {
            TextQualityAssessor.QualityReport report = assessor.assess("", 0L);

            assertFalse(report.suspicious(), "空文件是正常输入，不该告警");
        }

        @Test
        @DisplayName("单参 assess() 不做空结果检查（源大小未知）")
        void testSingleArgSkipsEmptyCheck() {
            assertFalse(assessor.assess("").suspicious(),
                    "源大小未知时无法判断是解析失败还是文件本身为空");
        }

        @Test
        @DisplayName("有内容时源大小不影响判定")
        void testSourceSizeIgnoredWhenTextPresent() {
            String healthy = "正常的中文内容。".repeat(30);

            assertFalse(assessor.assess(healthy, 5000L).suspicious());
            assertFalse(assessor.assess(healthy, -1L).suspicious());
        }
    }

    /** 构造 total 字符的文本，其中填充 count 次 filler */
    private String buildText(int total, String filler, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(filler);
        while (sb.length() < total) sb.append("正常的中文内容用于填充长度。");
        return sb.toString();
    }
}
