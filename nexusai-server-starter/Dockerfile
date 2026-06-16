# nexusai 可执行镜像（蓝图 §3.3 / §13.1：liberica 21 + ZGC，万级并发 SSE）
FROM bellsoft/liberica-openjdk-debian:21

WORKDIR /nexusai/gateway

COPY target/nexusai-server-starter.jar app.jar

# 业务口（WebFlux 转发）；管理口经 management 暴露
EXPOSE 8600

ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -Xms2g -Xmx6g"
# 凭证密钥强制环境变量注入（禁明文）：-e NEXUSAI_CRYPTO_KEY=<base64-32B>

ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar app.jar"]
