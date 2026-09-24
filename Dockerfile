# ── Stage 1: build the SPA ────────────────────────────────────────────────────
FROM node:22-alpine AS web
RUN corepack enable

WORKDIR /web
COPY carp-dsp-portal/web/package.json carp-dsp-portal/web/pnpm-lock.yaml* ./
RUN pnpm install --frozen-lockfile || pnpm install

COPY carp-dsp-portal/web/ ./
RUN pnpm build

# ── Stage 2: build the server ─────────────────────────────────────────────────
#
# The context is the directory holding the checkouts, not the portal alone. The
# server depends on carp-dsp, which depends on carp.core-kotlin and
# health-workflow-interfaces, and every one of those is resolved as a sibling -
# so laying them out the same way here is what lets those relative paths work
# unchanged.
#
# See Dockerfile.dockerignore: it excludes everything in that directory except
# these four checkouts, so a neighbouring repo never enters the context.
FROM eclipse-temurin:21-jdk AS server
WORKDIR /build

COPY carp.core-kotlin/ carp.core-kotlin/
COPY health-workflow-interfaces/ health-workflow-interfaces/
COPY carp-dsp/ carp-dsp/
COPY carp-dsp-portal/ carp-dsp-portal/

# The SPA is already built, and this image has no node.
COPY --from=web /web/build/ carp-dsp-portal/server/src/main/resources/static/

WORKDIR /build/carp-dsp-portal

# The step library, demo workflows, scripts and sample data are synced out of
# the carp-dsp checkout into the jar, so the image runs on its own. Mounting a
# checkout at DSP_REPO still overrides them, which is what makes editing a step
# and restarting work during a demo.
#
# The Gradle home is a cache mount, so a rebuild does not re-resolve three
# builds' dependency graphs.
#
# The task runtime is built in the same invocation, from the same step sources
# the server bundles, so the Kotlin steps the library lists are the ones the
# runtime can run.
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && \
    ./gradlew :server:installDist :carp-dsp:carp.dsp.steps:taskRuntimeJar \
        -PskipWebBuild=true --no-daemon

# ── Stage 3: runtime ──────────────────────────────────────────────────────────
#
# glibc rather than Alpine. pixi itself has a musl build, but the conda packages
# it installs are linux-64 and glibc-linked, so an Alpine runtime solves an
# environment and then fails to load it.
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl ca-certificates && \
    rm -rf /var/lib/apt/lists/* && \
    curl -fsSL https://pixi.sh/install.sh | PIXI_HOME=/usr/local bash && \
    pixi --version

COPY --from=server /build/carp-dsp-portal/server/build/install/server/ ./

# Kotlin steps run as `java -cp <runtime jar>`. The jar stays in the image rather
# than under $HOME: home is the state volume, and a volume keeps what was first
# put in it, so a rebuild would go on running the old runtime.
COPY --from=server /build/carp-dsp/carp.dsp.steps/build/task-runtime/carp-task-runtime.jar /app/task-runtime/
ENV CARP_DSP_TASK_RUNTIME=/app/task-runtime

ENV DSP_PORT=8080

# The engine puts a solved environment under $HOME/.carp-dsp/envs, and pixi puts
# its package cache under $HOME/.cache. Both are expensive - minutes, and a few
# gigabytes - and neither belongs in a container layer, so home points at the
# state volume and a second run of the same workflow starts immediately.
ENV HOME=/state/home
RUN mkdir -p /state/home

# $HOME is not enough on its own: the JVM takes user.home from the passwd entry,
# which for root is /root, so solved environments were landing in the container
# layer and were lost on every recreate. SERVER_OPTS reaches only the server's
# own JVM, not the `java` a Kotlin step launches.
ENV SERVER_OPTS="-Duser.home=/state/home"

EXPOSE 8080

# Fails the container health check if the server stops answering.
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
    CMD curl -fsS http://localhost:8080/health || exit 1

ENTRYPOINT ["./bin/server"]
