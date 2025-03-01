package systems.glam.vote.jupiter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

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
  private final Path statsFilePath;

  private volatile byte[] statsResponse;

  private VoteServiceWebServer(final HttpServer server,
                               final GlamAccountsCache glamAccountsCache,
                               final RpcCaller rpcCaller,
                               final TokenContext tokenContext,
                               final Path statsFilePath,
                               final String statsResponse) {
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
                                           final TokenContext tokenContext) {
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
          statsFilePath,
          statsResponse
      );
      httpServer.createContext(apiPath + (apiPath.endsWith("/") ? "stats" : "/stats"), webServer);
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

  private BigDecimal sumBatch(final Map<PublicKey, PublicKey> escrowKeyMap, final long[] staked, final int from) {
    final int numKeys = escrowKeyMap.size();
    if (numKeys == 0) {
      return BigDecimal.ZERO;
    }
    final var escrowKeyList = List.copyOf(escrowKeyMap.keySet());
    logger.log(INFO, String.format("""
                Fetching %d escrow accounts for the following GLAMs:
                  * %s""",
            numKeys,
            escrowKeyMap.values().stream().map(PublicKey::toBase58).collect(Collectors.joining("\n  * "))
        )
    );
    final var escrowAccountInfos = rpcCaller.courteousGet(
        rpcClient -> rpcClient.getMultipleAccounts(escrowKeyList, Escrow.FACTORY),
        "rpcClient::getEscrowAccounts"
    );

    int i = from;
    var sum = BigDecimal.ZERO;
    for (final var accountInfo : escrowAccountInfos) {
      escrowKeyMap.remove(accountInfo.pubKey());
      final var escrowAccount = accountInfo.data();
      final long amount = escrowAccount.amount();
      staked[i++] = amount;
      sum = sum.add(tokenContext.toDecimal(amount));
    }

    final int numMissingEscrowAccounts = escrowKeyMap.size();
    if (numMissingEscrowAccounts > 0) {
      logger.log(WARNING, String.format("""
                  Failed to find %d escrow accounts:
                    * %s
                  """,
              numMissingEscrowAccounts,
              escrowKeyMap.values().stream().map(PublicKey::toBase58).collect(Collectors.joining("\n  * "))
          )
      );
    }

    return sum;
  }

  void fetchBalances(final Map<PublicKey, StateAccount> delegatedGlams) {
    final var escrowKeyMap = HashMap.<PublicKey, PublicKey>newHashMap(delegatedGlams.size());
    for (final var glamKey : delegatedGlams.keySet()) {
      final var voteClient = glamAccountsCache.computeIfAbsent(glamKey);
      escrowKeyMap.put(voteClient.escrowKey(), glamKey);
    }

    final int numConstituents = escrowKeyMap.size();

    final long[] staked = new long[numConstituents];

    BigDecimal sum;
    if (numConstituents < MAX_MULTIPLE_ACCOUNTS) {
      sum = sumBatch(escrowKeyMap, staked, 0);
    } else {
      sum = BigDecimal.ZERO;
      final var escrowKeyMapBatch = HashMap.<PublicKey, PublicKey>newHashMap(MAX_MULTIPLE_ACCOUNTS);
      final var iterator = escrowKeyMap.entrySet().iterator();

      for (int from = 0, to; from < numConstituents; from = to) {
        to = Math.min(numConstituents, from + MAX_MULTIPLE_ACCOUNTS);
        for (int i = from; i < to; i++) {
          final var next = iterator.next();
          escrowKeyMap.put(next.getKey(), next.getValue());
        }
        sum = sum.add(sumBatch(escrowKeyMapBatch, staked, from));
        escrowKeyMapBatch.clear();
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
