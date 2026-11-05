#!/bin/bash
set -eo pipefail

if [ $# -eq 0 ]; then
  echo "Usage: $0 <image-tag> [command...]"
  exit 1
fi

DOCKER_IMAGE_NAME="repairgent-case-$1:latest"
echo "Running a docker container from image: $DOCKER_IMAGE_NAME"
docker run --privileged -d \
  -e DOCKER_HOST=unix:///var/run/docker.sock \
  "$DOCKER_IMAGE_NAME" "${@:2}"
