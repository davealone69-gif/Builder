#!/usr/bin/env sh
# Gradle wrapper script for Unix
APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
CLASSPATH="$SCRIPT_DIR/gradle/wrapper/gradle-wrapper.jar"
exec java -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
