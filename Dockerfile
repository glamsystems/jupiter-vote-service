ARG PROJECT="jupiter_vote_service"

FROM gradle:jdk23-alpine AS jlink

WORKDIR /tmp

COPY . .

ARG PROJECT
RUN --mount=type=secret,id=GITHUB_ACTOR \
    --mount=type=secret,id=GITHUB_TOKEN \
    export GITHUB_ACTOR=$(cat /run/secrets/GITHUB_ACTOR); \
    export GITHUB_TOKEN=$(cat /run/secrets/GITHUB_TOKEN); \
    gradle --ptargetJava=23 --console=plain --no-daemon --exclude-task=test :${PROJECT}:jlink -PnoVersionTag=true

FROM alpine:3

ARG UID=10001
RUN adduser \
    --disabled-password \
    --gecos "" \
    --home "/nonexistent" \
    --shell "/sbin/nologin" \
    --no-create-home \
    --uid "${UID}" \
    glam
USER glam

WORKDIR /glam

ARG PROJECT
COPY --from=jlink /tmp/${PROJECT}/build/${PROJECT} /glam

ENTRYPOINT [ "./bin/java" ]
