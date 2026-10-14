# ── Build ────────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies in their own layer: as long as pom.xml is unchanged, Docker
# reuses this layer and skips re-downloading the world on every code change.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# Explode the fat JAR into Spring Boot's layers. Dependencies change rarely,
# application code changes constantly — separating them means a code-only
# redeploy pushes ~2 MB over the wire instead of ~60 MB. On a Hetzner box you
# will feel this every single deploy.
RUN java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted

# ── Runtime ──────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# wget is used by the compose healthcheck; alpine's JRE image lacks curl.
RUN apk add --no-cache wget \
 && addgroup -S app && adduser -S -G app app \
 # Pre-create with app:app ownership BEFORE the named volume is mounted —
 # Docker initializes a new named volume from whatever already exists at its
 # mount path in the image (including ownership). Skip this and the volume
 # gets created root-owned on first run, and the non-root "app" user below
 # can never write an uploaded image to it.
 # Same for generated invoice PDFs (InvoiceService): without this the volume
 # is root-owned, every write fails, and customers silently get no invoice.
 && mkdir -p /app/data/product-images /app/data/invoices && chown -R app:app /app/data

# Copy layers most-stable first, so a code change invalidates only the last one.
COPY --from=build --chown=app:app /build/extracted/dependencies/ ./
COPY --from=build --chown=app:app /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /build/extracted/application/ ./

# Never run as root. A container escape from an unprivileged process is a much
# harder problem for an attacker than one from root.
USER app

EXPOSE 8080

# MaxRAMPercentage, not -Xmx: the JVM should size its heap from the container's
# cgroup memory limit, so changing the container's memory in compose is enough.
# A hardcoded -Xmx larger than the container limit gets you OOM-killed by the
# kernel with no Java stack trace — a genuinely miserable thing to debug.
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "org.springframework.boot.loader.launch.JarLauncher"]
