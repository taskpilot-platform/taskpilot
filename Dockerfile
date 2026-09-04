# Stage 1: Build stage
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app

COPY . .

RUN if [ -f taskpilot-app/target/taskpilot-app-0.0.1-SNAPSHOT.jar ]; then \
        echo "=== Pre-built JAR found, skipping Maven download ==="; \
    else \
        echo "=== Building JAR with Maven Wrapper ===" && \
        chmod +x mvnw && \
        ./mvnw clean package -DskipTests -B; \
    fi 

# Stage 2: Runtime
FROM eclipse-temurin:25-jre-alpine

ENV PORT=7860 \
    SERVER_PORT=7860 \
    HOME=/home/user

# Create unprivileged user with explicit UID 1000 / GID 1000 required by Hugging Face Spaces
RUN addgroup -g 1000 user && \
    adduser -u 1000 -G user -s /bin/sh -D user

WORKDIR /home/user/app

COPY --chown=user:user --from=build /app/taskpilot-app/target/*-SNAPSHOT.jar app.jar

USER user

EXPOSE 7860

ENTRYPOINT ["java", \
    "--enable-preview", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:InitialRAMPercentage=50.0", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-Djava.net.preferIPv4Stack=true", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
