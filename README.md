# AI 点评 — 智能本地生活服务平台

<p align="center">
  <strong>🚀 Spring Boot + LangChain4j + Redis 构建的智能点评系统</strong>
  <br>
  融合传统点评功能与 AI Agent 架构，让每一家店铺都拥有专属智能顾问
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-11-blue.svg" alt="Java 11">
  <img src="https://img.shields.io/badge/Spring_Boot-2.3.12-green.svg" alt="Spring Boot 2.3.12">
  <img src="https://img.shields.io/badge/LangChain4j-0.30.0-orange.svg" alt="LangChain4j 0.30.0">
  <img src="https://img.shields.io/badge/Redis-Stack-red.svg" alt="Redis Stack">
  <img src="https://img.shields.io/badge/MySQL-5.x-lightgrey.svg" alt="MySQL">
</p>

---

## ✨ 核心亮点

<table>
<tr>
<td width="50%">

### 🏪 经典点评功能
- **探店笔记** — 图文发布、热门推荐、关注流、点赞互动
- **店铺发现** — 分类浏览、地理距离排序、评分筛选
- **社交关系** — 关注取关、共同关注、好友动态
- **优惠秒杀** — 高并发 Lua 原子下单、Redis Stream 异步落库
- **每日签到** — Redis Bitmap 连续签到统计

</td>
<td width="50%">

### 🤖 AI Agent 架构
- **智能店铺顾问** — 基于 LLM 的自然语言交互，13 个专业工具自主调用
- **Function Calling** — LLM 按需选择工具：查店、对比、推荐、问答
- **RAG 知识增强** — 向量检索 + 质量评估，精准回答业务问题
- **多层级降级** — LLM → 规则引擎，Redis Stack → 内存向量库
- **对话记忆管理** — 5 类会话隔离，差异化 TTL，Redis 持久化

</td>
</tr>
</table>

---

## 🧠 Agent 架构深度解析

本项目的核心创新在于将 **LangChain4j Agent 模式** 落地到真实业务场景。LLM 不再只是"聊天机器人"，而是具备**自主决策与工具调用能力**的智能体。

### 整体架构

```
┌──────────────────────────────────────────────────────────────────┐
│                          👤 用户请求                               │
│            "帮我对比一下 1 号店和 2 号店的服务态度"                   │
└────────────────────────┬─────────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────────┐
│                    🤖 ShopAIService (Agent 门面)                   │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  System Prompt: 角色定义 + 13 个工具清单 + 8 条行为准则       │  │
│  │  @MemoryId → ChatMemoryStore → 上下文窗口 (最近 20 轮)       │  │
│  └────────────────────────────────────────────────────────────┘  │
└────────────────────────┬─────────────────────────────────────────┘
                         │
          ┌──────────────┼──────────────┐
          ▼              ▼              ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────────────┐
│   🔧 Tools    │ │   📚 RAG     │ │   💬 Chat Memory     │
│              │ │              │ │                      │
│ ShopTool     │ │ Content      │ │ RedissonChatMemory   │
│ • 基础摘要    │ │ Retriever    │ │ Store                │
│ • 详细分析    │ │              │ │                      │
│ • 质量筛选    │ │ Embedding    │ │ 5 类会话隔离:         │
│ • 智能问答    │ │ Model        │ │ 摘要/问答/对比/       │
│ • 多店对比    │ │              │ │ 推荐/AI对话           │
│ • 个性化推荐  │ │ Redis Stack  │ │                      │
│ • 意图路由    │ │ (Vector DB)  │ │ 差异化 TTL 策略       │
│              │ │              │ │                      │
│ Document     │ │              │ │                      │
│ Management   │ │              │ │                      │
│ Tool         │ │              │ │                      │
│ • 文档列表    │ │              │ │                      │
│ • 质量过滤    │ │              │ │                      │
│ • 状态查询    │ │              │ │                      │
└──────────────┘ └──────────────┘ └──────────────────────┘
         │              │              │
         ▼              ▼              ▼
┌──────────────────────────────────────────────────────────────────┐
│                        🗄️ 基础设施层                               │
│  MySQL ←→ Redis(6379) ←→ Redis Stack(6380) ←→ 本地文件系统        │
└──────────────────────────────────────────────────────────────────┘
```

