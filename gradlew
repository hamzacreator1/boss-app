#!/bin/sh
#
# Gradle wrapper script — Boss app
#

APP_BASE_NAME=${0##*/}
APP_HOME=$(cd "${0%/*}" >/dev/null && pwd -P)

DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

exec java $DEFAULT_JVM_OPTS \
  -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
  org.gradle.wrapper.GradleWrapperMain "$@"
