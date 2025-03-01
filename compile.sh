#!/usr/bin/env bash

set -e

targetJavaVersion=23
readonly targetJavaVersion
simpleProjectName="jupiter_vote_service"
readonly simpleProjectName

javaVersion=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | grep -oEi '^[0-9]+')
readonly javaVersion
if [[ "$javaVersion" -ne "$targetJavaVersion" ]]; then
  echo "Invalid Java version $javaVersion must be $targetJavaVersion."
  exit 3
fi

./gradlew clean --no-daemon --exclude-task=test ":$simpleProjectName:jlink" -PnoVersionTag=true

javaExe="$(pwd)/$simpleProjectName/build/$simpleProjectName/bin/java"
readonly javaExe

echo "$javaExe"