### Agent 决策流程

```
用户输入 "1号店怎么样？"
        │
        ▼
┌──────────────────┐
│  LLM 分析意图     │  ← System Prompt 引导
│  识别: 店铺查询   │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│  选择工具         │  ← Function Calling
│  → getShopBasic  │  从 13 个工具中自动匹配
│    Summary(1)    │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│  工具执行         │  ← 双层限流保护
│  查询 MySQL       │     用户日限额 + 时间窗口
│  聚合评论数据     │     线程级调用计数
│  情感分析         │     结果本地缓存
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│  LLM 生成回复     │  ← 基于工具返回的结构化数据
│  自然语言润色     │     质量后处理管道
│  返回用户         │     (去模板化 + 敏感词过滤)
└──────────────────┘
```

### 13 个 Agent 工具一览

| 工具 | 所属 | 能力描述 |
|---|---|---|
| `getShopBasicSummary` | ShopTool | 店铺基础摘要（评分/点评数/关键点/情感倾向） |
| `checkShopExists` | ShopTool | 店铺存在性校验 + 点评数量 |
| `getShopDetailedSummary` | ShopTool | 详细分析（附带记忆上下文） |
| `getShopQualitySummary` | ShopTool | 高质量评论摘要（按点赞数过滤） |
| `askAboutShop` | ShopTool | 指定店铺的智能问答 |
| `compareShops` | ShopTool | 多店铺对比（支持维度筛选） |
| `recommendShops` | ShopTool | 基于偏好的个性化推荐 |
| `smartAnalyzeShop` | ShopTool | **智能路由**：自动检测意图并分发到对应工具 |
| `clearShopQAMemory` | ShopTool | 清除店铺问答记忆 |
| `clearRecommendMemory` | ShopTool | 清除推荐记忆 |
| `getMemoryStats` | ShopTool | 记忆使用统计 |
| `listAllDocuments` | DocumentManagementTool | 列出全部知识库文档 |
| `getDocumentStatistics` | DocumentManagementTool | 知识库聚合统计 |

### Agent 可靠性设计

本项目的 Agent 不是简单的"调 API + 拼 prompt"，而是围绕**生产可靠性**进行了多层加固：

| 保障层 | 机制 | 说明 |
|---|---|---|
| **限流保护** | 用户日限额 + 时间窗口 + 线程计数 | 每个工具独立配置，防止滥用和死循环 |
| **降级策略** | LLM → `AIFallbackService` 规则引擎 | AI 不可用时自动切换关键词匹配 + 模板回复 |
| **向量库容灾** | Redis Stack → `InMemoryEmbeddingStore` | RAG 检索降级不影响基本服务 |
| **记忆容错** | 损坏数据自动清理 | 反序列化失败时刪除脏数据，返回空列表 |
| **质量后处理** | `AIResultQualityService` | 敏感词拦截 + 模板句式清洗 + AI 自引用检测 |
| **会话隔离** | 5 类功能独立 Key | 摘要/问答/对比/推荐/通用对话互不干扰 |

---

## 🏪 经典点评功能

### 探店笔记 (Blog)

用户分享到店体验的核心载体，支持图文发布与互动：

```
POST   /blog             发布笔记（标题 + 正文 + 最多 9 张图片）
GET    /blog/hot          热门笔记（按点赞数排行，分页加载）
GET    /blog/{id}         笔记详情（含作者信息、图片、当前用户是否已赞）
PUT    /blog/like/{id}    点赞/取消赞（Toggle 模式）
GET    /blog/likes/{id}   点赞用户列表（按时间倒序 Top 5）
GET    /blog/of/me        我的笔记
GET    /blog/of/user      查看他人笔记（?id=xxx）
GET    /blog/of/follow    关注流（游标分页，lastId + offset 滚动加载）
```

**Feed 流设计**：关注流采用滚动游标（Scroll Cursor）而非传统分页，解决"刷 Feed 时新内容插入导致重复"的经典问题。用户动态先写入粉丝的"收件箱"（Redis Sorted Set），读取时按时间降序出栈。

### 店铺发现 (Shop)

