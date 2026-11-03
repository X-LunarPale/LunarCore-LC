#!/usr/bin/env bash
# Portable wrapper: use configured JDK25, or the local development tools on Windows.
set -e
cd "$(dirname "$0")"
if [ -d "../_tools/jdk-25.0.4.1+1" ]; then
  export JAVA_HOME="$(cd ../_tools/jdk-25.0.4.1+1 && pwd)"
fi
if [ -z "$GRADLE_USER_HOME" ] && [ -d ../_tools/gradle-home ]; then
  export GRADLE_USER_HOME="$(cd ../_tools/gradle-home && pwd)"
fi
exec bash ./gradlew "$@"
