#!/bin/sh

# Gradle startup script for POSIX generated for Suno Local Player.
# Uses the checked-in Gradle Wrapper JAR under gradle/wrapper/.
# Keep this wrapper tiny and project-local so the Android MVP can build without a system Gradle install.

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P) || exit
DEFAULT_JVM_OPTS='-Xmx64m -Xms64m'

if [ -n "$JAVA_HOME" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD="java"
fi

if [ ! -x "$JAVACMD" ]; then
  echo "ERROR: JAVA_HOME is not set and no 'java' command could be found in PATH." >&2
  exit 1
fi

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# shellcheck disable=SC2086
exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \
  -Dorg.gradle.appname=gradlew \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain "$@"
