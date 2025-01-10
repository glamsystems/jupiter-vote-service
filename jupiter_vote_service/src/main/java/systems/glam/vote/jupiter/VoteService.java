package systems.glam.vote.jupiter;

import software.sava.anchor.programs.glam.GlamAccounts;
import software.sava.anchor.programs.glam.GlamJupiterVoteClient;
import software.sava.anchor.programs.glam.anchor.types.FundAccount;
import software.sava.anchor.programs.glam.anchor.types.Permission;
import software.sava.anchor.programs.jupiter.JupiterAccounts;
import software.sava.anchor.programs.jupiter.governance.anchor.types.Proposal;
import software.sava.anchor.programs.jupiter.voter.anchor.types.Escrow;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.kms.core.signing.SigningService;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.services.core.config.RemoteResourceConfig;
import software.sava.services.core.remote.call.Call;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.solana.alt.LookupTableCache;
import software.sava.services.solana.config.ChainItemFormatter;
import software.sava.services.solana.epoch.EpochInfoService;
import software.sava.services.solana.remote.call.RpcCaller;
import software.sava.services.solana.transactions.TransactionProcessor;
import software.sava.services.solana.transactions.TxMonitorConfig;
import software.sava.services.solana.transactions.TxMonitorService;
import software.sava.services.solana.websocket.WebSocketManager;
import software.sava.solana.web2.helius.client.http.HeliusClient;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.lang.System.Logger.Level.*;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static software.sava.anchor.programs.glam.GlamProgramAccountClient.isDelegatedWithPermission;
import static software.sava.rpc.json.http.client.SolanaRpcClient.MAX_MULTIPLE_ACCOUNTS;

public final class VoteService implements Consumer<AccountInfo<byte[]>>, Runnable, AutoCloseable {

  private static final System.Logger logger = System.getLogger(VoteService.class.getName());
  private static final Permission VOTE_PERMISSION = Permission.Unstake;

  private final ChainItemFormatter formatter;
  private final PublicKey servicePublicKey;
  private final long delayMillis;
  private final GlamAccountsCache glamAccountsCache;
  private final Path ballotFilePath;
  private final Path proposalsDirectory;
  private final Map<PublicKey, FundAccount> delegatedGlams;
  private final RpcCaller rpcCaller;
  private final TransactionProcessor transactionProcessor;
  private final TxMonitorService txMonitorService;
  private final WebSocketManager webSocketManager;
  private final long minLockedToVote;
  private final long stopVotingSecondsBeforeEnd;
  private final long confirmVoteTxAfterSeconds;
  private int newVoteBatchSize;
  private int changeVoteBatchSize;

  public VoteService(final ChainItemFormatter formatter,
                     final SigningService signingService,
                     final PublicKey servicePublicKey,
                     final ExecutorService executor,
                     final long delayMillis,
                     final SolanaAccounts solanaAccounts,
                     final JupiterAccounts jupiterAccounts,
                     final GlamAccounts glamAccounts,
                     final Path ballotFilePath,
                     final Path workDirectory,
                     final RpcCaller rpcCaller,
                     final LoadBalancer<SolanaRpcClient> sendClients,
                     final LoadBalancer<HeliusClient> heliusClients,
                     final EpochInfoService epochInfoService,
                     final RemoteResourceConfig webSocketConfig,
                     final TxMonitorConfig txMonitorConfig,
                     final LookupTableCache lookupTableCache,
                     final long minLockedToVote,
                     final Duration stopVotingBeforeEndDuration,
                     final Duration confirmVoteTxAfterDuration,
                     final int newVoteBatchSize,
                     final int changeVoteBatchSize) {
    this.formatter = formatter;
    this.servicePublicKey = servicePublicKey;
    this.delayMillis = delayMillis;
    this.proposalsDirectory = workDirectory.resolve(".proposals");
    this.rpcCaller = rpcCaller;
    this.minLockedToVote = minLockedToVote;
    this.stopVotingSecondsBeforeEnd = stopVotingBeforeEndDuration.toSeconds();
    this.confirmVoteTxAfterSeconds = confirmVoteTxAfterDuration.toSeconds();
    this.newVoteBatchSize = newVoteBatchSize;
    this.ballotFilePath = ballotFilePath;
    this.glamAccountsCache = GlamAccountsCache.createCache(solanaAccounts, glamAccounts, jupiterAccounts);
    this.delegatedGlams = new ConcurrentHashMap<>();
    final var glamProgram = glamAccounts.program();
    final var glamFilter = List.of(FundAccount.DISCRIMINATOR_FILTER);
    this.webSocketManager = WebSocketManager.createManager(
        HttpClient.newHttpClient(),
        webSocketConfig.endpoint(),
        webSocketConfig.backoff(),
        webSocket -> webSocket.programSubscribe(glamProgram, glamFilter, this)
    );
    this.txMonitorService = TxMonitorService.createService(
        formatter,
        rpcCaller,
        epochInfoService,
        webSocketManager,
        txMonitorConfig.minSleepBetweenSigStatusPolling(),
        txMonitorConfig.webSocketConfirmationTimeout()
    );
    this.transactionProcessor = TransactionProcessor.createProcessor(
        executor,
        signingService,
        lookupTableCache,
        servicePublicKey,
        solanaAccounts,
        formatter,
        rpcCaller.rpcClients(),
        sendClients,
        heliusClients,
        rpcCaller.callWeights(),
        webSocketManager
    );
    this.changeVoteBatchSize = changeVoteBatchSize;
  }

