#!/bin/bash
set -eo pipefail

if [ $# -eq 0 ]; then
  echo "Usage: $0 <image-tag>"
  exit 1
fi

DOCKER_IMAGE_NAME="repairgent-case-$1:latest"
echo "Creating docker image: $DOCKER_IMAGE_NAME"
docker buildx build --load -t "$DOCKER_IMAGE_NAME" .
