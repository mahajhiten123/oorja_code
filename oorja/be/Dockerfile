# ─────────────────────────────────────────────────────────────────────────────
# Stage 1: Build
# Uses the full JDK + Maven to compile and package the application.
# The Maven local repository is NOT baked into the image — only the fat JAR
# produced by spring-boot:repackage ends up in the runtime stage.
# ─────────────────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy dependency descriptors first so Docker can cache the dependency layer
# independently of source changes — a source-only change won't re-download deps.
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Now copy sources and build (tests are skipped here — the CI pipeline already
# ran them before building this image, so there's no value in running them again
# inside a slow Docker build step).
COPY src ./src
RUN mvn package -DskipTests -q

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2: Runtime
# Slim JRE-only image — no JDK, no Maven, no build toolchain.
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Non-root user: containers that run as root are a security risk.
RUN addgroup -S oorja && adduser -S oorja -G oorja
USER oorja

# Copy only the executable JAR from the build stage.
COPY --from=build /app/target/oorja-backend.jar app.jar

# Render (and most PaaS) inject PORT; Spring Boot picks it up via
# server.port=${PORT:8080} in application.properties.
EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseSerialGC", \
  "-XX:TieredStopAtLevel=1", \
  "-Xmx384m", \
  "-Xss512k", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-Dspring.profiles.active=prod", \
  "-jar", "app.jar"]
