# 后端镜像：构建阶段用 Maven，运行阶段只留 JRE。
# --mount=type=cache 需要 BuildKit（docker compose v2 默认开启；老构建器请删掉这处 mount 参数）。
FROM docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml maven-settings.xml ./
COPY src ./src
# /root/.m2 挂 BuildKit 缓存：改代码会让这一层失效，但依赖已在缓存里，不再重新下载
# （只有 docker builder prune 会清掉这份缓存）。
RUN --mount=type=cache,target=/root/.m2 mvn -B -s maven-settings.xml -DskipTests package

FROM docker.m.daocloud.io/library/eclipse-temurin:21-jre-jammy
WORKDIR /app
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Shanghai"
# curl 仅用于容器健康检查
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /src/target/platform-server-*.jar app.jar
EXPOSE 8080

# /wx/callback 缺 timestamp/nonce 会返回 500，但只要有 HTTP 响应就说明服务和映射都活着
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -s -o /dev/null http://127.0.0.1:8080/wx/callback || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
