# syntax=docker/dockerfile:1.6

# ----- build stage -----------------------------------------------------------
# JDK 17 is enforced by Maven Enforcer ([17,18)); use the matching maven image.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src

# Configure Aliyun Maven mirror for faster downloads in China
RUN mkdir -p /root/.m2 && cat > /root/.m2/settings.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 http://maven.apache.org/xsd/settings-1.2.0.xsd">
  <mirrors>
    <mirror>
      <id>aliyun-public</id>
      <mirrorOf>*</mirrorOf>
      <name>Aliyun Public Mirror</name>
      <url>https://maven.aliyun.com/repository/public</url>
    </mirror>
  </mirrors>
</settings>
EOF

# Cache dependencies first so source-only changes don't redownload the world.
COPY pom.xml ./
COPY meeting-api-client/pom.xml meeting-api-client/
COPY meeting-api-domain/pom.xml meeting-api-domain/
COPY meeting-api-app/pom.xml meeting-api-app/
COPY meeting-api-infrastructure/pom.xml meeting-api-infrastructure/
COPY meeting-api-adapter/pom.xml meeting-api-adapter/
COPY meeting-api-start/pom.xml meeting-api-start/
RUN mvn -q -B -ntp dependency:go-offline -DskipTests || true

# Bring in the full source tree (.dockerignore strips target/ etc.) and build.
COPY . .
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -ntp -DskipTests -pl meeting-api-start -am package

# ----- runtime stage ---------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy

# LibreOffice headless + CJK + common fonts. We deliberately keep only
# Writer (no impress/calc/draw/base) to stay under the 1.5 GB image cap;
# PdfExportGateway only needs DOCX → PDF.
ARG APT_MIRROR=""
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt/lists,sharing=locked \
    set -eux; \
    rm -f /etc/apt/apt.conf.d/docker-clean; \
    if [ -n "${APT_MIRROR}" ]; then \
      sed -i \
        -e "s|http://archive.ubuntu.com/ubuntu|${APT_MIRROR}|g" \
        -e "s|http://security.ubuntu.com/ubuntu|${APT_MIRROR}|g" \
        /etc/apt/sources.list /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources 2>/dev/null || true; \
    fi; \
    apt-get update \
    && apt-get install -y --no-install-recommends \
         libreoffice-core libreoffice-writer \
         fonts-noto-cjk fonts-noto-cjk-extra \
         fonts-liberation \
         curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Non-root user; mount points stay writable so soffice can create profile dirs.
RUN useradd --create-home --shell /bin/false meeting \
    && mkdir -p /app /tmp/soffice \
    && chown -R meeting:meeting /app /tmp/soffice

WORKDIR /app
COPY --from=build /src/meeting-api-start/target/meeting-api-start-*.jar /app/app.jar

USER meeting

ENV JAVA_OPTS="" \
    LIBREOFFICE_BINARY="soffice" \
    LIBREOFFICE_TIMEOUT_SECONDS="60"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fs http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
