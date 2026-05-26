# 轻智文档 API 架构优化与接口改进方案

## 一、项目当前情况

当前系统已具备以下核心功能：

- 微信登录鉴权
- 文件上传
- PDF/Word 文档转换
- AI 文档分析
- 历史记录
- SSE 流式输出

目前已经具备 MVP（最小可运行产品）能力，但距离真正可扩展、可上线的 SaaS 架构仍存在一些问题。

---

# 二、当前存在的问题

| 模块 | 当前问题 |
|---|---|
| 接口设计 | AI 与转换接口分散，扩展性较差 |
| 任务系统 | 状态过少，无法支持排队与取消 |
| AI 接口 | 同步阻塞，耗时任务容易超时 |
| 文件系统 | 缺少文件生命周期管理 |
| 安全性 | 缺少限流、格式校验 |
| 架构 | 尚未引入 MQ、Redis |
| 错误处理 | 错误码不规范 |
| 扩展性 | 后续 OCR、AI 对话不方便扩展 |

---

# 三、核心架构改进方案

---

# 1. 统一任务中心（核心优化）

## 当前设计

```text
/convert/start
/ai/analyze
```

问题：

- 接口职责分散
- 后期功能扩展困难
- 任务状态不统一

---

## 优化方案

统一为：

```text
POST /task/create
GET  /task/status/{taskId}
```

---

## 请求示例

```json
{
  "taskType": "pdf_to_word",
  "fileId": "uuid-123"
}
```

---

## taskType 设计

```text
pdf_to_word
word_to_pdf
pdf_to_markdown
ai_summary
ai_points
ocr_extract
```

---

## 优点

- 统一任务模型
- 后期扩展方便
- 更适合 MQ 与异步 Worker
- AI 与转换逻辑统一

---

# 2. 完善任务状态系统

## 当前状态

```text
processing
success
failed
```

无法支持：

- 排队
- 超时
- 用户取消
- 重试

---

## 推荐状态

```text
pending
queued
processing
success
failed
cancelled
timeout
```

---

## 状态说明

| 状态 | 含义 |
|---|---|
| pending | 任务已创建 |
| queued | 等待 Worker 处理 |
| processing | 正在执行 |
| success | 执行成功 |
| failed | 执行失败 |
| cancelled | 用户取消 |
| timeout | 超时 |

---

# 3. AI 接口异步化

## 当前问题

当前：

```text
POST /ai/analyze
```

直接返回结果。

但 AI：

- GPT 调用
- OCR
- 文档解析

通常耗时较长。

---

## 优化方案

采用异步任务模型：

```text
创建任务
    ↓
返回 taskId
    ↓
SSE / 轮询获取结果
```

---

## 推荐接口

### 创建 AI 任务

```text
POST /task/create
```

```json
{
  "taskType": "ai_summary",
  "fileId": "uuid-123"
}
```

---

### 查询状态

```text
GET /task/status/{taskId}
```

---

### SSE 流式输出

```text
GET /task/stream/{taskId}
```

---

# 4. 文件系统优化

---

# 4.1 增加文件元数据

## 当前返回

```json
{
  "fileId": "",
  "fileName": "",
  "url": ""
}
```

---

## 推荐返回

```json
{
  "fileId": "uuid",
  "fileName": "论文.pdf",
  "fileSize": 102400,
  "fileType": "application/pdf",
  "expireAt": "2026-05-13T12:00:00",
  "url": "https://xxx.com/file.pdf"
}
```

---

# 4.2 文件生命周期管理

## 当前问题

上传文件不会自动删除。

长期运行：

- OSS 成本增加
- 本地磁盘爆满

---

## 优化方案

增加：

```text
临时文件有效期：24h
结果文件有效期：7d
```

定时任务自动清理。

---

# 4.3 文件安全校验

## 增加：

### MIME 校验

```text
application/pdf
```

---

### 后缀校验

```text
.pdf
.docx
```

---

### 文件真实内容检测

防止：

