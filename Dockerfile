# Multi-stage Docker build for Listen Together backend
# Stage 1: Build distribution with official Gradle 8.13 image (avoids downloading Gradle at build time)
FROM gradle:8.13-jdk17 AS builder

WORKDIR /app

# Copy gradle build files
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY build-logic ./build-logic

# Copy source projects required by backend
COPY protocol ./protocol
COPY core/model ./core/model
COPY core/common ./core/common
COPY backend ./backend

# Build the distribution using pre-installed Gradle
RUN gradle :backend:installDist --no-daemon

# Stage 2: Minimal runtime image
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Install curl for health checks and create a non-privileged user
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/* && \
    useradd -m -u 10001 listentogether && \
    chown -R listentogether:listentogether /app

# Copy distribution from builder
COPY --from=builder --chown=listentogether:listentogether /app/backend/build/install/backend ./

USER listentogether

ENV HOST=0.0.0.0
ENV PORT=10000

EXPOSE 10000

HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
  CMD curl -f http://localhost:${PORT}/healthz || exit 1

ENTRYPOINT ["bin/backend"]
