#!/bin/sh
DIRNAME=$(dirname "$0")
exec java -jar "$DIRNAME/gradle/wrapper/gradle-wrapper.jar" "$@"
