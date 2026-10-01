# 更新日志

本文件记录本仓库的变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [未发布]

### 新增

- **文档解析质量检测**（2026-09-30）

  **背景**：`DocumentParseService` 解析完成后直接进入清洗与下游流程，没有任何质量校验。
  当 PDF 字体编码异常导致字符大面积损坏、或格式不受支持导致提取为空时，
  系统无法察觉，仍会把残缺文本交给 LLM 分析——实测中曾让 LLM 输出
  「简历存在乱码」这类指向用户的错误结论。

  **实现**：
  - 新增 `TextQualityAssessor`，在**清洗之前**统计三项损坏信号：
    控制符占比、CJK 兼容部首占比（U+2E80–U+2FDF）、替换字符占比
  - 额外检查「源文件非空但提取为空」——解析失败的强信号，优先于样本量守卫
  - `DocumentParseService` 接入评估，异常时记录 WARN 日志（含文件名、长度、源字节、判定理由）；
    未达阈值但有少量异常字符时记录 INFO 日志

  **为什么必须前置到清洗之前**：`TextCleaningService.cleanText` 会删除控制字符，
  一旦执行就失去了判断依据。

  **为什么不抛异常**：文档可能只是部分损坏，直接失败会误伤可用内容；
  但必须留下明确日志，避免残缺文本静默流入下游。

  **验证**：
  - 单元测试 12 个（`TextQualityAssessorTest`），覆盖正常文本、三项损坏信号、
    真实故障样本统计特征、空结果、短样本、边界情况
  - 端到端：上传含控制符的样本文件，日志正确输出
    `文档解析质量异常，提取内容可能不完整: 长度=0, 源字节=600, 原因=源文件 600 字节，但提取文本为空（解析失败）`

### 修复

- **向量化全部失败：Embedding 请求携带了模型不支持的参数**（2026-10-01）

  **现象**：知识库文档向量化 100% 失败，`vector_status` 停在 `FAILED`，
  异常栈定位到 `OpenAiEmbeddingModel.call → BadRequestException`。
  **RAG 问答链路因此完全不可用。**

  **根因（两处叠加，各自都会导致 HTTP 400）**：

  1. **`dimensions` 参数被无条件发送**。`LlmProviderRegistry` 在构建
     `OpenAiEmbeddingOptions` 时直接把 provider 维度传给 API，
     而 `resolveEmbeddingDimensions` 在未配置时会兜底到全局默认值（1024），
     导致该参数**永远非空、永远被发送**。bge-m3 是固定维度模型，不接受此参数。
  2. **embedding 渠道不支持 base64 编码**。Spring AI 的 `OpenAiEmbeddingModel`
     默认以 base64 编码发送向量，而 `cf/` 前缀（Cloudflare 托管）只接受
     `encoding_format=float`。

  实测各渠道对这两个参数的接受情况：

  | 渠道 | 裸请求 | `dimensions` | `encoding_format: base64` |
  |---|---|---|---|
  | `cf/bge-m3` | ✅ | ❌ 400 | ❌ 400 |
  | `BAAI/bge-m3` | ✅ | ❌ 400 | ✅ |
  | `Pro/BAAI/bge-m3` | ✅ | ❌ 400 | ✅ |

  **修复**：
  1. `LlmProviderRegistry` 仅在 provider **显式**配置维度时才传递 `dimensions`；
     未配置则完全不传（并记录 INFO 日志说明）
  2. `LlmProviderBootstrapService.resolveEmbeddingDimensions` 去掉兜底逻辑，
     未显式配置时写入 `null`（此前是它把 1024 写进了所有 provider 记录）
  3. 移除 `LlmProviderRegistry` 中因此变为死代码的同名私有方法
  4. embedding 渠道由 `cf/bge-m3` 换为 `BAAI/bge-m3`

  **关键澄清**：`dimensions` 此前承担了两个被混在一起的职责——
  **向量表结构的维度**（由 `spring.ai.vectorstore.pgvector.dimensions` 与
  Flyway 建表脚本决定）与 **传给 Embedding API 的参数**。
  两者无关，本次将其拆开。

  **验证**：
  - 日志确认 `Provider 'tumuer' 未显式配置 embeddingDimensions，不向 Embedding API 传递 dimensions 参数`
  - 上传文档后 `vector_status = COMPLETED`，`vector_store` 表写入 1 行向量，
    全程 0 条 ERROR
  - **端到端跑通 RAG 问答**：提问「什么是延迟双删？为什么需要第二次删除？」
    返回的答案准确覆盖了文档中的三个要点（定义、并发读导致的不一致、
    延迟时间应大于一次读请求耗时）

