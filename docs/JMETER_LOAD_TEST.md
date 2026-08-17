# JMeter 并发压测手册

## 目标与边界

仓库提供一个最小、可复现的 JMeter 非 GUI 测试计划：

- `health` 场景压测 `GET /api/v1/agent/health`，只验证应用 HTTP 层基线，不访问 MySQL、Redis 或 Milvus。
- `search` 场景压测 `POST /api/v1/agent/document/search`，只读调用向量检索链路，但会消耗 Embedding、Milvus 和应用限流配额。
- 不压测文档上传、聊天、ReAct 或 Multi-Agent 接口，避免写入数据、消耗真实模型额度或触发长耗时推理。
- RabbitMQ 当前只属于外部连通性预检对象，不在 `newagent` 运行链路中；JMeter 不会把 RabbitMQ 误测成应用吞吐量。

测试计划：[`tests/jmeter/agent-smoke.jmx`](../tests/jmeter/agent-smoke.jmx)

启动与汇总脚本：[`scripts/run-jmeter-smoke.ps1`](../scripts/run-jmeter-smoke.ps1)

脚本生成的 `.jtl`、日志、HTML 报告和汇总 JSON 写入 `evaluation-reports/`，该目录已被 `.gitignore` 忽略，不应提交到 GitHub。

## 当前混合部署拓扑

推荐的本地开发拓扑为：

```text
Win11 本地应用 :8081
    ├── MySQL   localhost:3306
    ├── Redis   localhost:6379
    └── Milvus  云服务器:19530

云服务器 RabbitMQ:5672 仅做可选连通性预检，当前未接入应用运行链路
Win11 本地 JMeter 5.6.3 → 本地应用 HTTP API
```

JMeter 只需要访问应用的 HTTP 地址；应用依赖在哪里运行，应通过应用自身的环境变量配置。压测前可以先运行：

```powershell
.\scripts\check-infrastructure.ps1 `
  -MilvusHost $env:MILVUS_HOST `
  -RabbitHost $env:RABBITMQ_HOST
```

该预检只验证 TCP 与非敏感目标配置，不会验证 JMeter 的业务请求，也不会修改 Milvus collection。

## 前置条件

1. JDK 17+、Maven 和 JMeter 5.6.3 已安装。
2. JMeter 的 `jmeter.bat` 已加入 `PATH`；否则通过 `-JMeterPath` 传入本地路径。
3. 应用已启动并监听 `8081`，健康接口可访问。
4. 执行 `search` 场景时，应用必须使用正确的向量库模式；复用云端 QA collection 时建议使用 `AI_VECTOR_STORE_MODE=qa`、目标 `MILVUS_COLLECTION_NAME` 和 `MILVUS_READ_ONLY=true`。
5. 测试查询只能使用脱敏、无隐私的固定文本；不要把 API Key、订单号、用户数据或生产问题写入命令行和结果文件。

## 运行方式

### 1. 健康检查基线

这是默认场景，适合先确认 JMeter、应用端口和 HTTP 链路是否正常：

```powershell
.\scripts\run-jmeter-smoke.ps1 `
  -BaseUrl 'http://localhost:8081' `
  -Scenario health `
  -Threads 5 `
  -RampUpSeconds 5 `
  -DurationSeconds 30 `
  -FailOnErrors
```

### 2. 只读文档检索

该场景会真实访问 Embedding 与 Milvus，适合在确认应用配置和 collection 数据后执行：

```powershell
.\scripts\run-jmeter-smoke.ps1 `
  -BaseUrl 'http://localhost:8081' `
  -Scenario search `
  -Threads 2 `
  -RampUpSeconds 5 `
  -DurationSeconds 30 `
  -Query 'password reset' `
  -TopK 5 `
  -Threshold 0.7 `
  -FailOnErrors
