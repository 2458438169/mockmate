# MockMate

> AI 模拟面试与 RAG 知识库平台 · 基于 Spring AI 2.0 + pgvector 的 Agent 应用实践项目

---

## 目录

- [项目简介](#项目简介)
- [功能特性](#功能特性)
- [系统架构](#系统架构)
- [技术栈](#技术栈)
- [快速开始](#快速开始)
- [许可证](#许可证)

---

## 项目简介

一个集**知识库 RAG 问答**、**简历分析**、**模拟面试**于一体的后端系统。核心是标准的检索增强生成链路：

```
上传文档 → Tika 解析 → 文本分块 → 向量化 → 存入 pgvector
                                              ↓
用户提问 → 问题向量化 → 相似度检索 → 拼 Prompt → LLM 生成 → SSE 流式返回
```

**为什么做这个项目**：目标是学习 Agent 应用开发，通过一个完整可运行的系统把核心能力跑通——RAG 检索增强、Prompt 工程、结构化输出、工具调用与 Skill 扩展、异步任务编排。选这个方向是因为它同时覆盖两条线：**Agent 应用开发**（Spring AI、RAG、MCP、Skill、Advisor）和 **Java 后端工程实践**（Redis Stream 异步、分布式限流、虚拟线程），而不是一个孤立的「调一次大模型 API」的示例。

---

## 功能特性

| 模块 | 能力 |
|---|---|
| **知识库管理** | 多格式文档上传（PDF / Word / TXT 等）、Apache Tika 统一解析、文本分块、向量化入库 |
| **RAG 问答** | 向量相似度检索 + Prompt 增强 + SSE 流式回答，支持多轮上下文与查询改写 |
| **简历管理** | 简历上传与解析、结构化信息提取 |
| **模拟面试** | 基于知识库或简历生成面试题、结构化评分、面试报告生成 |
| **异步任务** | 文档向量化、简历分析等耗时操作经 Redis Stream 异步解耦，支持失败重试与状态流转 |
| **限流防护** | 基于 Redis + Lua 的多维度分布式限流 |
| **Prompt 注入防御** | 多层纵深防御，含输入清洗与安全 Advisor |
| **多模型配置** | 支持接入多个 OpenAI 兼容服务，聊天模型与向量模型可分别指定 |

---

## 系统架构

```
┌──────────────┐
│  前端 React   │
└──────┬───────┘
       │ REST / SSE
┌──────▼───────────────────────────────────┐
│         Spring Boot 4.1 (Java 25)         │
│                                           │
│  ┌─────────────┐    ┌──────────────────┐  │
│  │ Spring AI   │    │  Redis Stream    │  │
│  │ ChatClient  │    │  异步任务消费者    │  │
│  └──────┬──────┘    └────────┬─────────┘  │
└─────────┼────────────────────┼────────────┘
          │                    │
   ┌──────▼──────┐      ┌──────▼──────┐
   │  LLM 服务    │      │   Redis 7   │
   │ (OpenAI兼容) │      └─────────────┘
   └─────────────┘
   ┌─────────────┐      ┌─────────────┐
   │ Embedding   │      │  PostgreSQL │
   │   服务       │      │  +pgvector  │
   └─────────────┘      └─────────────┘
                        ┌─────────────┐
                        │   RustFS    │
                        │ (S3 兼容)    │
                        └─────────────┘
```

---

## 技术栈

| 层次 | 技术 | 版本 |
|---|---|---|
| 语言 | Java | 25（LTS） |
| 应用框架 | Spring Boot | 4.1 |
| AI 集成 | Spring AI | 2.0 |
| 数据存储 | PostgreSQL + pgvector | PG 16 |
| 缓存 / 消息 | Redis + Redisson | Redis 7 |
| 对象存储 | RustFS（S3 协议兼容） | 1.0 |
| 文档解析 | Apache Tika | 2.9 |
| 对象映射 | MapStruct | 1.6 |
| 构建工具 | Gradle | 9.6 |
| 前端 | React + TypeScript | 18.3 / 5.6 |

### 选型说明

**为什么用 RustFS 而不是 MinIO**

MinIO 社区版仓库已于 2026 年初归档，README 明确标注 *no longer maintained*，官方 Docker 镜像也已下架。RustFS 是 Apache-2.0 许可的 S3 协议兼容实现，客户端代码与 MinIO 完全一致（同一套 AWS S3 SDK），迁移成本接近零。

**为什么用 pgvector 而不是专用向量数据库**

业务数据与向量数据放在同一个 PostgreSQL 实例里，避免引入 Milvus 这类独立组件带来的部署与运维成本。当前数据规模下 pgvector 的检索性能足够，需要时再拆。

**为什么聊天模型和向量模型分开配置**

两者在 RAG 链路中完全解耦：向量模型负责把文档和问题转成向量，聊天模型拿到的是检索后的**纯文本片段**，互不影响。因此可以独立选型。但**向量模型一旦选定就锁定**——换模型意味着向量空间不兼容，必须全量重新向量化。

**为什么向量维度是 1024**

建表 SQL 中 `vector(1024)` 固定，选用的 `bge-m3` 原生即为 1024 维，无需截断、无需改表。另外 `bge-m3` 未做 Matryoshka 训练，不能靠降维压缩存储。

---

## 快速开始

### 前置条件

| 依赖 | 版本要求 | 说明 |
|---|---|---|
| JDK | **25** | `build.gradle` 中 `JavaLanguageVersion.of(25)`；Gradle 守护进程本身需要 JVM 17+ |
| Docker Desktop | 最新版 | Windows 下需启用 **WSL 2 后端** |
| Gradle | 不需要 | 使用项目自带的 wrapper |

> 若系统 `JAVA_HOME` 指向其它版本（如为兼容老项目保留的 JDK 8），**不必修改全局变量**，在项目根目录创建 `gradle.properties` 指定即可：
> ```properties
> org.gradle.java.home=D:/Soft/Java/jdk-25
> ```
> `settings.gradle` 已配置 foojay-resolver 插件，本机缺少 JDK 25 时会自动下载。

### 1. 准备环境变量

```bash
cp .env.example .env
```

编辑 `.env`，必填项：

```bash
# 模型服务 API Key
# Provider 密钥加密密钥（生成后必须保持不变，否则已存密钥将无法解密）
APP_AI_CONFIG_ENCRYPTION_KEY=<随机长字符串>

# 注意：.env.example 中未提供此项，缺失会导致 compose 直接报错退出
RUSTFS_RPC_SECRET=<随机长字符串>
```

### 2. 启动依赖服务

```bash
docker compose -f docker-compose.dev.yml up -d
```

三个容器应为 `Up (healthy)`：`interview-postgres`、`interview-redis`、`interview-rustfs`。

> compose 使用**命名卷**而非绑定挂载，数据存放在 Docker 卷中。

### 3. 创建对象存储桶

浏览器打开 <http://localhost:9001>，使用 `.env` 中的 `RUSTFS_ACCESS_KEY` / `RUSTFS_SECRET_KEY` 登录，创建名为 `interview-guide` 的桶。

> ⚠️ 桶名必须与 `.env` 中的 `APP_STORAGE_BUCKET` **完全一致**，否则文件上传会返回 403。

### 4. 启动应用

```bash
./gradlew :app:bootRun
```

看到 `Started App in XX seconds` 即启动成功，服务监听 **8080**。

### 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `Gradle requires JVM 17 or later` | 守护进程使用了低版本 JDK | 用 `gradle.properties` 指定 JDK 25 |
| `compose file ... is invalid` | 未在项目根目录执行 | 先 `cd` 到项目目录；**Windows CMD 切换盘符需加 `/d`** |
| 文件上传返回 403 | 桶名与配置不一致 | 核对 RustFS 控制台中的桶名 |

---

## 许可证

本项目遵循 [AGPL-3.0](LICENSE) 许可证。
