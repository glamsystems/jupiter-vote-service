#!/usr/bin/env bash

set -e

targetJavaVersion=23
simpleProjectName="jupiter_vote_service"

for arg in "$@"
do
  if [[ "$arg" =~ ^--.* ]]; then
    key="${arg%%=*}"
    key="${key##*--}"
    val="${arg#*=}"

    case "$key" in
      tjv | targetJavaVersion) targetJavaVersion="$val";;
      *)
          printf "Unsupported flag '%s' [key=%s] [val=%s].\n" "$arg" "$key" "$val";
          exit 1;
        ;;
    esac
  else
    printf "Unhandled argument '%s', all flags must begin with '%s'.\n" "$arg" "--";
    exit 1;
  fi
done

javaVersion=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | grep -oEi '^[0-9]+')
readonly javaVersion
if [[ "$javaVersion" -ne "$targetJavaVersion" ]]; then
  echo "Invalid Java version $javaVersion must be $targetJavaVersion."
  exit 3
fi

./gradlew clean --exclude-task=test ":$simpleProjectName:jlink" -PnoVersionTag=true

javaExe="$(pwd)/$simpleProjectName/build/$simpleProjectName/bin/java"
readonly javaExe

echo "$javaExe"
