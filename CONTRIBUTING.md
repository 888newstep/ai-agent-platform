# 贡献指南

感谢您对 AI Agent Platform 的关注！我们欢迎任何形式的贡献，包括但不限于：

## 如何贡献

### 报告 Bug

1. 在 [Issues](https://github.com/888newstep/ai-agent-platform/issues) 中搜索是否已有类似问题
2. 如果没有，创建新 Issue 并附上：
   - 运行环境（OS、JDK 版本）
   - 复现步骤
   - 期望行为与实际行为
   - 相关日志或截图

### 提交 Pull Request

1. Fork 本仓库
2. 创建特性分支：`git checkout -b feature/your-feature-name`
3. 提交变更：`git commit -m "feat: add xxx feature"`
4. 推送到分支：`git push origin feature/your-feature-name`
5. 创建 Pull Request

### 开发规范

#### 代码风格

- 遵循项目现有的代码风格
- 使用 4 空格缩进
- 所有类和方法添加有意义的 Javadoc 注释
- 新增功能需包含单元测试

#### Commit 规范

使用 [Conventional Commits](https://www.conventionalcommits.org/) 规范：

- `feat:` 新功能
- `fix:` Bug 修复
- `docs:` 文档更新
- `refactor:` 重构
- `test:` 测试相关
- `chore:` 构建/工具链变更

#### 分支管理

- `main` — 稳定发布分支
- `dev` — 开发主分支
- `feature/*` — 特性分支
- `fix/*` — 修复分支

### 本地开发环境

```bash
# 1. 克隆项目
git clone https://github.com/888newstep/ai-agent-platform.git
cd ai-agent-platform

# 2. 配置环境变量
cp .env.example .env

# 3. 启动依赖服务（MySQL + Redis + Milvus）
docker compose up -d mysql redis milvus

# 4. 启动应用
mvn spring-boot:run
```

### 测试

提交前确保所有测试通过：

```bash
mvn test
```

## 行为准则

本项目采用 [Contributor Covenant](CODE_OF_CONDUCT.md) 行为准则。请阅读并遵守。

## 问题反馈

如有任何问题，请通过 [Issues](https://github.com/888newstep/ai-agent-platform/issues) 反馈。