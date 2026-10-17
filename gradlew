#!/bin/sh

#
# Gradle start up script for POSIX generated for the ExBro project.
#
# Menjalankan Gradle sesuai versi yang tercatat pada
# gradle/wrapper/gradle-wrapper.properties. Bila gradle-wrapper.jar belum ada,
# skrip mengunduhnya sekali dari repositori resmi Gradle.
#

set -e

# ---------------------------------------------------------------------------
# Tentukan APP_HOME (mengikuti symlink)
# ---------------------------------------------------------------------------
PRG="$0"
while [ -h "$PRG" ]; do
    ls=$(ls -ld "$PRG")
    link=$(expr "$ls" : '.*-> \(.*\)$')
    case $link in
        /*) PRG="$link" ;;
        *)  PRG=$(dirname "$PRG")/"$link" ;;
    esac
done
APP_HOME=$(cd "$(dirname "$PRG")" > /dev/null && pwd -P)

WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_PROPERTIES="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"
WRAPPER_JAR_URL="https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradle/wrapper/gradle-wrapper.jar"

# ---------------------------------------------------------------------------
# Cari perintah java
# ---------------------------------------------------------------------------
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
elif command -v java > /dev/null 2>&1; then
    JAVACMD=java
else
    echo "ERROR: Java tidak ditemukan." >&2
    echo "Set JAVA_HOME ke JDK 17, atau pakai JDK bawaan Android Studio:" >&2
    echo "  export JAVA_HOME=\"/c/Program Files/Android/Android Studio/jbr\"" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Unduh gradle-wrapper.jar bila belum tersedia
# ---------------------------------------------------------------------------
if [ ! -f "$WRAPPER_JAR" ]; then
    echo "gradle-wrapper.jar belum ada, mengunduh dari repositori resmi Gradle..."
    mkdir -p "$APP_HOME/gradle/wrapper"
    if command -v curl > /dev/null 2>&1; then
        curl -fsSL -o "$WRAPPER_JAR" "$WRAPPER_JAR_URL"
    elif command -v wget > /dev/null 2>&1; then
        wget -q -O "$WRAPPER_JAR" "$WRAPPER_JAR_URL"
    else
        echo "ERROR: curl/wget tidak tersedia. Unduh manual dari:" >&2
        echo "  $WRAPPER_JAR_URL" >&2
        echo "lalu simpan sebagai gradle/wrapper/gradle-wrapper.jar" >&2
        exit 1
    fi
    echo "Selesai: $WRAPPER_JAR"
fi

if [ ! -f "$WRAPPER_PROPERTIES" ]; then
    echo "ERROR: $WRAPPER_PROPERTIES tidak ditemukan." >&2
    exit 1
fi

exec "$JAVACMD" \
    $JAVA_OPTS $GRADLE_OPTS \
    "-Dorg.gradle.appname=gradlew" \
    -classpath "$WRAPPER_JAR" \
    org.gradle.wrapper.GradleWrapperMain "$@"
