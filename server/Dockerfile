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

# Not merely documentation here: Back4App requires the Dockerfile to expose a TCP port and
# routes to the one selected in App Settings (set it to 8080). It injects no $PORT, so the
# `${PORT:8080}` default in application.properties is what actually binds — the two agree at
# 8080 by design. Keep this in sync with the port configured in the dashboard.
EXPOSE 8080

# Sized for a 256 MB container (Back4App's free plan), and measured rather than guessed —
# see the numbers in README's "Deploying". A percentage-of-RAM heap is the obvious choice and
# the wrong one here: 75% of 256 MB is a 192 MB heap, which leaves nothing for the ~100 MB of
# metaspace Spring + Hibernate need, so the container sits at 81% before serving a request.
# A hard -Xmx is what creates the headroom — the heap physically cannot grow into the space
# metaspace and native memory are using, so GC works harder instead of the kernel OOM-killing
# the process. Measured under load (6 concurrent users building boards): this peaks at 210 MB
# of 256 MB, where MaxRAMPercentage=75 peaked at 240 MB — 16 MB from being killed.
#
#   -Xmx128m               hard heap cap; the single flag doing the real work
#   -XX:MaxMetaspaceSize   bounds the other half — an unbounded metaspace leak reads as a
#                          container OOM, which is far harder to diagnose than a Java error
#   -Xss512k               Tomcat's threads are numerous and shallow; 1 MB stacks are waste
#   -XX:TieredStopAtLevel=1  C1 only. On 0.25 CPU this cut startup 120s -> 48s, which matters
#                          because a slow boot fails the platform's deploy health check
#   -XX:+UseSerialGC       G1's per-region bookkeeping and GC threads don't pay for themselves
#                          on a sub-quarter-CPU box with a small heap
#
# Raising the plan? Give the heap the extra room (512 MB plan -> -Xmx256m); everything else
# still holds. Kept in an env var so it's tunable from the dashboard without a rebuild.
ENV JAVA_OPTS="-Xmx128m -XX:MaxMetaspaceSize=128m -Xss512k -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"

# `exec` so the JVM becomes PID 1 and receives the platform's SIGTERM directly — a clean
# Spring shutdown that closes open WebSockets, rather than the signal stopping at a shell.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