  int newVoteBatchSize() {
    return newVoteBatchSize;
  }

  int reduceNewNewBatchSize(final int by) {
    return newVoteBatchSize = Math.max(1, newVoteBatchSize - by);
  }

  int reduceNewVoteBatchSize() {
    return reduceNewNewBatchSize(1);
  }

  int changeVoteBatchSize() {
    return changeVoteBatchSize;
  }

  int reduceChangeVoteBatchSize(final int by) {
    return changeVoteBatchSize = Math.max(1, changeVoteBatchSize - by);
  }

  int reduceChangeVoteBatchSize() {
    return reduceChangeVoteBatchSize(1);
  }

  boolean hasMinLockedToVote(final long amount) {
    return Long.compareUnsigned(amount, minLockedToVote) < 0;
  }

  boolean eligibleToVote(final Escrow escrow) {
    return escrow.isMaxLock()
        && hasMinLockedToVote(escrow.amount())
        && escrow.voteDelegate().equals(escrow.owner());
  }

  PublicKey servicePublicKey() {
    return servicePublicKey;
  }

  RpcCaller rpcCaller() {
    return rpcCaller;
  }

  TransactionProcessor transactionProcessor() {
    return transactionProcessor;
  }

  TxMonitorService transactionMonitorService() {
    return txMonitorService;
  }

  long confirmVoteTxAfterSeconds() {
    return confirmVoteTxAfterSeconds;
  }

  ChainItemFormatter chainItemFormatter() {
    return formatter;
  }

  private static VoteService createService(final ExecutorService serviceExecutor,
                                           final ExecutorService taskExecutor,
                                           final HttpClient httpClient) throws InterruptedException {
    final var config = VoteServiceConfig.loadConfig(httpClient);

    final var signingService = config.signingServiceConfig().signingService();
    final var serviceKeyFuture = signingService.publicKeyWithRetries();

    final var jupiterConfig = config.jupiterConfig();
    final var jupiterClient = jupiterConfig.createClient(httpClient);
    final var jupiterAccounts = JupiterAccounts.MAIN_NET;
    final var tokenContextFuture = Call.createCourteousCall(
        () -> jupiterClient.token(jupiterAccounts.jupTokenMint()),
        jupiterConfig.capacityMonitor().capacityState(),
        jupiterConfig.backoff(),
        "jupiterClient::token"
    ).async(taskExecutor);

    final var rpcClients = config.rpcClients();
    final var rpcCaller = new RpcCaller(taskExecutor, rpcClients, config.callWeights());

    final var tableCacheConfig = config.tableCacheConfig();
    final var lookupTableCache = LookupTableCache.createCache(
        taskExecutor,
        tableCacheConfig.initialCapacity(),
        rpcCaller.rpcClients()
    );

    final var scheduleConfig = config.scheduleConfig();
    final long delayMillis = scheduleConfig.toDuration().toMillis();
    final long initialDelay = scheduleConfig.initialDelay();
    if (initialDelay > 0) {
      logger.log(INFO, String.format("Starting service in %d %s.", initialDelay, scheduleConfig.timeUnit()));
      scheduleConfig.timeUnit().sleep(initialDelay);
    }

    logger.log(INFO, "Starting epoch info service.");
    final var epochInfoService = EpochInfoService.createService(config.epochServiceConfig(), rpcClients);
    serviceExecutor.execute(epochInfoService);

    final long minLockedToVote = Math.max(1, tokenContextFuture.join().fromDecimal(config.minLockedToVote()).longValue());
    final var servicePublicKey = serviceKeyFuture.join();
    return new VoteService(
        config.chainItemFormatter(),
        signingService,
        servicePublicKey,
        taskExecutor,
        delayMillis,
        SolanaAccounts.MAIN_NET,
        jupiterAccounts,
        GlamAccounts.MAIN_NET,
        config.ballotFilePath(),
        config.workDir(),
        rpcCaller,
        config.sendClients(),
        config.heliusClient(),
        epochInfoService,
        config.websocketConfig(),
        config.txMonitorConfig(),
        lookupTableCache,
        minLockedToVote,
        config.stopVotingBeforeEndDuration(),
        config.confirmVoteTxAfterDuration(),
        config.newVoteBatchSize(),
        config.changeVoteBatchSize()
    );
  }

