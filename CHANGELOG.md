# 更新日志

本文件记录本仓库的变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [未发布]

### 文档更正

- **移除 README 中关于 Agent / MCP 能力的不实描述**（2026-10-01）

  **问题**：README 首行描述为「Agent 应用实践项目」，正文称项目覆盖
  「Agent 应用开发（Spring AI、RAG、**MCP**、Skill、Advisor）」。
  经核实：

  | 声明 | 实际情况 |
  |---|---|
  | MCP | 项目内 **0 个文件** |
  | 工具调用（`@Tool`） | **0 个文件** |
  | 工具注册（`.tools(...)`） | **0 处** |
  | Agent 链路 | `SkillsTool` 仅挂载于语音面试链路，非核心能力 |

  本项目核心链路（知识库问答、简历分析）是**固定流程的 RAG 应用**：
  检索 → 拼 Prompt → 生成，不含工具调用与自主循环。

  **成因**：描述是依据「Spring AI 2.0 支持 MCP」推断的——
  **框架支持 ≠ 项目使用**。写文档时未对照代码逐项核实。

  **更正**：首行改为「LLM 应用实践项目」；补充「关于技术边界」说明，
  明确核心链路为固定流程 RAG，并说明 Spring AI 的 Agent 扩展点
  在项目中的实际接入范围。

  **教训**：技术描述必须**从代码里读出来**，不能从「框架支持什么」推断。
  这条与项目自身的规则一致——「写的每一句都要能扛住追问」。

### 重构

- **抽取 `EmbeddingModelSupport`，统一三处重复的判定逻辑**（2026-10-01）

  **背景**：`looksLikeChatModel` 与 `resolveEmbeddingDimensions` 这两个纯函数，
  此前在 `LlmProviderRegistry`、`LlmProviderBootstrapService`、
  `LlmProviderConfigService` 中**各有一份完全相同的副本**。

  这直接导致了上一轮修复的遗漏——「Embedding 模型被误判为聊天模型」只改到了
  前两处，第三处被漏掉。**同一规则存在多份副本时，修一处就少一处。**

  **变更**：
  - 新增 `common/ai/EmbeddingModelSupport`，集中两条规则：
    - `looksLikeChatModel` —— 含 `embed` 的名称优先判为 Embedding 模型
    - `normalizeDimensions` —— 仅保留显式配置的正整数，未配置返回 `null`
  - 三个服务改为调用工具类，删除各自的私有副本
  - **补充修复**：`LlmProviderConfigService.validateEmbeddingConfig` 此前
    要求维度必须非空。维度现为可选（留空 = 使用模型原生维度），
    故调整为「填写时必须是正整数，留空合法」

  **为什么维度要允许留空**：固定维度模型（bge-m3 等）收到 `dimensions`
  参数会被 API 拒绝；MRL 模型（text-embedding-3-*、Qwen3-Embedding-*）
  想降维才需要填写。留空是合法且有意义的语义，不是漏填。

  **验证**：
  - 新增 `EmbeddingModelSupportTest`（20 个测试：15 个模型名判定 + 5 个维度归一化）
  - `common.ai` + `modules.llmprovider` + `infrastructure.file` 三包共
    **150 个测试全部通过**
  - 端到端：清空向量后重新向量化 → `COMPLETED`，RAG 问答答案准确

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

- **分析 Prompt 诱导 LLM 建议未采用的组件与编造量化指标**（2026-10-01）

  **现象**：简历分析给出的建议中，大量出现候选人**从未使用过**的组件
  （Caffeine 两级缓存、Sentinel、ShardingSphere 等），以及**未经测量**的量化数字
  （如「RT 从 800ms 降至 120ms」「单机支撑 5w+ QPS」）。
  **照抄这类建议等于在简历上写自己没做过的事，面试追问必然暴露。**

  **根因（两处叠加）**：

  1. `resume-analysis-user.st` 的「技术优化基准」表前写着
     **"在提出优化建议时，请务必对标以下高标准场景及表达逻辑"** ——
     "务必对标"把一份**表达参考**变成了**补强清单**，
     推动 LLM 把表里的组件往任何简历上套。
  2. `resume-analysis-system.st` 的约束中明确写着
     **"但可以基于现有背景建议合理的量化指标"** ——
     直接授权模型给出未经测量的数字。

  **修复**：

  - 系统 Prompt 约束改为：**严禁编造量化指标**，只能指出「此处缺少量化」
    并说明应该量化什么；量化数据须由候选人自行实测填入。
  - 系统 Prompt 增加约束：区分**表达优化**（针对已有技术点，可给改写句）与
    **技术补强**（针对没有的技术方向，只能作为可选方向，不得改写成简历句子）。
  - 用户 Prompt「技术优化基准」标题改为**"表达参考，不是补强清单"**，
    去掉"务必对标"的表述。
  - 新增「建议写作约束」章节，明确两类建议的允许写法、禁止编造数字的细则。
  - 工作流「深度重写」改为**严格基于简历中已有的信息**，
    不得引入未出现的组件或未经测量的数字。

  **验证**（同一份简历，改动前后各跑一次分析）：

  | 指标 | 改动前 | 改动后 |
  |---|---|---|
  | 建议中出现的量化数字 | `800ms` `120ms` `1.2s` `60%` `90%` `80ms` `2w+` | **0 个** |

  建议质量同时提升——从「你应该引入 X」变为指出简历本身的问题，例如：

  > 「技能部分提到『分布式锁』但项目中无任何落地点；JVM 仅写『了解 jstack、jmap 用途』，
  > 缺少一次真实的排查经历支撑，面试追问时容易…」

  对确需补强的方向，措辞已变为可选且带明确边界：

  > 「技术补强（可选方向，非必做）：…**完成后即可作为真实项目点写入；
  > 未实践前不要在简历中提及。**」

