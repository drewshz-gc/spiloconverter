#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Builds a GraalVM native executable: target/spilo2cnpg
#
# Prerequisites:
#   sdk install java 25.0.2-graalce
#
# Usage:
#   ./build-native.sh              # build with tests
#   ./build-native.sh --quick      # skip tests
# ---------------------------------------------------------------------------
set -euo pipefail

GRAALVM_HOME="${GRAALVM_HOME:-${HOME}/.sdkman/candidates/java/25.0.2-graalce}"

if [[ ! -x "${GRAALVM_HOME}/bin/native-image" ]]; then
  echo "ERROR: native-image not found at ${GRAALVM_HOME}/bin/native-image"
  echo "Install: sdk install java 25.0.2-graalce"
  exit 1
fi

echo "GraalVM : ${GRAALVM_HOME}"
echo "Version : $("${GRAALVM_HOME}/bin/native-image" --version)"

SKIP_TESTS=""
for arg in "$@"; do [[ "$arg" == "--quick" ]] && SKIP_TESTS="-DskipTests"; done

export JAVA_HOME="${GRAALVM_HOME}"
export PATH="${JAVA_HOME}/bin:${PATH}"

mvn ${SKIP_TESTS} -Pnative package

echo ""
echo "Native executable: target/spilo2cnpg"
echo "Run:  ./target/spilo2cnpg --help"
