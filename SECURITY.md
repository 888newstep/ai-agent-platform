# 安全策略

## 支持的版本

| 版本 | 支持状态 |
|------|---------|
| 最新 Release | ✅ 积极维护 |
| 其他版本 | ❌ 不提供安全更新 |

## 报告安全漏洞

如果您发现了安全漏洞，**请不要公开披露**，而是通过以下方式私下报告：

1. 在 GitHub 上创建一个 [Security Advisory](https://github.com/888newstep/ai-agent-platform/security/advisories)
2. 或发送邮件至项目维护者

我们将在 **48 小时内**确认收到报告，并在修复后公开致谢（如您同意）。

## 安全最佳实践

### 部署安全

- 始终使用 `.env` 文件配置敏感信息，不要硬编码 API Key
- 生产环境使用强 JWT Secret（至少 256 位随机字符串）
- 启用 HTTPS（Nginx 反向代理 + SSL 证书）
- 定期更新依赖版本

### 凭据管理

项目通过以下方式保护凭据安全：

1. 所有 API Key 通过环境变量 `${VAR_NAME}` 注入
2. `.env` 文件已被 `.gitignore` 排除，不会提交到仓库
3. 提供 `.env.example` 模板，包含占位符值

### 依赖安全

- 使用 `mvn dependency-check` 定期扫描已知漏洞
- GitHub Dependabot 自动检测依赖更新
- 及时应用安全补丁