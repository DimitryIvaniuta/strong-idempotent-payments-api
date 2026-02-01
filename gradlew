#!/usr/bin/env sh
# Minimal Gradle wrapper script
DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
JAVA_CMD="${JAVA_HOME:-}/bin/java"
if [ ! -x "$JAVA_CMD" ]; then
  JAVA_CMD="java"
fi
CLASSPATH="$DIR/gradle/wrapper/gradle-wrapper.jar"
exec "$JAVA_CMD" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