- **「重新分析」不会重新解析文档**（2026-10-01）

  **现象**：对已存在的简历点「重新分析」，只会拿数据库中**已存的旧解析文本**
  重跑 LLM。解析器升级或修复后，已有简历永远拿不到新的解析结果——
  **我们自己在验证 Tika 升级时就踩过这个坑，最后只能「删除 + 重新上传」绕过去。**

  **根因**：`AnalyzeStreamConsumer` 的兜底逻辑：

  ```java
  String resumeText = resume.getResumeText();
  if (isBlank(resumeText)) {          // ← 只有文本为空才重新解析
      resumeText = parseService.downloadAndParseContent(...);
  }
  ```

  这段兜底本意是「历史数据正文为空时从对象存储恢复」，
  但也顺带导致了「重新分析无法刷新解析结果」。

  **修复**：`ResumeUploadService.reanalyze` 在重置状态时一并清空 `resumeText`，
  使其走完整的「重新下载 → 解析 → 分析」链路。

  **取舍说明**：这不是「把 bug 绕过」，而是明确语义——**「重新分析」= 完整重来一遍**。
  解析耗时实测约 500ms，对该操作可忽略；原始文件仍在对象存储中，
  解析失败也不会丢失源文件。

  **验证**：对 `resumeId=4` 调用 `/reanalyze`，
  `DocumentParseService` 日志由 0 条变为出现「开始解析文件 / 文件解析成功」，
  分析完成后 `analyze_status = COMPLETED` 且生成新的分析记录。

- **Embedding 模型被误判为聊天模型，导致向量化失败**（2026-10-01）

  **现象**：将 embedding 模型换为 `Qwen/Qwen3-Embedding-4B` 后，
  向量化立即失败，异常定位在 `LlmProviderRegistry.createEmbeddingModel`。

  **根因**：`looksLikeChatModel` 这个「防止把聊天模型误配成 Embedding 模型」
  的守卫，采用**厂商前缀**判定：

  ```java
  lower.startsWith("qwen")   // "qwen/qwen3-embedding-4b" 命中 → 误判
  ```

  它只看模型名开头，会把同一厂商的 **Embedding** 模型一并误伤。
  `Qwen/Qwen3-Embedding-4B` 以小写 `qwen` 开头，于是被当成聊天模型直接拒绝。

  **为什么之前没暴露**：此前使用的 `BAAI/bge-m3` 恰好不匹配任何前缀，
  这个缺陷被选型"绕过去了"。它是**一直存在**的，只是换模型才触发。

  **修复**：显式排除优先于前缀判定——模型名中包含 `embed` 的一律视为
  Embedding 模型（覆盖 `Qwen3-Embedding-*`、`text-embedding-*`、`embedding-3` 等）。
  该方法改为包级可见以便直接单元测试。

  **验证**：
  - 新增 13 个参数化单元测试：7 个 Embedding 模型名（含此前被误判的
    `Qwen/Qwen3-Embedding-4B`）不应被标记，6 个聊天模型名应被标记
  - 端到端：切换为 `Qwen/Qwen3-Embedding-4B`（显式配置 `dimensions: 1024`，
    该模型原生 2560 维、属 MRL 模型）后，`vector_status = COMPLETED`，
    向量写入成功；RAG 问答返回的答案准确覆盖文档三个要点

  **换模型的连带处理**：向量空间不兼容，已清空 `vector_store` 中由
  `bge-m3` 产生的旧向量并重新向量化。

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
