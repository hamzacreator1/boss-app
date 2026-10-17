@rem Gradle wrapper script for the Boss app (Windows)
@echo off
set APP_HOME=%~dp0
java -Xmx64m -Xms64m -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
