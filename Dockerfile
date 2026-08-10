# ============================================
# AI Agent Platform — Docker 多阶段构建
# ============================================

# ---- Stage 1: Build ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先复制依赖配置，利用 Docker 缓存
COPY pom.xml .
RUN mvn dependency:go-offline -B || true

# 复制源码并编译
COPY src ./src
RUN mvn clean package -DskipTests -B

# ---- Stage 2: Runtime ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 安装 curl（用于健康检查）
RUN apk add --no-cache curl

# 从 build 阶段复制构建产物
COPY --from=build /build/target/ai-agent-platform-*.jar app.jar
COPY examples/evaluation-datasets ./examples/evaluation-datasets

# 暴露端口
EXPOSE 8081

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD curl -f http://localhost:8081/api/v1/agent/health || exit 1

# 启动
ENTRYPOINT ["java", "-jar", "app.jar"]