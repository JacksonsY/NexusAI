# nexusai 可执行镜像（蓝图 §3.3 / §13.1：liberica 21 + ZGC，万级并发 SSE）
FROM bellsoft/liberica-openjdk-debian:21

WORKDIR /nexusai/gateway

# 健康探测需 HTTP 客户端；Debian 基础镜像默认无 curl，显式最小安装并清理 apt 缓存
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 非 root 运行：创建 uid/gid 10001 的专用系统用户
RUN groupadd --system --gid 10001 nexusai \
    && useradd --system --uid 10001 --gid 10001 --home-dir /nexusai --shell /usr/sbin/nologin nexusai

COPY target/nexusai-server-starter.jar app.jar

# 运行期可写目录 + 归属非 root 属主（含 jar 与 data/logs）
RUN mkdir -p /nexusai/gateway/data /nexusai/gateway/logs \
    && chown -R 10001:10001 /nexusai

# 业务口（WebFlux 转发）；管理口经 management 暴露
EXPOSE 8600

ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -Xms2g -Xmx6g"
# 凭证密钥强制环境变量注入（禁明文）：-e NEXUSAI_CRYPTO_KEY=<base64-32B>

# 切换非 root 用户运行
USER 10001

# 容器健康检查：探测业务口 8600 的 actuator/health（DOWN→503→unhealthy）
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8600/actuator/health || exit 1

ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar app.jar"]
