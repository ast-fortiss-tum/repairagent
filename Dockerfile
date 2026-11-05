FROM eclipse-temurin:21-jdk-noble

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates git docker.io docker-buildx \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /repairgent
COPY agent/ agent/
RUN cd agent && ./gradlew --no-daemon installDist -x test && mkdir -p agent_dir

WORKDIR /repairgent/agent
ENV APP_EXECUTION_MODE=commit-scoped
ENTRYPOINT ["build/install/repairgent/bin/repairgent"]
