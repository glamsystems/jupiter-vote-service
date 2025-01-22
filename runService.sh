#!/usr/bin/env bash

set -e

simpleProjectName="jupiter_vote_service"
moduleName="systems.glam.jupiter_vote_service"
mainClass="systems.glam.vote.jupiter.VoteService"

jvmArgs="-server -XX:+UseZGC -Xms256M -Xmx1G"
logLevel="INFO";
configFile="";
dryRun="false";

screen=0;

for arg in "$@"
do
  if [[ "$arg" =~ ^--.* ]]; then
    key="${arg%%=*}"
    key="${key##*--}"
    val="${arg#*=}"

    case "$key" in
      l | log)
          case "$val" in
            INFO|WARN|DEBUG) logLevel="$val";;
            *)
              printf "'%slog=[INFO|WARN|DEBUG]' not '%s'.\n" "--" "$arg";
              exit 2;
            ;;
          esac
        ;;

      mc | mainClass) mainClass="$val";;
      mn | moduleName) moduleName="$val";;
      spn | simpleProjectName) simpleProjectName="$val";;

      jvm | jvmArgs) jvmArgs="$val";;

      cf | configFile) configFile="$val";;
      dr | dryRun) dryRun="$val";;

      screen)
        case "$val" in
          1|*screen) screen=1 ;;
          0) screen=0 ;;
          *)
            printf "'%sscreen=[0|1]' or '%sscreen' not '%s'.\n" "--" "--" "$arg";
            exit 2;
          ;;
        esac
        ;;

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

javaExe="$(pwd)/$simpleProjectName/build/$simpleProjectName/bin/java"
readonly javaExe

jvmArgs="$jvmArgs -D$moduleName.dryRun=$dryRun -D$moduleName.logLevel=$logLevel -D$moduleName.config=$configFile -m $moduleName/$mainClass"
IFS=' ' read -r -a jvmArgsArray <<< "$jvmArgs"

if [[ "$screen" == 0 ]]; then
  set -x
  "$javaExe" "${jvmArgsArray[@]}"
else
  set -x
  screen -S "$simpleProjectName" "$javaExe" "${jvmArgsArray[@]}"
fi
