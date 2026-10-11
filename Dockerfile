# ts-ticketing-system: the whole API in ONE container (OPS-01). Locally via docker-compose.yml,
# on Render via render.yaml:
#
#   docker build -t ts-ticketing/app .
#
# No BuildKit cache mounts, so it builds on any builder (Render's included).

# ---- build: tests skipped (they need Docker; run ./mvnw test on the host) ---------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# Dependencies in their own layer: a source change doesn't download them again.
RUN ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q package -DskipTests

# ---- runtime: JRE only, non-root, java as PID 1 so SIGTERM shuts down gracefully -------------
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
# prod: application-prod.yml applies (no local DB fallback), logs are JSON lines.
ENV SPRING_PROFILES_ACTIVE=prod
# glibc would otherwise create a malloc arena per host CPU (LOAD-TESTING.md §5.4).
ENV MALLOC_ARENA_MAX=2
# Memory: sized for a 512 MB instance (Render free/starter) with Native Memory Tracking under
# 20k-request bursts (LOAD-TESTING.md §5): ~150 MiB non-heap + ~45 MiB glibc overhead, so a 60 %
# heap fits; metaspace and code cache are capped. MaxRAM makes the JVM assume 512 MB even if the
# host doesn't expose its memory limit.
# CPU: Render free gives 0.1 CPU. Optimizing compiler (C2) work would compete with startup,
# so TieredStopAtLevel=1 uses only the fast C1 compiler. On a bigger plan, override
# JAVA_TOOL_OPTIONS without MaxRAM and TieredStopAtLevel.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAM=512m -XX:MaxRAMPercentage=60 -XX:MaxMetaspaceSize=96m -XX:ReservedCodeCacheSize=32m -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"
# Class Data Sharing: unpack the jar, then start the app once at build time and stop it after
# the Spring context refresh. That run saves the loaded classes to app.jsa, and every later
# start (including a free instance waking up) maps that file instead of parsing the classes
# again (CLAUDE.md lesson 23). The training run needs no database: Flyway is off and the
# connection pool connects lazily. Its secrets are throwaway values that only satisfy startup
# validation; nothing is stored in the archive except classes.
COPY --from=build /src/target/ticketing.jar /tmp/ticketing.jar
RUN java -Djarmode=tools -jar /tmp/ticketing.jar extract --destination /app \
    && rm /tmp/ticketing.jar \
    && JWT_SECRET=cds-training-only-not-a-real-secret-0123456789 ADMIN_SECRET=cds-training-only \
       DATABASE_URL=jdbc:mysql://localhost:1/cds-training \
       java -XX:ArchiveClassesAtExit=app.jsa -Dspring.context.exit=onRefresh -Dspring.flyway.enabled=false \
            -jar ticketing.jar > /tmp/cds-training.log 2>&1 \
    && test -s app.jsa && rm /tmp/cds-training.log
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:SharedArchiveFile=app.jsa", "-jar", "ticketing.jar"]
