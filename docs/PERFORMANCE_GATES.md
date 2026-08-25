# JMeter 性能门禁

`scripts/run-jmeter-smoke.ps1` 除了生成 JTL、HTML 和汇总 JSON，还支持把关键指标转换为自动化退出码门禁。

```powershell
.\scripts\run-jmeter-smoke.ps1 `
  -Scenario search `
  -Threads 5 `
  -DurationSeconds 60 `
  -MaxErrorRate 0.01 `
  -MaxP95Milliseconds 2000 `
  -MinThroughputRps 2
```

## 参数

- `-MaxErrorRate`：允许的最大错误率，范围 `0..1`。
- `-MaxP95Milliseconds`：允许的最大 P95 响应时间，单位毫秒。
- `-MinThroughputRps`：要求的最低客户端观测吞吐量。
- `-FailOnErrors`：只要出现失败请求就返回退出码 `1`。

三个性能阈值默认关闭，只有显式传入时才启用。任一门禁不通过时，脚本返回退出码 `1`，原因同时输出到控制台，并写入 `summary.json` 的 `qualityGates`。

阈值应基于同一机器、同一数据集、同一 Milvus collection 和同一模型配置的历史基线制定。示例值只展示用法，不代表生产性能承诺。
