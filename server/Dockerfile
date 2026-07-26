# syntax=docker/dockerfile:1

# ---- Stage 1: build the jar -------------------------------------------------
# Deliberately not ./mvnw: this repo's wrapper is `distributionType=only-script`, so it
# downloads and unzips a Maven distribution at build time and needs curl + unzip that the
# plain temurin JDK image doesn't ship. The maven image already carries Maven 3.9 + JDK 21,
# the same line the wrapper pins.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# pom.xml alone first: this layer is cached and only invalidates when dependencies change,
# so an ordinary source edit doesn't re-download the world on every deploy.
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src

# -DskipTests, deliberately: every @SpringBootTest here needs a live Postgres (which is why
# .github/workflows/ci.yml stands up a service container), and there is no database inside
# an image build. CI is what proves the tests pass; this build produces the artifact.
RUN mvn -B -ntp clean package -DskipTests

# ---- Stage 2: run it --------------------------------------------------------
# JRE, not JDK: the compiler and friends are build-time tools with no business in a running
# container. Alpine keeps the image roughly half the size of the Ubuntu variant.
FROM eclipse-temurin:21-jre-alpine AS runtime

# Nothing the app does needs root.
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app

# spring-boot-maven-plugin repackages into an executable fat jar and leaves the
# pre-repackage artifact as *.jar.original, which this glob does not match.
# --chown on the COPY rather than a following `RUN chown`: a RUN would write a second,
# full copy of the jar into a new layer, roughly doubling its contribution to the image.
COPY --chown=app:app --from=build /build/target/*.jar app.jar
USER app

# Documentation only — the real listen port comes from $PORT (see application.properties).
EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: the JVM sizes the heap from the *container's*
# memory limit, so one image is correctly sized on a 512 MB free instance and on a larger
# one. Kept in an env var so the flags are tunable from the Render dashboard, no rebuild.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# `exec` so the JVM becomes PID 1 and receives the platform's SIGTERM directly — a clean
# Spring shutdown that closes open WebSockets, rather than the signal stopping at a shell.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
