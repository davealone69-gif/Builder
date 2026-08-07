#!/usr/bin/env sh
# Gradle wrapper script for Unix
APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
CLASSPATH="$SCRIPT_DIR/gradle/wrapper/gradle-wrapper.jar"
exec java -classpath "$CLASSPATH"
dependencies {
    // Import the BoM for the Firebase platform
    implementation(platform("com.google.firebase:firebase-bom:34.17.0"))

    // Add the dependency for the Firebase Authentication library
    // When using the BoM, you don't specify versions in Firebase library dependencies
    implementation("com.google.firebase:firebase-auth")
} org.gradle.wrapper.GradleWrapperMain "$@"
