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

## Configuration

### Primitives

#### Time

Durations/windows/delays are [ISO-8601 duration](https://en.wikipedia.org/wiki/ISO_8601#Durations) formatted
`PnDTnHnMn.nS`,  `PT` may be omitted.

#### `capacity`

Request capacity limits for constrained resources.

* `maxCapacity`: Maximum requests that can be made within `resetDuration`
* `resetDuration`: `maxCapacity` is added over the course of this duration.
* `minCapacityDuration`: Maximum time before capacity should recover to a positive value, given that no additional
  failures happen.

#### `backoff`

Backoff strategy in response to errors.

* `type`: single, linear, fibonacci, exponential
* `initialRetryDelay`
* `maxRetryDelay`

### Example

See context below.

```json
{
  "signingService": {
    "factoryClass": "software.sava.kms.core.signing.MemorySignerFromFilePointerFactory",
    "config": {
      "filePath": ".config/service_key.json"
    }
  },
  "workDir": ".vote",
  "ballotFilePath": ".config/glam_ballot.json",
  "formatter": {
    "sig": "https://solscan.io/tx/%s",
    "address": "https://solscan.io/account/%s"
  },
  "notificationHooks": [
    {
      "endpoint": "https://hooks.slack.com/<URL_PATH_PROVIDED_BY_SLACK>",
      "provider": "SLACK",
      "capacity": {
        "maxCapacity": 2,
        "resetDuration": "1S",
        "minCapacityDuration": "8S"
      }
    }
  ],
  "rpc": {
    "defaultCapacity": {
      "minCapacityDuration": "PT8S",
      "maxCapacity": 4,
      "resetDuration": "PT1S"
    },
    "endpoints": [
      {
        "url": "https://mainnet.helius-rpc.com/?api-key=",
        "capacity": {
          "minCapacityDuration": "PT5S",
          "maxCapacity": 50,
          "resetDuration": "PT1S"
        }
      },
      {
        "url": "https://solana-mainnet.rpc.extrnode.com/"
      }
    ]
  },
  "sendRPC": {
    "defaultCapacity": {
      "maxCapacity": 1,
      "resetDuration": "1S",
      "minCapacityDuration": "8S"
    },
    "endpoints": [
      {
        "url": "https://staked.helius-rpc.com/?api-key="
      }
    ]
  },
  "rpcCallWeights": {
    "getProgramAccounts": 2,
    "getTransaction": 5,
    "sendTransaction": 10
  },
  "websocket": {
    "endpoint": "wss://mainnet.helius-rpc.com/?api-key="
  },
  "helius": {
    "url": "https://mainnet.helius-rpc.com/?api-key=",
    "capacity": {
      "maxCapacity": 2,
      "resetDuration": "1S",
      "minCapacityDuration": "8S"
    }
  },
  "schedule": {
    "initialDelay": 0,
    "delay": 1,
    "timeUnit": "HOURS"
  },
  "minLockedToVote": 1,
  "stopVotingBeforeEndDuration": "42S",
  "confirmVoteTxAfterDuration": "42S",
  "newVoteBatchSize": 3,
  "changeVoteBatchSize": 6
}
```

### `signingService`

Service key used to pay for transactions.

See [Sava KMS](https://github.com/sava-software/kms?tab=readme-ov-file#local-disk-to-in-memory) for some example
options/implementations. The Google KMS dependency is not included in this base service, using that or providing your
own will require building your own executable, but everything else can be used.

The purpose is to separate signing operations so that this service does not have to be trusted with a private key and
also so that transactions being signed may be independently audited.

#### Delegation Requirements

Each GLAM that delegates to this service key must have the following minimum configuration.

Integrations:

* JupiterVote

Permissions:

* VoteOnProposal
