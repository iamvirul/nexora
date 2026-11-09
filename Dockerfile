# Container image for `nexora observe`, the Nexora HTTP API, dashboard and metrics server.
#
# Build the CLI first, then the image (the jar is copied, not built here, so the image build needs
# no Maven and no network):
#   mvn -pl nexora-cli -am package -DskipTests
#   docker build -t nexora:dev .
#
# Run it (NEXORA_API_KEY must be at least 32 characters):
#   docker run --rm -p 9464:9464 -p 9465:9465 -e NEXORA_API_KEY=... nexora:dev
#
# Ports: 9464 HTTP (API, dashboard, /metrics, /health/*), 9465 the dashboard's WebSocket stream.
# Writable paths: /data for an embedded H2 store, /tmp for the JVM. Everything else can be read-only.
FROM eclipse-temurin:25-jre-noble

ARG NEXORA_VERSION=dev
LABEL org.opencontainers.image.title="nexora" \
      org.opencontainers.image.description="Nexora observe server: execution API, dashboard and Prometheus metrics" \
      org.opencontainers.image.source="https://github.com/iamvirul/nexora" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${NEXORA_VERSION}"

# A fixed, non-root uid/gid so Kubernetes runAsNonRoot and fsGroup work without a username lookup.
RUN groupadd --system --gid 10001 nexora \
    && useradd --system --uid 10001 --gid nexora --home-dir /home/nexora --create-home --shell /usr/sbin/nologin nexora \
    && mkdir -p /app /data /etc/nexora \
    && chmod 0755 /app \
    && chown nexora:nexora /data

# /app exists first, so --chmod applies to the jar only, not to a directory it would otherwise create.
COPY --chown=root:root --chmod=0444 nexora-cli/target/nexora.jar /app/nexora.jar

USER 10001:10001
WORKDIR /home/nexora

# Size the heap from the container's memory limit, and exit (to be restarted) rather than limp on after
# an OutOfMemoryError.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE 9464 9465
VOLUME ["/data"]

ENTRYPOINT ["java", "-jar", "/app/nexora.jar"]
CMD ["observe"]