- **简历分析误判时间线为「未来时间」**（2026-09-30）

  **现象**：简历中 `2026.05 - 2026.07`（过去时间）被判定为「未来时间」，
  并被列为最高优先级的真实性问题。

  **根因**：`resume-analysis-user.st` 只注入 `{resumeText}`，未提供当前日期。
  LLM 的知识存在截止时间，在缺少基准的情况下只能按自身认知判断时间先后。

  **修复**：在 Prompt 中新增「当前日期」区块，由 `ResumeGradingService`
  注入 `LocalDate.now()`，并明确要求以该日期为判断基准。

  **验证**：重新上传同一份简历，分析结果中「未来」「时间线」「时间错误」
  等表述出现次数由多次降为 **0**；时间判断改为对项目周期（2 个月）
  的合理性评价。见 `ResumeGradingService.java` 与 `prompts/resume-analysis-user.st`。

- **PDF 文本提取乱码：数字丢失、汉字变形**（2026-09-30）

  **现象**：上传含自定义字体子集的中文 PDF（简历、报告等）时，提取结果出现两类损坏——
  所有数字被映射为 `U+0000` 控制符，部分汉字被映射为形近的康熙部首
  （如 `⼴` U+2F34 而非 `广` U+5E7F）。

  **根因**：该 PDF 的 `ToUnicode` CMap 存在缺陷。PDFBox 2.0.31（Tika 2.9.2 的传递依赖）
  无条件信任 `ToUnicode` 映射；而 Xpdf 等解析器会检测映射异常并回退到字体自身的编码表，
  因此同一份文件用不同工具解析会得到截然不同的结果。

  **修复**：Apache Tika `2.9.2` → `3.3.0`（传递依赖 PDFBox `2.0.31` → `3.0.7`）。
  PDFBox 3.x 增强了字体编码的容错处理。上游代码零改动，仅版本号变更。

  **验证**：端到端复测——删除并重新上传同一份 PDF 后，
  提取文本中控制符数由 57 降至 **0**，康熙部首区字符数由 90 降至 **0**，
  联系方式、日期、证书时间全部正确提取。分析报告中
  「简历存在严重乱码」的误报消失，建议具体度显著提升。

  **影响**：修复前，下游 LLM 基于残缺文本进行分析，会得出「简历存在乱码」
  这类指向用户的错误结论——实际是解析环节的数据丢失。

  **注意**：`resumes.file_hash` 有唯一索引，同文件重新上传会被去重并复用
  已存的旧文本。验证修复时必须先删除记录再上传，「重新分析」不会触发重新解析
  （见 `AnalyzeStreamConsumer`：仅当 `resumeText` 为空时才重新解析）。

### 变更

- **重写 README**：替换为面向本仓库的说明文档，补充本地运行步骤、技术选型说明与常见问题。（2026-09-29）

## 已知问题

- **分析 Prompt 的「技术优化基准」会诱导 LLM 建议未采用的组件**：
  `resume-analysis-user.st` 中列有一份含具体指标的参考表达模板
  （如「Redis + Caffeine 两级缓存架构，支撑 30w+ QPS」）。LLM 会将这些模板
  直接套用到任何简历上，产出的建议包含候选人并未使用的组件和未经测量的数字。
  建议区分「表达优化建议」与「技术补强建议」，或对后者明确标注为可选方向。

- **「重新分析」不会重新解析文档**：`AnalyzeStreamConsumer` 仅在
  `resumeText` 为空时才调用解析逻辑。当解析器升级或修复后，
  已存在的记录无法通过「重新分析」获得新的解析结果。
