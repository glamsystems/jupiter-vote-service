package systems.glam.vote.jupiter;

import software.sava.anchor.programs.glam.GlamAccounts;
import software.sava.anchor.programs.glam.GlamJupiterVoteClient;
import software.sava.anchor.programs.glam.anchor.types.Integration;
import software.sava.anchor.programs.glam.anchor.types.Permission;
import software.sava.anchor.programs.glam.anchor.types.StateAccount;
import software.sava.anchor.programs.jupiter.JupiterAccounts;
import software.sava.anchor.programs.jupiter.governance.anchor.types.Proposal;
import software.sava.anchor.programs.jupiter.voter.anchor.types.Escrow;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.rpc.Filter;
import software.sava.kms.core.signing.SigningService;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.services.core.config.RemoteResourceConfig;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.solana.alt.LookupTableCache;
import software.sava.services.solana.config.ChainItemFormatter;
import software.sava.services.solana.epoch.EpochInfoService;
import software.sava.services.solana.remote.call.RpcCaller;
import software.sava.services.solana.transactions.HeliusFeeProvider;
import software.sava.services.solana.transactions.TransactionProcessor;
import software.sava.services.solana.transactions.TxMonitorConfig;
import software.sava.services.solana.transactions.TxMonitorService;
import software.sava.services.solana.websocket.WebSocketManager;
import software.sava.solana.web2.jupiter.client.http.request.JupiterTokenTag;
import software.sava.solana.web2.jupiter.client.http.response.TokenContext;
import software.sava.solana.web2.jupiter.client.http.response.TokenExtension;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
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
import static software.sava.anchor.programs.glam.GlamProgramAccountClient.hasIntegration;
import static software.sava.anchor.programs.glam.GlamProgramAccountClient.isDelegatedWithPermission;
import static software.sava.rpc.json.http.client.SolanaRpcClient.MAX_MULTIPLE_ACCOUNTS;

public final class VoteService implements Consumer<AccountInfo<byte[]>>, Runnable, AutoCloseable {

  private static final System.Logger logger = System.getLogger(VoteService.class.getName());
  private static final Permission VOTE_PERMISSION = Permission.VoteOnProposal;

  private static VoteService createService(final ExecutorService serviceExecutor,
                                           final ExecutorService taskExecutor,
                                           final HttpClient httpClient) throws InterruptedException {
    final var config = VoteServiceConfig.loadConfig(taskExecutor, httpClient);

    final var signingService = config.signingServiceConfig().signingService();
    final var serviceKeyFuture = signingService.publicKeyWithRetries();

    final var rpcCaller = config.rpcCaller();

    final var jupiterAccounts = JupiterAccounts.MAIN_NET;
    final var tokenContext = new TokenContext(
        jupiterAccounts.jupTokenMint(),
        6,
        "Jupiter",
        "JUP",
        "https://static.jup.ag/jup/icon.png",
        EnumSet.of(JupiterTokenTag.verified, JupiterTokenTag.community, JupiterTokenTag.strict),
        BigDecimal.ZERO,
        null,
        null,
        null,
        Instant.parse("2024-04-26T10:56:58.893768Z"),
        Instant.parse("2024-01-25T08:54:23Z"),
        Map.of(TokenExtension.coingeckoId, "jupiter-exchange-solana")
    );

    final var solanaAccounts = SolanaAccounts.MAIN_NET;
    final var glamAccounts = GlamAccounts.MAIN_NET;
    final var glamAccountsCache = GlamAccountsCache.createCache(solanaAccounts, glamAccounts, jupiterAccounts);

    final var workDir = config.workDir();
    final var apiConfig = config.apiConfig();
    final var webServer = VoteServiceWebServer.createServer(
        taskExecutor,
        apiConfig.port(),
        apiConfig.basePath(),
        workDir,
        glamAccountsCache,
        rpcCaller,
        tokenContext
    );
    webServer.start();

    final var rpcClients = rpcCaller.rpcClients();
    final var tableCacheConfig = config.tableCacheConfig();
    final var lookupTableCache = LookupTableCache.createCache(
        taskExecutor,
        tableCacheConfig.initialCapacity(),
        rpcClients
    );

    final long minLockedToVote = Math.max(1, tokenContext.fromDecimal(config.minLockedToVote()).longValue());

    final var epochInfoService = EpochInfoService.createService(config.epochServiceConfig(), rpcCaller);

    final var scheduleConfig = config.scheduleConfig();
    final long delayMillis = scheduleConfig.toDuration().toMillis();
    final long initialDelay = scheduleConfig.initialDelay();
    if (initialDelay > 0) {
      logger.log(INFO, String.format("Starting service in %d %s.", initialDelay, scheduleConfig.timeUnit()));
      scheduleConfig.timeUnit().sleep(initialDelay);
    }

    logger.log(INFO, "Starting epoch info service.");
    serviceExecutor.execute(epochInfoService);

    final var servicePublicKey = serviceKeyFuture.join();

    return new VoteService(
        config.chainItemFormatter(),
        signingService,
        servicePublicKey,
        taskExecutor,
        delayMillis,
        solanaAccounts,
        glamAccounts,
        glamAccountsCache,
        config.ballotFilePath(),
        workDir,
        rpcCaller,
        config.sendClients(),
        config.feeProviders(),
        epochInfoService,
        config.websocketConfig(),
        config.txMonitorConfig(),
        config.maxLamportPriorityFee(),
        lookupTableCache,
        webServer,
        minLockedToVote,
        config.stopVotingBeforeEndDuration(),
        config.newVoteBatchSize(),
        config.changeVoteBatchSize()
    );
  }

