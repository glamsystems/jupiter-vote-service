# GLAM Jupiter Vote Service

Automates the task for a Jupiter DAO representative to vote on-behalf of their constituents.

If a GLAM is observed to cast their own vote the service will NOT override the users vote for that proposal.

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
  "api": {
    "basePath": "/api/v0/",
    "port": 7073
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
  "rpcCallWeights": {
    "getProgramAccounts": 2,
    "getTransaction": 5,
    "sendTransaction": 10
  },
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
  "newVoteBatchSize": 3,
  "changeVoteBatchSize": 6,
  "maxSOLPriorityFee": 0.00042
}
```

<!-- TOC -->

* [signingService](#signingservice)
  * [Delegation Requirements](#delegation-requirements)
* [workDir](#workdir)
* [ballotFilePath](#ballotfilepath)
* [formatter](#formatter)
* [notificationHooks](#notificationhooks)
* [rpcCallWeights](#rpccallweights)
* [rpc](#rpc)
* [sendRPC](#sendrpc)
* [helius](#helius)
* [websocket](#websocket)
* [minLockedToVote](#minlockedtovote)
* [stopVotingBeforeEndDuration](#stopvotingbeforeendduration)
* [newVoteBatchSize](#newvotebatchsize)
* [changeVoteBatchSize](#changevotebatchsize)
* [maxSOLPriorityFee](#maxSOLPriorityFee)

<!-- TOC -->

### `signingService`

Service key used to pay for transactions.

See [Sava KMS](https://sava.software/libraries/kms#service-configuration) for some example options and configuration.

#### Delegation Requirements

Each GLAM that delegates to this service key must have the following minimum configuration.

##### Integrations

* JupiterVote

##### Permissions

* VoteOnProposal

### `workDir`

Used to cache run time state and track the proposal vote state for each delegated GLAM.

### `ballotFilePath`

Holds the representatives vote side for each proposal they wish to vote on.

The `proposal` key can be found in the URL for each proposal page hosted by vote.jup.ag,
e.g., https://vote.jup.ag/proposal/ByQ21v3hqdQVwPHsfwurrtEAH8pB3DYuLdp9jU2Hwnd4.

The representative may change their vote by updating this file and restarting the service.

#### JSON Configuration

TODO: Map text based side to int.

```json
[
  {
    "proposal": "ByQ21v3hqdQVwPHsfwurrtEAH8pB3DYuLdp9jU2Hwnd4",
    "side": 3
  }
]
```

### [`formatter`](https://sava.software/libraries/ravina#chain-item-formatter)

### [`notificationHooks`](https://sava.software/libraries/ravina#webhook-configuration)

Detailed JSON messages will be POSTed to each webhook endpoint.

### `api`

Local webserver to provide service information.

#### Defaults

```json
{
  "basePath": "/api/v0/",
  "port": 7073
}
```

### `rpcCallWeights`

Define call weights per RPC call to help match your providers API limits. See
the [CallWeights](https://github.com/sava-software/services/blob/main/solana/src/main/java/software/sava/services/solana/remote/call/CallWeights.java)
class for which are supported.

#### Defaults

```json
{
  "getProgramAccounts": 2,
  "getTransaction": 5,
  "sendTransaction": 10
}
```

### `rpc`

* `defaultCapacity`
* `defaultBackoff`
* `endpoints`: Array of RPC node configurations.
  * `url`
  * `capacity`: Overrides `defaultCapacity`
  * `backoff`: Overrides `defaultBackoff`

#### Defaults

```json
{
  "defaultCapacity": {
    "maxCapacity": 10,
    "resetDuration": "1S",
    "minCapacityDuration": "13S"
  }
}
```

### `sendRPC`

Same as `rpc` but only used for publishing transactions to the network. Defaults to the `rpc` configuration if not
provided.

#### Defaults

```json
{
  "defaultCapacity": {
    "maxCapacity": 1,
    "resetDuration": "1S",
    "minCapacityDuration": "5S"
  }
}
```

### `helius`

Same as a single `rpc` `endpoint` entry. Only used for estimating priority fees.

#### Defaults

```json
{
  "capacity": {
    "maxCapacity": 3,
    "resetDuration": "1S",
    "minCapacityDuration": "5S"
  }
}
```

### `websocket`

Used to track GLAM Vault account changes.

* `endpoint`
* `backoff`

### `minLockedToVote`

Defaults to 1 JUP.

### `stopVotingBeforeEndDuration`

Stop executing vote transactions this close to the end of the proposal conclusion.

### `newVoteBatchSize`

Number of GLAM vaults to cast new votes for per transaction. Each new vote requires two instructions, create vote
account and cast vote. The service will dynamically reduce this size if a transaction exceeds the size limit.

Defaults to 5.

### `changeVoteBatchSize`

Number of GLAM vaults to change votes for per transaction. Each vote change requires one instruction, cast vote. The
service will dynamically reduce this size if a transaction exceeds the size limit.

Defaults to 10.

### `maxSOLPriorityFee`

Caps the maximum priority fee for all transactions within the system. Default is 0.00042 SOL.
