# Stage 1: build the React SPA (Phase 3.2). A dedicated node image avoids the alpine/musl
# node-download problem inside the maven build, and caches npm ci on package-lock changes.
# Vite's outDir is ../target/classes/static/app (relative to frontend/), i.e. /target/... here.
FROM node:20 AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# Stage 2: build the Spring Boot jar. The SPA bundle is copied into the backend resources
# (classpath:/static/app) so the repackaged jar serves it from the root (SpaWebConfig maps it to
# /); -DskipFrontend skips the frontend-maven-plugin (the SPA is already built above).
FROM maven:3.9-eclipse-temurin-17-alpine AS build

WORKDIR /build

COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
COPY --from=frontend /target/classes/static/app ./src/main/resources/static/app
RUN mvn package -DskipTests -DskipFrontend=true -q

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY --from=build /build/target/inbox-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
