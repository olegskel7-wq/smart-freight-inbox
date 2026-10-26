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

# Gate C6: run as an unprivileged user. A code-execution bug (e.g. in PDF/Excel attachment parsing)
# then lands as a plain process with no write access to the app or the image, not as root.
# Fixed uid/gid so a volume added later can be chowned to a known id. The app writes only to /tmp
# (Tomcat work dir, multipart uploads), which is world-writable.
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app -H -h /app app
USER 10001:10001

EXPOSE 8080

# The base image's own entrypoint runs first: with USE_SYSTEM_CA_CERTS set it imports
# /certificates/*.crt into a JVM truststore (dev: a TLS-inspecting antivirus's root CA —
# see certs/extra-ca/README.md; as non-root it builds a private copy under /tmp instead of
# editing the JDK's); unset (the default, and prod) it just execs java.
# Gate C6: ExitOnOutOfMemoryError — an OOM kills the JVM so Docker restarts it and the
# health/MailboxDown alerts fire, instead of a half-dead JVM that answers health checks but
# ingests nothing. Heap is 65% of the container limit set in docker-compose.yml: the rest
# (metaspace, threads, direct buffers, PDFBox/POI) must fit too, or the kernel OOM-kills the
# container first — SIGKILL, no graceful drain, no OutOfMemoryError in the log.
ENTRYPOINT ["/__cacert_entrypoint.sh", "java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=65", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