```
GET    /shop/{id}         店铺详情（Redis 缓存加速）
GET    /shop/{id}/stats   店铺统计（点评数 + 存在性，Caffeine 本地缓存）
POST   /shop              入驻店铺
PUT    /shop              更新店铺信息
```

支持按分类检索 + 地理位置距离排序（基于经纬度 GeoHash），为后续 LBS 场景预留接口。

### 秒杀系统 (Seckill)

高并发优惠券抢购，是系统的**性能核心**：

```
POST   /voucher-order/seckill/{id}     抢购秒杀券
```

**技术架构**：

```
用户请求
  │
  ▼
┌─────────────────────────────┐
│  Redis Lua 脚本 (原子执行)    │
│  ① 校验库存 (seckill:stock)  │
│  ② 一人一单 (SISMEMBER)      │
│  ③ DECR 扣库存               │
│  ④ SADD 记录用户             │
│  ⑤ XADD 写入 Stream          │
└─────────────┬───────────────┘
              │
              ▼
┌─────────────────────────────┐
│  异步消费者 (Redis Stream)    │
│  → 读取消息                   │
│  → 持久化到 MySQL             │
│  → ACK 确认                   │
└─────────────────────────────┘
```

- **原子性**：Lua 脚本保证库存扣减与用户去重的原子操作
- **异步落库**：订单通过 Redis Stream 异步写入 MySQL，解耦快速响应与持久化
- **分布式锁**：`unlock.lua` 实现 Compare-And-Delete 安全解锁，防止误删他线程持有的锁

### 社交关系 (Follow)

```
PUT    /follow/{id}/true     关注用户
PUT    /follow/{id}/false    取消关注
GET    /follow/or/not/{id}   查询关注状态
GET    /follow/common/{id}   共同关注（Redis Set 交集运算）
```

### 每日签到 (Sign-in)

```
POST   /user/sign           签到（Redis Bitmap，按日期分 key）
GET    /user/sign/count     连续签到天数（BITFIELD 位运算）
```

BitMap 存储极大节省内存：一年的签到数据仅需 365 bit ≈ 46 字节/用户。

---

## 🚀 快速开始

### 环境要求

| 组件 | 版本 | 用途 |
|---|---|---|
| JDK | 11+ | 运行环境 |
| MySQL | 5.7+ | 业务数据存储 |
| Redis | 6.x+ | 缓存 + 分布式锁 + Stream + 聊天记忆 (端口 6379) |
| Redis Stack | 7.x+ | 向量存储与检索 (端口 6380，可选，关闭 `rag.enabled` 则不需) |
| Maven | 3.6+ | 项目构建 |

### 启动步骤

```bash
# 1. 克隆项目
git clone https://github.com/makabaka165/AI_dianping.git
cd AI_dianping
git checkout develop

# 2. 初始化数据库
# 执行 src/main/resources/db/hmdp.sql 创建表结构

# 3. 配置 API Key
export DASHSCOPE_API_KEY=your_dashscope_api_key

# 4. 启动 Redis (6379) 和 Redis Stack (6380, 可选)
# Docker 方式:
docker run -d -p 6379:6379 redis:7
docker run -d -p 6380:6379 redis/redis-stack-server:latest

# 5. 构建并启动
mvn clean package -DskipTests
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
```

服务启动后访问 `http://localhost:8081`。

### 关键配置

```yaml
# application.yaml
langchain4j:
  open-ai:
    chat-model:
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1  # 阿里云兼容 OpenAI 协议
      api-key: ${DASHSCOPE_API_KEY}
      model-name: qwen-plus        # 可按需更换为 qwen-max / gpt-4 等

rag:
  enabled: true                    # RAG 功能开关，关闭后仅用 LLM 自身知识

chat:
  memory:
    max-messages: 20               # 每个会话保留的最近消息数（滑动窗口）
```

---

## 📁 项目结构