  public static void main(final String[] args) {
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      try (final var serviceExecutor = Executors.newFixedThreadPool(1)) {
        try (final var httpClient = HttpClient.newHttpClient()) {
          try (final var voteService = createService(serviceExecutor, executor, httpClient)) {
            voteService.run();
          } catch (final InterruptedException ex) {
            // exit
          } catch (final Throwable ex) {
            logger.log(ERROR, "Unhandled service failure.", ex);
          }
        } finally {
          serviceExecutor.shutdownNow();
        }
      } finally {
        executor.shutdownNow();
      }
    }
  }

  private final ChainItemFormatter formatter;
  private final PublicKey servicePublicKey;
  private final long delayMillis;
  private final GlamAccountsCache glamAccountsCache;
  private final Path ballotFilePath;
  private final Path proposalsDirectory;
  private final PublicKey glamProgram;
  private final List<Filter> glamFilter;
  private final Map<PublicKey, StateAccount> delegatedGlams;
  private final RpcCaller rpcCaller;
  private final TransactionProcessor transactionProcessor;
  private final TxMonitorService txMonitorService;
  private final BigDecimal maxLamportPriorityFee;
  private final HttpClient webSocketHttpClient;
  private final WebSocketManager webSocketManager;
  private final VoteServiceWebServer webServer;
  private final long minLockedToVote;
  private final long stopVotingSecondsBeforeEnd;
  private int newVoteBatchSize;
  private int changeVoteBatchSize;

  private VoteService(final ChainItemFormatter formatter,
                      final SigningService signingService,
                      final PublicKey servicePublicKey,
                      final ExecutorService executor,
                      final long delayMillis,
                      final SolanaAccounts solanaAccounts,
                      final GlamAccounts glamAccounts,
                      final GlamAccountsCache glamAccountsCache,
                      final Path ballotFilePath,
                      final Path workDirectory,
                      final RpcCaller rpcCaller,
                      final LoadBalancer<SolanaRpcClient> sendClients,
                      final LoadBalancer<HeliusFeeProvider> feeProviders,
                      final EpochInfoService epochInfoService,
                      final RemoteResourceConfig webSocketConfig,
                      final TxMonitorConfig txMonitorConfig,
                      final BigDecimal maxLamportPriorityFee,
                      final LookupTableCache lookupTableCache,
                      final VoteServiceWebServer webServer,
                      final long minLockedToVote,
                      final Duration stopVotingBeforeEndDuration,
                      final int newVoteBatchSize,
                      final int changeVoteBatchSize) {
    this.formatter = formatter;
    this.servicePublicKey = servicePublicKey;
    this.delayMillis = delayMillis;
    this.proposalsDirectory = workDirectory.resolve(".proposals");
    this.rpcCaller = rpcCaller;
    this.webServer = webServer;
    this.minLockedToVote = minLockedToVote;
    this.stopVotingSecondsBeforeEnd = stopVotingBeforeEndDuration.toSeconds();
    this.newVoteBatchSize = newVoteBatchSize;
    this.ballotFilePath = ballotFilePath;
    this.glamAccountsCache = glamAccountsCache;
    this.delegatedGlams = new ConcurrentHashMap<>();
    this.glamProgram = glamAccounts.program();
    this.glamFilter = List.of(StateAccount.DISCRIMINATOR_FILTER);
    this.webSocketHttpClient = HttpClient.newHttpClient();
    this.webSocketManager = WebSocketManager.createManager(
        webSocketHttpClient,
        webSocketConfig.endpoint(),
        webSocketConfig.backoff(),
        webSocket -> webSocket.programSubscribe(glamProgram, glamFilter, this)
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
        feeProviders,
        rpcCaller.callWeights(),
        webSocketManager
    );
    this.txMonitorService = TxMonitorService.createService(
        formatter,
        rpcCaller,
        epochInfoService,
        webSocketManager,
        txMonitorConfig,
        transactionProcessor
    );
    this.maxLamportPriorityFee = maxLamportPriorityFee;
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

  BigDecimal maxLamportPriorityFee() {
    return maxLamportPriorityFee;
  }

  ChainItemFormatter chainItemFormatter() {
    return formatter;
  }


  @Override
  public void accept(final AccountInfo<byte[]> accountInfo) {
    try {
      final var glamAccount = StateAccount.read(accountInfo);
      if (isDelegatedWithPermission(glamAccount, servicePublicKey, VOTE_PERMISSION)) {
        if (hasIntegration(glamAccount, Integration.JupiterVote)) {
          delegatedGlams.put(glamAccount._address(), glamAccount);
        } else {
          logger.log(
              WARNING, String.format(
                  "Delegate GLAM %s does not have the %s integration enabled.",
                  accountInfo.pubKey(), Integration.JupiterVote
              )
          );
        }
      } else {
        logger.log(
            WARNING, String.format(
                "Delegate GLAM %s has not given the permission to %s.",
                accountInfo.pubKey(), VOTE_PERMISSION
            )
        );
      }
    } catch (final RuntimeException ex) {
      logger.log(ERROR, "Failed to parse GLAM account " + accountInfo.pubKey(), ex);
    }
  }

  private void fetchDelegatedGlamsWithPermission() {
    logger.log(INFO, "Fetching all glam accounts.");
    final var glamAccounts = rpcCaller.courteousGet(
        rpcClient -> rpcClient.getProgramAccounts(glamProgram, glamFilter),
        rpcCaller.callWeights().getProgramAccounts(),
        "rpcClient::getGlamAccounts"
    );

    logger.log(INFO, String.format("Retrieved %d glam accounts.", glamAccounts.size()));
    for (final var accountInfo : glamAccounts) {
      accept(accountInfo);
    }
    logger.log(
        INFO, String.format(
            "Filtered %d delegated glam accounts with permission to vote.",
            delegatedGlams.size()
        )
    );
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
      final var proposal = Proposal.read(accountInfo);
      proposalAccountStateMap.put(proposal._address(), proposal);
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
    try {
      logger.log(INFO, "Starting service with key " + servicePublicKey.toBase58());
      // Initial websocket connection.
      webSocketManager.webSocket();
      fetchDelegatedGlamsWithPermission();
      webServer.fetchBalances(this.delegatedGlams);

      final var proposalVotes = readBallot();
      final int numProposals = proposalVotes.size();

      if (numProposals == 0) {
        logger.log(
            WARNING, String.format(
                "No proposals configured in the ballot file %s.",
                ballotFilePath.toAbsolutePath()
            )
        );
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
        logger.log(INFO, "No pending or active proposals, truncating ballot file.");
        try {
          Files.writeString(ballotFilePath, "[]", TRUNCATE_EXISTING, WRITE);
        } catch (final IOException e) {
          throw new UncheckedIOException(e);
        }
      }

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
          logger.log(INFO, "No pending or active proposals.");
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
        webServer.fetchBalances(this.delegatedGlams);
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
    webSocketHttpClient.close();
  }
}
