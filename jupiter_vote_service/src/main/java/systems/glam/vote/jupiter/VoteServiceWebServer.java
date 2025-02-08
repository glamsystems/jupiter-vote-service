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
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
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
  private final Path summaryFilePath;

  private volatile byte[] summaryResponse;

  private VoteServiceWebServer(final HttpServer server,
                               final GlamAccountsCache glamAccountsCache,
                               final RpcCaller rpcCaller,
                               final TokenContext tokenContext,
                               final Path summaryFilePath,
                               final String summaryResponse) {
    this.server = server;
    this.glamAccountsCache = glamAccountsCache;
    this.rpcCaller = rpcCaller;
    this.tokenContext = tokenContext;
    this.summaryFilePath = summaryFilePath;
    this.summaryResponse = summaryResponse.getBytes();
  }

  static VoteServiceWebServer createServer(final ExecutorService executorService,
                                           final int port,
                                           final String apiPath,
                                           final Path workDir,
                                           final GlamAccountsCache glamAccountsCache,
                                           final RpcCaller rpcCaller,
                                           final TokenContext tokenContext) {
    final var summaryFilePath = workDir.resolve(".summary.json");
    final String summaryResponse;
    if (Files.exists(summaryFilePath)) {
      try {
        summaryResponse = Files.readString(summaryFilePath);
      } catch (final IOException e) {
        logger.log(ERROR, "Failed read api summary cache file " + summaryFilePath, e);
        throw new UncheckedIOException(e);
      }
    } else {
      summaryResponse = """
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
          summaryFilePath,
          summaryResponse
      );
      httpServer.createContext(apiPath + (apiPath.endsWith("/") ? "summary" : "/summary"), webServer);
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

  private BigDecimal sumBatch(final List<PublicKey> escrowKeyList, final long[] staked, final int from) {
    final int numKeys = escrowKeyList.size();
    if (numKeys == 0) {
      return BigDecimal.ZERO;
    }
    logger.log(INFO, String.format("Fetching %d escrow accounts.", numKeys));
    final var escrowAccountInfos = rpcCaller.courteousGet(
        rpcClient -> rpcClient.getMultipleAccounts(escrowKeyList, Escrow.FACTORY),
        "rpcClient::getEscrowAccounts"
    );

    int i = from;
    var sum = BigDecimal.ZERO;
    for (final var accountInfo : escrowAccountInfos) {
      final var escrowAccount = accountInfo.data();
      final long amount = escrowAccount.amount();
      staked[i++] = amount;
      sum = sum.add(tokenContext.toDecimal(amount));
    }
    return sum;
  }

  void fetchBalances(final Map<PublicKey, StateAccount> delegatedGlams) {
    final var escrowKeyList = delegatedGlams.keySet().stream()
        .map(glamAccountsCache::computeIfAbsent)
        .map(GlamJupiterVoteClient::escrowKey)
        .toList();
    final int numConstituents = escrowKeyList.size();

    final long[] staked = new long[numConstituents];

    BigDecimal sum;
    if (numConstituents < MAX_MULTIPLE_ACCOUNTS) {
      sum = sumBatch(escrowKeyList, staked, 0);
    } else {
      sum = BigDecimal.ZERO;
      for (int from = 0, to; from < numConstituents; from = to) {
        to = Math.min(numConstituents, from + MAX_MULTIPLE_ACCOUNTS);
        final var escrowKeyListView = escrowKeyList.subList(from, to);
        sum = sum.add(sumBatch(escrowKeyListView, staked, from));
      }
    }

    final var timestamp = ZonedDateTime.now(UTC).truncatedTo(ChronoUnit.SECONDS);

    final String medianString;
    if (numConstituents == 0) {
      medianString = "0";
    } else {
      final int middle = staked.length >> 1;
      final long median = (staked.length & 1) == 1
          ? staked[middle]
          : (staked[middle] + staked[middle - 1]) >> 1;
      medianString = tokenContext.toDecimal(median).stripTrailingZeros().toPlainString();
    }
    final var responseJson = String.format("""
            {
              "staked": "%s",
              "median": "%s",
              "numConstituents": %d,
              "timestamp": "%s"
            }""",
        sum.stripTrailingZeros().toPlainString(),
        medianString,
        numConstituents,
        timestamp
    );
    summaryResponse = responseJson.getBytes();

    try {
      Files.writeString(
          summaryFilePath,
          responseJson,
          CREATE, TRUNCATE_EXISTING, WRITE
      );
    } catch (final IOException e) {
      logger.log(WARNING, String.format("Failed to write API summary to %s.", summaryFilePath), e);
    }
    logger.log(INFO, responseJson);
  }

  @Override
  public void handle(final HttpExchange exchange) throws IOException {
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    final var responseBytes = summaryResponse;
    exchange.sendResponseHeaders(200, responseBytes.length);
    try (final var os = exchange.getResponseBody()) {
      os.write(responseBytes);
    }
  }

  @Override
  public void close() {
    server.stop(1);
  }
}
