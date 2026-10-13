# =====================================================================
# SMMS 后端镜像（NFR-CMP-05 / AC-D04）
# 多阶段构建：Gradle + JDK 25 编译打包，JRE 25 最小运行
# 构建：docker build -t smms/backend ./backend
# =====================================================================
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# 先复制构建脚本与依赖清单，单独解析依赖以复用 Docker 层缓存
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon -q dependencies

# 再复制源码构建可执行 JAR（跳过单元测试；并发集成测试需 Docker，由 CI/本地执行）
COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test \
    && cp "$(find build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' | head -n1)" /app/app.jar

# ---------------------------------------------------------------------
FROM eclipse-temurin:25-jre
WORKDIR /app

# curl 供 HEALTHCHECK 探活；tzdata 供 TZ=Asia/Shanghai 生效（NFR-CMP-03）
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/*

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8" \
    SMMS_TASK_FILE_DIR=/app/task-files \
    SMMS_UPLOAD_DIR=/app/uploads

# 异步任务结果目录（导出/导入，保留期默认 24h；容器重建会丢失，任务记录仍在库中）
# 商品图片目录与日志目录（建议经数据卷持久化，见 docker-compose.yml）
RUN mkdir -p /app/task-files /app/uploads /app/logs

COPY --from=build /app/app.jar app.jar

EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=6 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
