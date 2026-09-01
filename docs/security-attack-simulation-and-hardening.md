# newagent 安全攻击面审计与加固建议

> 审计日期：2026-08-29  
> 范围：`C:\Users\xiaohongfu\IdeaProjects\newagent`  
> 方法：源码静态审计 + 非破坏性本地 HTTP 探针。未进行数据删除、凭据读取、外网扫描、并发压测或认证绕过。

## 一、已交付攻击模拟脚本

脚本：`scripts/security_probe.py`

默认仅允许访问 `localhost / 127.0.0.1 / ::1`，串行、低频、非破坏性地覆盖：

1. 未认证访问聊天、RAG 调试、文档、评测历史和 Actuator；
2. 错误管理员 API Key 越权；
3. topK、threshold、limit、topKs 等异常边界；
4. `../probe.txt`、`..\\probe.txt` 等上传文件名路径穿越；
5. 非白名单 Origin 的 CORS 反射；
6. 安全响应头缺失与错误响应中的密钥、堆栈信息迹象。

运行方式：

```bash
cd /c/Users/xiaohongfu/IdeaProjects/newagent
python scripts/security_probe.py --json security-probe-report.json
```

说明：本次实际运行因本地接口部分请求长时间不返回而主动终止，未形成完整动态报告；脚本已通过 Python 语法编译。建议在服务健康、外部模型依赖隔离后再运行，或先给反向代理配置 3–5 秒超时。

## 二、静态审计结论

### P0：管理员 API Key 采用明文等值比较，且是全局长期凭据

位置：`AdminApiKeyFilter`。

风险：

- `configuredApiKey.equals(providedApiKey)` 不是恒定时间比较，理论上存在时序侧信道；
- 单个长期 Key 直接授予 `ROLE_ADMIN`，一旦日志、终端历史、截图或 CI 泄露，可调用评测、导入、缓存清理、文档删除等全部管理接口；
- 无主体 ID、权限细分、有效期和轮换记录，审计能力不足。

建议：

1. 短期改为 `MessageDigest.isEqual()` 比较 UTF-8 字节；
2. Key 只存 SHA-256/HMAC 摘要，不存明文；
3. 增加 keyId、创建时间、失效时间、权限域和轮换机制；
4. 中期以短时效管理员 JWT/OIDC 代替静态 Key；
5. 管理接口按 `EVALUATION_ADMIN / KNOWLEDGE_ADMIN / DATA_ADMIN` 拆分权限，而非统一 `ROLE_ADMIN`。

### P0：注册和登录端点缺少明确的反自动化防护

位置：`/api/v1/auth/login`、`/api/v1/auth/register`。

风险：暴力猜解、撞库、用户名枚举、批量注册和资源消耗。当前响应中“Invalid credentials”已避免直接区分用户名/密码，但注册接口会返回用户名存在，仍可被枚举。

建议：

- 登录按 `IP + username` 双维度限流，例如 5 次/分钟、连续失败指数退避；
- 注册按 IP/设备限流并增加邮箱验证；
- 高风险时引入验证码，而非所有请求常态启用；
- 登录失败统一响应时间，审计日志不得记录密码或完整 Token；
- 生产环境默认关闭开放注册，或改为邀请制。

### P0：评测接口接受服务端文件路径

位置：`/api/v1/agent/evaluate`、`/compare`、`/export` 的 `datasetPath`。

风险：虽然接口要求 ADMIN，但管理员 Key 泄露后，攻击者可尝试读取任意本地路径、探测文件是否存在、触发解析器处理非预期文件，形成路径穿越/本地文件读取/资源消耗链。

建议：

- 禁止客户端提交任意路径，改为提交预注册 `datasetId`；
- 若必须支持路径，仅允许 `evaluation-datasets` 根目录，使用 `toRealPath()` 后验证 `startsWith(allowedRoot.toRealPath())`；
- 拒绝符号链接、绝对路径、`..`、UNC 路径；
- 限制文件扩展名、文件大小、记录数和单次评测最大耗时；
- 评测/导出接口增加任务队列、并发上限和取消能力。

### P1：上传限制为 100MB，仍可能造成内存、磁盘和解析器 DoS

位置：`application.yml` multipart；`DocumentService`/暂存与异步解析链。

