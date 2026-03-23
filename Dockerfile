# Stage 1: Build stage
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
COPY taskpilot-infrastructure/pom.xml taskpilot-infrastructure/
COPY taskpilot-users/pom.xml taskpilot-users/
COPY taskpilot-ai/pom.xml taskpilot-ai/
COPY taskpilot-projects/pom.xml taskpilot-projects/
COPY taskpilot-app/pom.xml taskpilot-app/

RUN mvn dependency:resolve -B 

COPY taskpilot-infrastructure/src taskpilot-infrastructure/src
COPY taskpilot-users/src taskpilot-users/src
COPY taskpilot-ai/src taskpilot-ai/src
COPY taskpilot-projects/src taskpilot-projects/src
COPY taskpilot-app/src taskpilot-app/src

RUN mvn clean package -DskipTests -B 

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

ENV SERVER_PORT=7860

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

USER appuser

COPY --chown=appuser:appgroup --from=build /app/taskpilot-app/target/*-SNAPSHOT.jar app.jar

EXPOSE 7860

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