```
src/main/java/com/hmdp/
├── config/                          # 配置层
│   ├── CommonAIConfig.java          #   AI 全栈装配（模型/嵌入/向量库/RAG/聊天记忆）
│   ├── ChatMemoryKeyManager.java    #   对话记忆 Redis Key 命名策略
│   └── ...
├── controller/                      # REST 接口层
│   ├── ShopSummaryController.java   #   ★ 核心 AI Agent 接口 (/api/shop-summary/ai/*)
│   ├── AITestController.java        #   AI 调试验证接口 (/api/ai/test/*)
│   ├── DocumentManagementController #   知识库文档管理
│   ├── BlogController.java          #   探店笔记
│   ├── VoucherOrderController.java  #   秒杀下单
│   └── ...
├── service/
│   ├── ai/
│   │   ├── ShopAIService.java       #   ★ 生产级 Agent 接口 (13 工具 + RAG)
│   │   └── AIService.java           #   调试用 AI 接口 (无工具)
│   └── impl/
│       ├── AIFallbackService.java   #   AI 降级规则引擎
│       ├── QualityBasedContent      #   向量内容检索器
│       │   Retriever.java
│       ├── DocumentQuality          #   文档质量评分器
│       │   Assessor.java
│       └── ...
├── tools/                           # Agent 工具层
│   ├── ShopTool.java                #   ★ 11 个店铺相关工具 (LLM Function Calling)
│   └── DocumentManagementTool.java  #   5 个知识库管理工具
├── repository/
│   └── RedissonChatMemoryStore.java #   对话记忆 Redis 持久化 (ChatMemoryStore 实现)
├── entity/                          # 数据实体
├── mapper/                          # MyBatis 数据访问
└── utils/                           # 工具类 (分布式锁/雪花ID/拦截器/限流缓存)
```

---

## 🛠️ 技术栈

| 层面 | 技术选型 | 说明 |
|---|---|---|
| **框架** | Spring Boot 2.3.12 | 应用核心框架 |
| **AI 编排** | LangChain4j 0.30.0 | Agent/工具调用/RAG/记忆管理 |
| **LLM** | 通义千问 qwen-plus (OpenAI 兼容协议) | 可替换任意 OpenAI 兼容模型 |
| **Embedding** | text-embedding-v3 (1024 维) | 向量化文本用于 RAG 检索 |
| **ORM** | MyBatis-Plus 3.4.3 | 数据库访问与分页 |
| **缓存** | Redis 7 (Lettuce) + Caffeine 2.9 | 二级缓存：远程 Redis + 本地 Caffeine |
| **分布式** | Redisson 3.13.6 | 分布式锁 / Redis 客户端 |
| **向量库** | Redis Stack (RediSearch) | 向量相似度搜索 |
| **文档解析** | Apache POI / PDFBox / Tika | 多格式文档内容提取 |
| **工具库** | Hutool 5.7.17 / Lombok | 通用工具与简化代码 |

---

## 📖 API 速查

### AI Agent 接口（核心）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/shop-summary/ai/chat` | **通用入口** — 自然语言驱动，Agent 自主选工具 |
| GET | `/api/shop-summary/ai/analyze/{shopId}` | 店铺智能分析 |
| POST | `/api/shop-summary/ai/ask/{shopId}` | 店铺智能问答 |
| POST | `/api/shop-summary/ai/compare` | 多店自然语言对比 |
| POST | `/api/shop-summary/ai/recommend` | 个性化自然语言推荐 |

### 店铺摘要接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/shop-summary/{shopId}` | 基础摘要 |
| GET | `/api/shop-summary/{shopId}/quality` | 高质量评论摘要 |
| POST | `/api/shop-summary/compare` | 结构化对比 |

### AI 调试接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/ai/test/health` | AI 健康检查 |
| POST | `/api/ai/test/sentiment` | 情感分析测试 |
| POST | `/api/ai/test/keywords` | 关键词提取测试 |
| GET | `/api/ai/test/batch` | 全功能批量测试 |
| GET | `/api/ai/test/stress?count=` | 性能压测 |

---

## 🤝 参考与致谢

- 基础点评框架源自 [hm-dianping](https://github.com/huyi612/hm-dianping)（黑马点评实战项目）
- AI Agent 架构参考 [LangChain4j](https://github.com/langchain4j/langchain4j) 官方最佳实践
- 大模型服务由 [阿里云 DashScope](https://dashscope.aliyun.com/) 提供

---

<p align="center">
  <sub>Built with ❤️ using Spring Boot, LangChain4j & Redis</sub>
</p>
