# GLAM Jupiter Vote Service

Automates the task for a Jupiter DAO representative to vote on-behalf of their constituents.

## Compile & Run

### GitHub Access Token

Needed to access dependencies hosted on GitHub Package Repository.

Create a `gradle.properties` file in this directory or under `$HOME/.gradle/`.

[Generate a classic token](https://github.com/settings/tokens) with the `read:packages` scope.

```properties
gpr.user=GITHUB_USERNAME
gpr.token=GITHUB_TOKEN
```

### Common Run Args

With dry run enabled all code paths will still be hit, excluding only publishing transactions.

* dryRun="false"
* jvmArgs="-server -XX:+UseZGC -Xms128M -Xmx896M"
* logLevel="INFO"

### Docker

#### Build

Builds an Alpine based image with a custom runtime image using Java jlink.

If your gradle properties file is not local you can pass your GitHub access token via environment variables.

```shell
export GITHUB_ACTOR="GITHUB_USERNAME"
export GITHUB_TOKEN="GITHUB_TOKEN"
docker build \
  --secret type=env,id=GITHUB_ACTOR,env=GITHUB_ACTOR \
  --secret type=env,id=GITHUB_TOKEN,env=GITHUB_TOKEN \
    -t glam-systems/jupiter-vote-service:latest .
```

#### Run Script

`configDirectory` must be absolute.

##### Args

* configFileName=""
* configDirectory="$(pwd)/.config"
* dockerImageName="glam-systems/jupiter-vote-service:latest"
* dockerRunFlags="--detach --name jupiter_vote_service --memory 1g"

```shell
./runDockerImage.sh --configFileName=vote_service.json
```

### Local Compile Script

Java JDK 23 or later is required.

Compiles a custom runtime image using Java jlink, that can be found at
`jupiter_vote_service/build/jupiter_vote_service/bin/java`.

```shell
 ./compile.sh
```

### Local Run Script

#### Args

* configFile=""
* screen=[0|1]

```shell
./runService.sh --configFile=.config/vote_service.json
```

-Dsystems.glam.jupiter_vote_service.config=.config/vote_service.json
-Dsystems.glam.jupiter_vote_service.dry_run=false
