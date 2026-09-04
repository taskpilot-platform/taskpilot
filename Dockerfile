# Stage 1: Build stage
FROM eclipse-temurin:25-jdk-alpine AS build
RUN apk add --no-cache maven
WORKDIR /app

COPY pom.xml .
COPY taskpilot-infrastructure/pom.xml taskpilot-infrastructure/
COPY taskpilot-contracts/pom.xml taskpilot-contracts/
COPY taskpilot-users/pom.xml taskpilot-users/
COPY taskpilot-ai/pom.xml taskpilot-ai/
COPY taskpilot-projects/pom.xml taskpilot-projects/
COPY taskpilot-app/pom.xml taskpilot-app/

RUN mvn dependency:resolve -B || true

COPY taskpilot-infrastructure/src taskpilot-infrastructure/src
COPY taskpilot-contracts/src taskpilot-contracts/src
COPY taskpilot-users/src taskpilot-users/src
COPY taskpilot-ai/src taskpilot-ai/src
COPY taskpilot-projects/src taskpilot-projects/src
COPY taskpilot-app/src taskpilot-app/src

RUN mvn clean package -DskipTests -B 

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