已有优点：文件名经过斜杠归一化和 basename 截取；使用临时文件暂存；有格式白名单、幂等处理和 ADMIN 认证。

建议：

- 面向知识库文档将上限降为业务实际值（如 10–20MB），并设置请求总大小；
- 限制 PDF 页数、压缩比、文本字符数、解析时间、chunk 数；
- 对 ZIP/Office 文件防 Zip Bomb（解压后大小、条目数、嵌套层数）；
- 不信任客户端 Content-Type，使用 magic bytes/Tika 双重识别；
- 临时目录设置配额、定期清理和不可执行权限；
- 异步队列设置每用户/每租户并发与积压上限。

### P1：CORS/CSRF 配置需要生产环境不变量校验

位置：`SecurityConfig`：CSRF 全局禁用；CORS 来源来自配置。

JWT/API Key 放 Header 的无状态 API 关闭 CSRF 通常合理，但必须确保：

- JWT 不写入自动携带的 Cookie；
- `allowCredentials=true` 时禁止 `*`、通配 Origin 或动态原样反射 Origin；
- 生产环境启动时对 `allowedOrigins` 做 fail-fast 校验；
- 管理后台若以后改用 Cookie Session，应为该安全链重新启用 CSRF。

### P1：Actuator 指标存在误配置后公开暴露的风险

当前代码通过 `observability.publicMetrics` 动态决定是否公开 `/actuator/metrics/**` 和 `/actuator/prometheus`，设计上可控，但生产环境误设即可暴露接口名、JVM、数据库池、调用量等侦察信息。

建议：

- 生产默认 `publicMetrics=false`；
- Prometheus 通过内网、VPN 或独立 management port 访问；
- 仅暴露 health/info/prometheus 必要端点；
- health 对公网仅返回 UP/DOWN，不返回组件详情。

### P1：缺少统一请求预算与超时边界

本次探针中部分本地请求长时间未返回，说明安全失败模式可能被外部模型、向量库、数据库或大查询拖住。

建议：

- Controller/API 网关配置请求超时；
- WebClient、Milvus、数据库、模型调用分别设置连接/读取/总超时；
- 对 chat、RAG debug、evaluate、search 设最大输入长度、topK 上限与总 Token/文档预算；
- 超时统一返回 504/503，取消下游任务并释放幂等锁；
- 配置 Bulkhead，避免慢请求耗尽 Tomcat/Reactor 线程。

### P2：参数边界应集中校验

重点字段：`topK`、`threshold`、`limit`、`topKs`、question/query 长度、profilesJson 大小。

建议：

- `topK`：1–100；`limit`：1–100；`threshold`：有限数且 0–1；
- `topKs`：去重、升序、每项 1–100、最多 10 项；
- question/query：非空并限制 UTF-8 字节和字符数；
- profilesJson/dataset：限制请求体和数组规模；
- 对 NaN、Infinity、整数溢出返回 400，不进入检索或评测服务。

### P2：JWT 可进一步加固

已有优点：Secret 启动时强制至少 32 字节；Token 有 `jti`、`iat`、`exp`；登出有撤销列表。

建议：

- 增加 issuer、audience 并在验证时强制校验；
- 显式限定签名算法；
- Access Token 缩短至 15–30 分钟，增加轮换式 Refresh Token；
- JWT Secret 定期轮换，使用 `kid` 支持灰度；
- Redis 撤销记录故障时明确采用 fail-closed 还是风险可接受的降级策略；
- 日志仅记录 jti 哈希或用户标识，禁止完整 Token。

### P2：建议补充通用安全响应头

建议在 Spring Security 或 Nginx 统一配置：

- `Content-Security-Policy`（管理页）；
- `X-Content-Type-Options: nosniff`；
- `X-Frame-Options: DENY` 或 CSP frame-ancestors；
- `Referrer-Policy: no-referrer`；
- HTTPS 环境启用 `Strict-Transport-Security`；
- API 响应 `Cache-Control: no-store`（登录、Token、管理接口）。

## 二点五、已落地加固（2026-09-01）

以下加固已实现并通过相关单元测试：