```text
.exe 改名 .pdf
```

---

# 五、安全系统优化

---

# 5.1 增加限流系统

## 当前问题

AI 接口可能被恶意刷爆。

---

## 推荐方案

### IP 限流

```text
1分钟最多 20 次
```

---

### 用户限流

```text
每日 AI 次数限制
```

---

## 推荐技术

```text
Redis + Lua
```

---

# 5.2 Token 双 Token 机制

## 当前问题

Token 过期后需要重新登录。

---

## 优化方案

采用：

```text
accessToken
refreshToken
```

---

# 六、错误码系统优化

---

# 当前问题

目前只有：

```text
400
500
```

错误粒度不够。

---

# 推荐业务错误码

| 错误码 | 含义 |
|---|---|
| FILE_TOO_LARGE | 文件过大 |
| UNSUPPORTED_FORMAT | 文件格式不支持 |
| TASK_NOT_FOUND | 任务不存在 |
| TOKEN_EXPIRED | Token 已过期 |
| AI_SERVICE_ERROR | AI 服务异常 |

---

# 推荐返回格式

```json
{
  "success": false,
  "code": "FILE_TOO_LARGE",
  "message": "文件超过20MB"
}
```

---

# 七、Redis 与 MQ 引入

---

# 7.1 Redis

## 用途

- Token 缓存
- 限流
- SSE 状态
- 任务缓存

---

# 7.2 MQ（RabbitMQ）

## 当前问题

Web 服务直接处理转换任务。

高并发时：

- 接口阻塞
- CPU 飙升
- 转换卡顿

---

## 推荐架构

```text
用户请求
    ↓
SpringBoot API
    ↓
RabbitMQ
    ↓
Worker 消费任务
    ↓
LibreOffice / AI
```

---

## 优点

- 解耦
- 支持高并发
- 支持失败重试
- 支持任务排队

---

# 八、文档转换实现方案

---

# 推荐方案：LibreOffice

## 原因

- 免费
- 稳定
- 企业常用
- 支持 Office 全格式

---

## Java 调用方式

```java
ProcessBuilder pb = new ProcessBuilder(
    "soffice",
    "--headless",
    "--convert-to",
    "pdf",
    inputFile
);
```

---

## 支持格式

| 输入 | 输出 |
|---|---|
| doc/docx | pdf |
| xls/xlsx | pdf |
| ppt/pptx | pdf |

---

# 九、后期扩展方向

---

# 9.1 OnlyOffice 在线预览

支持：

- 在线编辑
- 在线预览
- 协同编辑

---

# 9.2 OCR

支持：

```text
图片转文字
PDF OCR
```

---

# 9.3 AI 文档对话

例如：

```text
“总结第三章”
“提取重点”
“帮我生成 PPT”
```

---

# 9.4 WebSocket 实时通知

任务完成后：

```text
主动推送
```

而不是轮询。

---

# 十、推荐演进路线

---

# 第一阶段（当前）

```text
微信小程序
    ↓
SpringBoot
    ↓
LibreOffice
```

目标：

- 上传
- 转换
- AI 总结

---

# 第二阶段

引入：

```text
Redis
RabbitMQ
OSS
```

目标：

- 异步任务
- 高并发
- 限流

---

# 第三阶段

升级：

```text
OnlyOffice
OCR
WebSocket
AI Office
```

目标：

- 在线 Office 平台
- AI 文档助手

---

# 十一、最终目标架构

```text
微信小程序
    ↓
API Gateway
    ↓
SpringBoot API
    ↓
Redis
    ↓
RabbitMQ
    ↓
Worker
    ↓
LibreOffice / AI
    ↓
OSS
```

---

# 十二、项目亮点

该项目后期可发展为：

# AI 智能文档处理平台

具备：

- 微信小程序
- JWT 鉴权
- OSS 文件系统
- 异步任务
- SSE 流式输出
- AI 文档分析
- Office 文档转换
- 在线文档处理

属于具备真实业务场景的综合型项目。