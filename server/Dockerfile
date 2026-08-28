# ============================================================
# Video Reader 后端镜像（多阶段构建）
# 阶段1：Maven + JDK21 编译源码 → jar
# 阶段2：JRE21 运行时 + FFmpeg（后端抽帧/切片依赖）
# 用法：docker compose -f docker-compose.prod.yml build backend
# ============================================================

# ---- 构建阶段 ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先拷贝 pom 拉依赖（利用层缓存，代码改动不重下依赖）
COPY pom.xml .
RUN mvn -B -q dependency:go-offline || true

COPY src ./src
RUN mvn -B -q -DskipTests package

# ---- 运行阶段 ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# FFmpeg：FfmpegService 默认取 PATH 上的 ffmpeg（application.yml FFMPEG_PATH 默认 "ffmpeg"）
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /build/target/video-reader-server-*.jar app.jar

EXPOSE 8081
# MaxRAMPercentage：容器内存限制下让 JVM 自适应堆大小（不撑爆宿主机）
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "app.jar"]