  public static void main(final String[] args) throws InterruptedException {
    try (final var serviceExecutor = Executors.newFixedThreadPool(2)) {
      try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        try (final var httpClient = HttpClient.newHttpClient()) {
          try (final var voteService = createService(serviceExecutor, executor, httpClient)) {
            voteService.run();
          } catch (final RuntimeException ex) {
            logger.log(ERROR, "Unhandled service failure.", ex);
          }
        }
      }
    }
  }

  @Override
  public void accept(final AccountInfo<byte[]> accountInfo) {
    try {
      final var fundAccount = FundAccount.read(accountInfo.pubKey(), accountInfo.data());
      if (isDelegatedWithPermission(fundAccount, servicePublicKey, VOTE_PERMISSION)) {
        delegatedGlams.put(fundAccount._address(), fundAccount);
      }
    } catch (final RuntimeException ex) {
      logger.log(ERROR, "Failed to parse fund account " + accountInfo.pubKey(), ex);
    }
  }

  private void fetchDelegatedGlamsWithPermission() {
    logger.log(INFO, "Fetching all glam accounts.");
    final var glamAccounts = rpcCaller.courteousGet(
        rpcClient -> rpcClient.getProgramAccounts(
            glamAccountsCache.glamAccounts().program(),
            List.of(FundAccount.DISCRIMINATOR_FILTER)
        ),
        rpcCaller.callWeights().getProgramAccounts(),
        "rpcClient::getProgramAccounts"
    );

    logger.log(INFO, String.format("Retrieved %d glam accounts.", glamAccounts.size()));
    for (final var accountInfo : glamAccounts) {
      accept(accountInfo);
    }
    logger.log(INFO, String.format(
        "Filtered %d delegated glam accounts with permission to vote.",
        delegatedGlams.size()
    ));
  }

  private boolean inActiveProposal(final PublicKey proposalKey,
                                   final Proposal proposal,
                                   final Iterator<ProposalVote> iterator) {
    if (proposal == null) {
      throw new IllegalStateException("No proposal account exists for " + proposalKey);
    } else if (proposal.canceledAt() > 0) {
      iterator.remove();
      logger.log(WARNING, String.format("Proposal %s has been cancelled.", proposalKey));
      return true;
    } else if (proposal.activatedAt() <= 0) {
      return true;
    } else {
      final long votingEndsAt = proposal.votingEndsAt();
      if (votingEndsAt > 0) {
        final long _nowEpochSeconds = Instant.now().getEpochSecond() + stopVotingSecondsBeforeEnd;
        if (_nowEpochSeconds > votingEndsAt) {
          iterator.remove();
          logger.log(WARNING, String.format("Voting for proposal %s has ended.", proposalKey));
          return true;
        }
      }
      return false;
    }
  }

  private static boolean cancelledOrEnded(final Proposal proposal, final long nowEpochSeconds) {
    if (proposal.canceledAt() >= 0) {
      return true;
    } else {
      final long votingEndsAt = proposal.votingEndsAt();
      return votingEndsAt > 0 && nowEpochSeconds > votingEndsAt;
    }
  }

  private void writeBallotFile(final Collection<ProposalVote> proposalVotes) {
    final var json = proposalVotes.stream()
        .map(proposalVote -> proposalVote.toJson().indent(2).stripTrailing())
        .collect(Collectors.joining("\n", "[", "]"));
    try {
      Files.writeString(ballotFilePath, json, TRUNCATE_EXISTING, WRITE);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void fetchProposalBatch(final Map<PublicKey, Proposal> proposalAccountStateMap,
                                  final List<PublicKey> batch) {
    logger.log(INFO, String.format("Fetching %d proposal account(s).", batch.size()));
    final var proposalAccounts = rpcCaller.courteousGet(
        rpcClient -> rpcClient.getMultipleAccounts(batch),
        "rpcClient::getMultipleAccounts"
    );
    for (final var accountInfo : proposalAccounts) {
      final var proposalKey = accountInfo.pubKey();
      final var proposal = Proposal.read(proposalKey, accountInfo.data());
      proposalAccountStateMap.put(proposalKey, proposal);
    }
  }

  private Map<PublicKey, Proposal> fetchProposals(final Collection<ProposalVote> proposalVotes) {
    final int numProposals = proposalVotes.size();
    if (numProposals == 0) {
      return Map.of();
    }

    final var proposalAccountStateMap = HashMap.<PublicKey, Proposal>newHashMap(numProposals);
    final var allProposalKeys = proposalVotes.stream().map(ProposalVote::proposal).toList();

    if (numProposals <= MAX_MULTIPLE_ACCOUNTS) {
      fetchProposalBatch(proposalAccountStateMap, allProposalKeys);
    } else {
      for (int from = 0, to; from < numProposals; from = to) {
        to = Math.min(from + MAX_MULTIPLE_ACCOUNTS, numProposals);
        fetchProposalBatch(proposalAccountStateMap, allProposalKeys.subList(from, to));
      }
    }

    return proposalAccountStateMap;
  }

  @Override
  @SuppressWarnings({"BusyWait"})
  public void run() {
    logger.log(INFO, "Starting service with key " + servicePublicKey.toBase58());
    final var proposalVotes = readBallot();
    final int numProposals = proposalVotes.size();

    if (numProposals == 0) {
      logger.log(INFO, String.format(
          "No proposals configured in the ballot file %s, exiting.",
          ballotFilePath.toAbsolutePath()
      ));
      return;
    }

    final var recordedVotesMap = HashMap.<PublicKey, RecordedProposalVotes>newHashMap(numProposals);
    for (final var proposalVote : proposalVotes) {
      final var recordedVotes = RecordedProposalVotes.createRecord(proposalsDirectory, proposalVote);
      final var proposalKey = proposalVote.proposal();
      recordedVotesMap.put(proposalKey, recordedVotes);
    }

    final var proposalAccountStateMap = fetchProposals(proposalVotes);

    final long nowEpochSeconds = Instant.now().getEpochSecond() + stopVotingSecondsBeforeEnd;
    if (proposalAccountStateMap.values().stream().allMatch(proposal -> cancelledOrEnded(proposal, nowEpochSeconds))) {
      logger.log(INFO, "No pending or active proposals, truncating ballot file and exiting.");
      try {
        Files.writeString(ballotFilePath, "[]", TRUNCATE_EXISTING, WRITE);
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
      return;
    }

    // Initial websocket connection.
    webSocketManager.webSocket();
    fetchDelegatedGlamsWithPermission();

    try {
      // Check for and handle service vote side changes.
      var proposalIterator = proposalVotes.iterator();
      while (proposalIterator.hasNext()) {
        final var proposalVote = proposalIterator.next();
        final var proposalKey = proposalVote.proposal();
        final var recordedProposalVotes = recordedVotesMap.get(proposalKey);
        final var voting = recordedProposalVotes.voting();
        voting.recoverState(
            proposalKey,
            recordedProposalVotes,
            delegatedGlams,
            glamAccountsCache,
            rpcCaller
        );

        final var proposal = proposalAccountStateMap.get(proposalKey);
        if (inActiveProposal(proposalKey, proposal, proposalIterator)) {
          writeBallotFile(proposalVotes);
          logger.log(INFO, String.format("Removed inactive proposal %s from ballot file.", proposalKey.toBase58()));
          continue;
        }

        final int side = proposalVote.side();
        final var recordedOptionVotes = recordedProposalVotes.optionVotes();
        for (int i = 0; i < recordedOptionVotes.length; ++i) {
          final var optionVotes = recordedOptionVotes[i];
          if (optionVotes == null) {
            continue;
          }
          final var glamKeys = optionVotes.glamKeys();
          if (optionVotes.side() != side && !glamKeys.isEmpty()) {
            final var delegatedGlamVoteClients = glamKeys.stream()
                .filter(delegatedGlams::containsKey)
                .map(glamAccountsCache::computeIfAbsent)
                .toArray(GlamJupiterVoteClient[]::new);

            if (delegatedGlamVoteClients.length > 0) {
              final var changeVoteProcessor = new ChangeVoteProcessor(
                  this,
                  proposalKey,
                  proposal,
                  side,
                  delegatedGlamVoteClients,
                  recordedProposalVotes,
                  optionVotes
              );
              changeVoteProcessor.processVotes();
            }

            optionVotes.deleteFile();
            recordedOptionVotes[i] = null;
          }
        }
      }

      // Main loop to cast new votes.
      for (; ; ) {
        final int numGlams = delegatedGlams.size();
        proposalIterator = proposalVotes.iterator();
        while (proposalIterator.hasNext()) {
          final var proposalVote = proposalIterator.next();
          final var proposalKey = proposalVote.proposal();
          final var proposal = proposalAccountStateMap.get(proposalKey);
          if (inActiveProposal(proposalKey, proposal, proposalIterator)) {
            writeBallotFile(proposalVotes);
            logger.log(INFO, String.format("Removed inactive proposal %s from ballot file.", proposalKey.toBase58()));
            continue;
          }

          final var recordedProposalVotes = recordedVotesMap.get(proposalKey);
          final int side = proposalVote.side();

          final var delegatedGlamClientsWithoutVote = delegatedGlams.values().stream()
              .filter(glam -> recordedProposalVotes.needsToVote(glam._address(), side))
              .map(glam -> glamAccountsCache.computeIfAbsent(glam._address()))
              .toArray(GlamJupiterVoteClient[]::new);

          if (delegatedGlamClientsWithoutVote.length > 0) {
            final var newVoteProcessor = new NewVoteProcessor(
                this,
                proposalKey,
                proposal,
                side,
                delegatedGlamClientsWithoutVote,
                recordedProposalVotes
            );
            newVoteProcessor.processVotes();
          }
        }

        if (proposalVotes.isEmpty()) {
          logger.log(INFO, "No more pending or active proposals, exiting.");
          return;
        }

        final long continueAfter = System.currentTimeMillis() + delayMillis;
        do {
          if ((delegatedGlams.size() - numGlams) >= newVoteBatchSize) {
            break;
          }
          webSocketManager.checkConnection();
          Thread.sleep(2_000);
        } while (System.currentTimeMillis() < continueAfter);

        webSocketManager.checkConnection();
        fetchDelegatedGlamsWithPermission();
      }
    } catch (final InterruptedException e) {
      // exit.
    }
  }

  private List<ProposalVote> readBallot() {
    try {
      final byte[] votesFile = Files.readAllBytes(ballotFilePath);
      if (votesFile.length == 0) {
        return List.of();
      }
      try (final var ji = JsonIterator.parse(votesFile)) {
        final var ballots = HashMap.<PublicKey, ProposalVote>newHashMap(MAX_MULTIPLE_ACCOUNTS);
        final var parser = new ProposalVote.Parser();
        while (ji.readArray()) {
          parser.reset();
          ji.testObject(parser);
          final var ballot = parser.create();
          final var previousBallot = ballots.putIfAbsent(ballot.proposal(), ballot);
          if (previousBallot != null) {
            if (previousBallot.side() != ballot.side()) {
              throw new IllegalStateException("Duplicate ballots with different votes for proposal " + ballot.proposal());
            } else {
              logger.log(WARNING, "Duplicate ballot for proposal " + ballot.proposal());
            }
          }
        }
        return new ArrayList<>(ballots.values());
      } catch (final RuntimeException e) {
        logger.log(ERROR, "Failed to parse votes file:\n\n" + new String(votesFile), e);
        throw e;
      }
    } catch (final IOException e) {
      logger.log(ERROR, "Failed to read votes file " + ballotFilePath, e);
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void close() {
    webSocketManager.close();
  }
}