| 加固项 | 落地方式 | 验证 |
|---|---|---|
| 评测数据路径沙箱化 | `RagEvaluationService`：强制配置 dataset-directory，相对/绝对路径经 `normalize()+toRealPath()` 双重校验，拒绝逃逸与符号链接；文件大小 ≤20MB、样本 ≤10000；错误不回显客户端路径 | RagEvaluationServiceTest ✅ |
| TopK/参数边界 | topKs 每项 1-100、最多 10 项；profilesJson ≤64KB；内部调用非法值安全回退默认 | RagEvaluationServiceTest ✅ |
| 管理 Key 恒定时间比较 | `AdminApiKeyFilter`：`MessageDigest.isEqual` + 空值/超长(512)拒绝 | 编译+回归 ✅ |
| 上传大小双层预算 | multipart 20MB（可配）+ Service 层 `max-upload-bytes` 20MB（可配）+ 空文件拒绝，超限不落盘不排队 | DocumentServiceTest ✅ |
| JWT issuer/audience | 生成携带 `iss`/`aud`（可配，默认 newagent/newagent-api）；解析 `requireIssuer/requireAudience` 强制校验，错 issuer 拒绝 | JwtTokenProviderTest ✅（11 项）|
| 评测并发信号量 | `evaluation.max-concurrent-runs`（默认 2）：同时运行评测数超限快速失败，防管理员接口被滥用耗尽 Milvus/模型 | RagEvaluationServiceTest ✅ |
| 文档 Magic Bytes 检测 | PDF 校验 `%PDF`、DOCX 校验 ZIP 头 `PK\x03\x04`，纯文本（txt/md）不误伤；与扩展名校验互补 | DocumentServiceTest ✅ |

认证/安全回归套件（AuthControllerTest、ApiProtectionFilterTest、RateLimitDetectorTest、SecurityConfigTest、JwtAuthenticationFilterTest、PersistentIdempotencyServiceTest、JwtTokenProviderTest）全部通过。

## 三、建议新增的自动化安全测试

1. `SecurityConfigTest`：遍历所有 Controller 路由，断言每条路由的最小角色，避免新增接口遗漏授权；
2. `AdminApiKeyFilterTest`：空 Key、错误 Key、重复 Header、超长 Header、正确 Key、Key 未配置；
3. `AuthControllerTest`：登录限流、注册限流、统一错误和并发注册；
4. `RagEvaluationServiceTest`：绝对路径、`..`、符号链接、UNC、超大数据集；
5. `DocumentServiceTest`：伪造扩展名、Magic Byte 不符、Zip Bomb 元数据、超页数 PDF、路径穿越文件名；
6. `ApiProtectionFilterTest`：NaN/Infinity、超长 query、巨大 topK、慢请求、同用户并发；
7. CI：OWASP Dependency-Check/Snyk、Gitleaks、CodeQL、SBOM（CycloneDX）和容器镜像 Trivy 扫描。

## 四、建议落地顺序

| 优先级 | 工作项 | 验收标准 |
|---|---|---|
| P0 | 登录/注册限流 | 暴力尝试返回 429；正常用户无明显影响 |
| P0 | datasetPath 沙箱化 | 任意路径、`..`、符号链接均 400/403 |
| P0 | 管理 Key 摘要存储与轮换 | 无明文持久化；可单 Key 吊销；审计可追踪 |
| P1 | 上传与解析资源预算 | 超限请求快速拒绝，不创建后台任务 |
| P1 | 请求/下游超时和 Bulkhead | 外部依赖挂起时接口在预算内返回并释放资源 |
| P1 | CORS/Actuator 生产校验 | 危险配置启动失败或 CI 阻断 |
| P2 | ~~JWT issuer/audience~~/短时效 | 错 issuer/audience 的 Token 均拒绝（✅已落地）；短时效/Refresh Token 未落地 |
| P2 | 安全响应头与 CI 扫描 | 自动化测试与流水线持续验证 |

## 五、项目面试表达建议

可以将本次治理概括为：

> 我不是只做功能测试，而是按攻击面建立了本地非破坏性安全探针，覆盖未授权访问、管理 Key 越权、异常参数、路径穿越、恶意上传、CORS 与 Actuator 暴露；源码审计后优先识别出服务端文件路径、静态管理员 Key、认证限流和资源预算四类风险，并给出可自动化验收的 P0/P1 加固清单。安全测试默认仅允许 localhost，避免测试工具被误用于外部目标。
