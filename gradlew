#!/bin/sh
#
# Gradle wrapper script — Boss app
#

APP_HOME=$(cd "${0%/*}" >/dev/null && pwd -P)

exec java -Xmx64m -Xms64m \
  -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
  org.gradle.wrapper.GradleWrapperMain "$@"