```

JMeter 不在请求中加入管理员密钥，因为健康和文档搜索接口当前允许匿名访问。若未来接口鉴权策略改变，应在测试计划中以环境变量方式增加 Header，禁止把真实密钥写入 `.jmx`。

如果应用不在本机默认端口：

```powershell
.\scripts\run-jmeter-smoke.ps1 -BaseUrl 'http://192.0.2.10:8081' -Scenario health
```

如果 `jmeter.bat` 不在 `PATH`：

```powershell
.\scripts\run-jmeter-smoke.ps1 `
  -JMeterPath 'C:\tools\apache-jmeter-5.6.3\bin\jmeter.bat' `
  -Scenario health
```

## 参数说明

| 参数 | 默认值 | 说明 |
|---|---:|---|
| `-BaseUrl` | `http://localhost:8081` | 应用 HTTP 地址，只允许绝对 `http/https` URL |
| `-Scenario` | `health` | `health` 或只读 `search` |
| `-Threads` | `1` | 并发虚拟用户数 |
| `-RampUpSeconds` | `1` | 在多长时间内逐步启动线程 |
| `-DurationSeconds` | `30` | 稳态运行时长 |
| `-Query` | `password reset` | `search` 场景的脱敏查询 |
| `-TopK` | `5` | `search` 场景的 Top-K，范围 `1..50` |
| `-Threshold` | `0.7` | `search` 场景的相似度阈值，范围 `0..1` |
| `-FailOnErrors` | 关闭 | 有失败请求时以退出码 `1` 结束，适合本地门禁或 CI |
| `-SkipHealthCheck` | 关闭 | 跳过脚本启动前的健康预检；只建议用于排查应用启动阶段问题 |

## 输出与指标

每次执行生成：

- `.jtl`：JMeter 原始采样结果。
- `.log` 和 `-console.log`：JMeter 运行日志。
- `-html/`：JMeter HTML Dashboard。
- `-summary.json`：脚本汇总的样本数、成功数、错误率、平均耗时、P50/P95/P99、吞吐量和响应码分布。

这里的 `throughputRps` 是按 JTL 样本时间窗口计算的客户端观测请求速率，不等于服务端理论 QPS；P99 也只代表本次参数、机器、依赖状态和数据集下的观测值。样本数少于 2 条时，脚本将 `sampleDurationSeconds` 和 `throughputRps` 留空，避免把 JMeter 启动耗时误当成吞吐量。没有实际运行结果时，文档和简历不能填写性能数字。

脚本默认即使有失败请求也会完成并输出报告；加入 `-FailOnErrors` 后，存在失败请求时以退出码 `1` 结束。`-FailOnErrors` 只提供门禁，不会改变 JMeter 采样结果。

## 测试策略建议

1. 先执行 `health`，确认应用 HTTP 层和本地 JMeter 正常。
2. 再以 `Threads=1/2/5` 分档执行 `search`，记录每档的错误率、P95、P99 和 Milvus 查询耗时。
3. 搜索场景默认受 Redis 固定窗口限流保护；如果要做容量测试，只能在隔离的本地或预发布环境显式调整 `AI_RATE_LIMIT_*`，不能为了压测关闭生产保护。
4. 每次改变 collection、Embedding 模型、相似度阈值或部署拓扑，都应重新记录测试参数；不同配置的结果不能直接横向比较。
5. JMeter 结果不能替代 `RagEvaluationService` 的召回率/Precision/F1 评测，两者分别回答“服务承载表现”和“检索质量”问题。

## 结果可信度检查

- `health` 通过只说明 HTTP 健康接口可达，不说明 MySQL、Redis、Milvus 或模型链路可用。
- `search` 出现 `0` 条结果可能是查询、阈值、collection schema、Embedding 模型或数据问题，不能直接解释为性能问题。
- 错误率升高时，应同时查看应用日志、`/actuator/prometheus`、Redis 限流指标和 Milvus 日志。
- RabbitMQ 端口可达不代表项目已经使用 RabbitMQ；当前 README、预检脚本和压测文档均保持这一边界。
