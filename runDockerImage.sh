#!/usr/bin/env bash

set -e

moduleName="systems.glam.jupiter_vote_service"
readonly moduleName
mainClass="systems.glam.vote.jupiter.VoteService"
readonly mainClass

dockerImageName="glam-systems/jupiter-vote-service:latest"
dockerRunFlags="--detach --name liquid_stake_service --memory 1g"
jvmArgs="-server -XX:+UseZGC -Xms128M -Xmx896M"
logLevel="INFO";
configDirectory="$(pwd)/.config";
configFileName="";
dryRun="false";

for arg in "$@"
do
  if [[ "$arg" =~ ^--.* ]]; then
    key="${arg%%=*}"
    key="${key##*--}"
    val="${arg#*=}"

    case "$key" in
      cd | configDirectory) configDirectory="$val";;
      cfn | configFileName) configFileName="$val";;
      dr | dryRun) dryRun="$val";;
      drf | dockerRunFlags) dockerRunFlags="$val";;
      jvm | jvmArgs) jvmArgs="$val";;
      l | log)
          case "$val" in
            INFO|WARN|DEBUG) logLevel="$val";;
            *)
              printf "'%slog=[INFO|WARN|DEBUG]' not '%s'.\n" "--" "$arg";
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

IFS=' ' read -r -a dockerRunFlagArray <<< "$dockerRunFlags"
IFS=' ' read -r -a jvmArgsArray <<< "$jvmArgs"

set -x
docker run "${dockerRunFlagArray[@]}" \
  --mount type=bind,source="$configDirectory",target=/glam/.config/,readonly \
    "$dockerImageName" \
      "${jvmArgsArray[@]}" \
      "-D$moduleName.dryRun=$dryRun" \
      "-D$moduleName.logLevel=$logLevel" \
      "-D$moduleName.config=/glam/.config/$configFileName" \
      -m "$moduleName/$mainClass"
