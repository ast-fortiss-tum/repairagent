#!/usr/bin/env bash
set -eo pipefail
cd "$(dirname "$0")/../agent"

has_setting() {
  [ -n "${!1}" ] || grep -Eq "^$1=.+" .env 2>/dev/null
}
if ! has_setting OPENAI_API_KEY; then
  echo "Set OPENAI_API_KEY in the environment or in agent/.env" >&2
  exit 1
fi
if ! has_setting JAVA_HOME; then
  echo "Set JAVA_HOME to a JDK 21 in the environment or in agent/.env" >&2
  exit 1
fi

mkdir -p agent_dir
export SWE_DATASET="${SWE_DATASET:-gson}"
export CASE_IDS="${CASE_IDS:-19}"
export APP_MAX_ATTEMPTS="${APP_MAX_ATTEMPTS:-1}"
export APP_EXECUTION_MODE=commit-scoped
export LLM_MODEL="${LLM_MODEL:-gpt-4o}"
./gradlew --console=plain run
echo "Done. The attempt is in agent/agent_dir/evaluation/"
