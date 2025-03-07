package systems.glam.vote.jupiter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import software.sava.anchor.programs.glam.GlamJupiterVoteClient;
import software.sava.anchor.programs.glam.anchor.types.StateAccount;
import software.sava.anchor.programs.jupiter.voter.anchor.types.Escrow;
import software.sava.core.accounts.PublicKey;
import software.sava.services.solana.remote.call.RpcCaller;
import software.sava.solana.web2.jupiter.client.http.response.TokenContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static java.lang.System.Logger.Level.*;
import static java.nio.file.StandardOpenOption.*;
import static java.time.ZoneOffset.UTC;
import static software.sava.rpc.json.http.client.SolanaRpcClient.MAX_MULTIPLE_ACCOUNTS;

final class VoteServiceWebServer implements HttpHandler, AutoCloseable {

  private static final System.Logger logger = System.getLogger(VoteServiceWebServer.class.getName());

  private final HttpServer server;
  private final GlamAccountsCache glamAccountsCache;
  private final RpcCaller rpcCaller;
  private final TokenContext tokenContext;
  private final double unstakeDurationSeconds;
  private final Path statsFilePath;

  private volatile byte[] statsResponse;

  private VoteServiceWebServer(final HttpServer server,
                               final GlamAccountsCache glamAccountsCache,
                               final RpcCaller rpcCaller,
                               final TokenContext tokenContext,
                               final double unstakeDurationSeconds,
                               final Path statsFilePath,
                               final String statsResponse) {
    this.unstakeDurationSeconds = unstakeDurationSeconds;
    this.server = server;
    this.glamAccountsCache = glamAccountsCache;
    this.rpcCaller = rpcCaller;
    this.tokenContext = tokenContext;
    this.statsFilePath = statsFilePath;
    this.statsResponse = statsResponse.getBytes();
  }

  static VoteServiceWebServer createServer(final ExecutorService executorService,
                                           final int port,
                                           final String apiPath,
                                           final Path workDir,
                                           final GlamAccountsCache glamAccountsCache,
                                           final RpcCaller rpcCaller,
                                           final TokenContext tokenContext,
                                           final double unstakeDurationSeconds) {
    final var statsFilePath = workDir.resolve(".stats.json");
    final String statsResponse;
    if (Files.exists(statsFilePath)) {
      try {
        statsResponse = Files.readString(statsFilePath);
      } catch (final IOException e) {
        logger.log(ERROR, "Failed read api stats cache file " + statsFilePath, e);
        throw new UncheckedIOException(e);
      }
    } else {
      statsResponse = """
          {
            "staked": null
          }""";
    }

    try {
      final var httpServer = HttpServer.create(new InetSocketAddress(port), 0);
      httpServer.setExecutor(executorService);

      final var webServer = new VoteServiceWebServer(
          httpServer,
          glamAccountsCache,
          rpcCaller,
          tokenContext,
          unstakeDurationSeconds,
          statsFilePath,
          statsResponse
      );
      final var path = apiPath + (apiPath.endsWith("/") ? "stats" : "/stats");
      httpServer.createContext(path, webServer);
      logger.log(INFO, String.format("""
              Registered stats handler at %s.""", path
          )
      );
      return webServer;
    } catch (final IOException e) {
      logger.log(ERROR, "Failed start api web server on port " + port, e);
      throw new UncheckedIOException(e);
    }
  }

  void start() {
    server.start();
    logger.log(INFO, String.format("API web server listening at %s.", server.getAddress()));
  }

  private void sumBatch(final List<PublicKey> escrowKeyList,
                        final long[] staked,
                        final long[] votePower,
                        final int from) {
    final int numKeys = escrowKeyList.size();
    if (numKeys == 0) {
      return;
    }
    logger.log(INFO, String.format("Fetching %d escrow accounts.", numKeys));
    final var escrowAccountInfos = rpcCaller.courteousGet(
        rpcClient -> rpcClient.getMultipleAccounts(escrowKeyList, Escrow.FACTORY),
        "rpcClient::getEscrowAccounts"
    );

    int i = from;
    for (final var accountInfo : escrowAccountInfos) {
      final var escrowAccount = accountInfo.data();
      final long amount = escrowAccount.amount();
      staked[i] = amount;
      votePower[i] = VoteService.votingPower(escrowAccount, unstakeDurationSeconds);
      ++i;
    }
  }

  void fetchBalances(final Map<PublicKey, StateAccount> delegatedGlams) {
    final var escrowKeyList = delegatedGlams.keySet().stream()
        .map(glamAccountsCache::computeIfAbsent)
        .map(GlamJupiterVoteClient::escrowKey)
        .toList();
    final int numConstituents = escrowKeyList.size();

    final long[] staked = new long[numConstituents];
    final long[] votePower = new long[numConstituents];

    if (numConstituents < MAX_MULTIPLE_ACCOUNTS) {
      sumBatch(escrowKeyList, staked, votePower, 0);
    } else {
      for (int from = 0, to; from < numConstituents; from = to) {
        to = Math.min(numConstituents, from + MAX_MULTIPLE_ACCOUNTS);
        final var escrowKeyListView = escrowKeyList.subList(from, to);
        sumBatch(escrowKeyListView, staked, votePower, from);
      }
    }

    final var timestamp = ZonedDateTime.now(UTC).truncatedTo(ChronoUnit.SECONDS);

    final String sumString;
    final String votePowerString;
    final String medianString;
    final String meanString;
    if (numConstituents == 0) {
      sumString = "0";
      votePowerString = "0";
      medianString = "0";
      meanString = "0";
    } else {
      Arrays.sort(staked);
      final int middle = staked.length >> 1;
      final long median = (staked.length & 1) == 1
          ? staked[middle]
          : (staked[middle] + staked[middle - 1]) >> 1;
      medianString = tokenContext.toDecimal(median).stripTrailingZeros().toPlainString();
      votePowerString = tokenContext.toDecimal(Arrays.stream(votePower).sum()).stripTrailingZeros().toPlainString();

      final var summaryStats = Arrays.stream(staked).summaryStatistics();
      sumString = tokenContext.toDecimal(summaryStats.getSum()).stripTrailingZeros().toPlainString();
      meanString = tokenContext.toDecimal((long) summaryStats.getAverage()).stripTrailingZeros().toPlainString();
    }
    final var responseJson = String.format("""
            {
              "staked": "%s",
              "votePower": "%s",
              "median": "%s",
              "mean": "%s",
              "numConstituents": %d,
              "timestamp": "%s"
            }""",
        sumString,
        votePowerString,
        medianString,
        meanString,
        numConstituents,
        timestamp
    );
    statsResponse = responseJson.getBytes();

    try {
      Files.writeString(
          statsFilePath,
          responseJson,
          CREATE, TRUNCATE_EXISTING, WRITE
      );
    } catch (final IOException e) {
      logger.log(WARNING, String.format("Failed to write API stats to %s.", statsFilePath), e);
    }
    logger.log(INFO, responseJson);
  }

  @Override
  public void handle(final HttpExchange exchange) {
    try (final var os = exchange.getResponseBody()) {
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      final var responseBytes = statsResponse;
      exchange.sendResponseHeaders(200, responseBytes.length);
      os.write(responseBytes);
    } catch (final RuntimeException | IOException e) {
      logger.log(ERROR, "Failed to write response.", e);
    }
  }

  @Override
  public void close() {
    server.stop(1);
  }
}
