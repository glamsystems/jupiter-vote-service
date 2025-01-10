package systems.glam.vote.jupiter;

import software.sava.kms.core.signing.SigningServiceConfig;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.services.core.config.Parser;
import software.sava.services.core.config.RemoteResourceConfig;
import software.sava.services.core.config.ScheduleConfig;
import software.sava.services.core.config.ServiceConfigUtil;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.remote.load_balance.LoadBalancer;
import software.sava.services.core.remote.load_balance.LoadBalancerConfig;
import software.sava.services.solana.alt.TableCacheConfig;
import software.sava.services.solana.config.ChainItemFormatter;
import software.sava.services.solana.config.HeliusConfig;
import software.sava.services.solana.config.JupiterConfig;
import software.sava.services.solana.epoch.EpochServiceConfig;
import software.sava.services.solana.remote.call.CallWeights;
import software.sava.services.solana.transactions.TxMonitorConfig;
import software.sava.solana.web2.helius.client.http.HeliusClient;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static java.util.Objects.requireNonNullElse;
import static software.sava.services.core.config.ServiceConfigUtil.parseDuration;
import static software.sava.services.solana.load_balance.LoadBalanceUtil.createRPCLoadBalancer;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record VoteServiceConfig(ChainItemFormatter chainItemFormatter,
                                SigningServiceConfig signingServiceConfig,
                                LoadBalancer<SolanaRpcClient> rpcClients,
                                LoadBalancer<SolanaRpcClient> sendClients,
                                CallWeights callWeights,
                                LoadBalancer<HeliusClient> heliusClient,
                                RemoteResourceConfig websocketConfig,
                                EpochServiceConfig epochServiceConfig,
                                TxMonitorConfig txMonitorConfig,
                                TableCacheConfig tableCacheConfig,
                                JupiterConfig jupiterConfig,
                                Path workDir,
                                Path ballotFilePath,
                                ScheduleConfig scheduleConfig,
                                BigDecimal minLockedToVote,
                                Duration stopVotingBeforeEndDuration,
                                Duration confirmVoteTxAfterDuration,
                                int newVoteBatchSize,
                                int changeVoteBatchSize) {

  public static final Backoff DEFAULT_NETWORK_BACKOFF = Backoff.fibonacci(1, 21);

  public static VoteServiceConfig loadConfig(final HttpClient rpcHttpClient) {
    return ServiceConfigUtil.loadConfig(VoteServiceConfig.class, new Builder(rpcHttpClient));
  }

  private static final class Builder implements Parser<VoteServiceConfig> {

    private final HttpClient httpClient;

    private ChainItemFormatter chainItemFormatter;
    private SigningServiceConfig signingServiceConfig;
    private LoadBalancer<SolanaRpcClient> rpcClients;
    private LoadBalancer<SolanaRpcClient> sendClients;
    private CallWeights callWeights;
    private HeliusConfig heliusConfig;
    private RemoteResourceConfig websocketConfig;
    private EpochServiceConfig epochServiceConfig;
    private TxMonitorConfig txMonitorConfig;
    private TableCacheConfig tableCacheConfig;
    private JupiterConfig jupiterConfig;
    private Path workDir;
    private Path ballotFilePath;
    private ScheduleConfig scheduleConfig;
    private BigDecimal minLockedToVote;
    private Duration stopVotingBeforeEndDuration;
    private Duration confirmVoteTxAfterDuration;
    private int newVoteBatchSize = 5;
    private int changeVoteBatchSize = 10;

    private Builder(final HttpClient httpClient) {
      this.httpClient = httpClient;
    }

    @Override
    public VoteServiceConfig createConfig() {
      if (ballotFilePath == null || Files.notExists(ballotFilePath)) {
        throw new IllegalStateException("Must configure a votes file which contains votes for active or upcoming proposals.");
      }

      final var workDir = this.workDir == null
          ? Path.of(".vote").toAbsolutePath()
          : this.workDir;
      if (Files.notExists(workDir)) {
        try {
          Files.createDirectories(workDir);
        } catch (final IOException e) {
          throw new UncheckedIOException(e);
        }
      }

      final var heliusClient = heliusConfig.createHeliusClient(httpClient);

      return new VoteServiceConfig(
          chainItemFormatter == null ? ChainItemFormatter.createDefault() : chainItemFormatter,
          signingServiceConfig,
          rpcClients,
          requireNonNullElse(sendClients, rpcClients),
          callWeights,
          heliusClient,
          websocketConfig,
          epochServiceConfig,
          txMonitorConfig,
          tableCacheConfig == null ? TableCacheConfig.createDefault() : tableCacheConfig,
          jupiterConfig,
          workDir,
          ballotFilePath,
          scheduleConfig,
          requireNonNullElse(minLockedToVote, BigDecimal.ZERO),
          stopVotingBeforeEndDuration == null
              ? Duration.ofSeconds(60)
              : stopVotingBeforeEndDuration,
          confirmVoteTxAfterDuration == null
              ? Duration.ofSeconds(42)
              : confirmVoteTxAfterDuration,
          Math.max(1, Math.min(Long.SIZE, newVoteBatchSize)),
          Math.max(1, Math.min(Long.SIZE, changeVoteBatchSize))
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("signingService", buf, offset, len)) {
        signingServiceConfig = SigningServiceConfig.parseConfig(ji);
      } else if (fieldEquals("formatter", buf, offset, len)) {
        chainItemFormatter = ChainItemFormatter.parseFormatter(ji);
      } else if (fieldEquals("rpcCallWeights", buf, offset, len)) {
        callWeights = CallWeights.parse(ji);
      } else if (fieldEquals("rpc", buf, offset, len)) {
        final var loadBalancerConfig = LoadBalancerConfig.parse(ji);
        rpcClients = createRPCLoadBalancer(loadBalancerConfig, httpClient);
      } else if (fieldEquals("sendRPC", buf, offset, len)) {
        final var loadBalancerConfig = LoadBalancerConfig.parse(ji);
        sendClients = createRPCLoadBalancer(loadBalancerConfig, httpClient);
      } else if (fieldEquals("helius", buf, offset, len)) {
        heliusConfig = HeliusConfig.parseConfig(ji);
      } else if (fieldEquals("jupiter", buf, offset, len)) {
        jupiterConfig = JupiterConfig.parseConfig(ji);
      } else if (fieldEquals("websocket", buf, offset, len)) {
        websocketConfig = RemoteResourceConfig.parseConfig(ji, null, DEFAULT_NETWORK_BACKOFF);
      } else if (fieldEquals("epochService", buf, offset, len)) {
        epochServiceConfig = EpochServiceConfig.parseConfig(ji);
      } else if (fieldEquals("txMonitor", buf, offset, len)) {
        txMonitorConfig = TxMonitorConfig.parseConfig(ji);
      } else if (fieldEquals("tableCache", buf, offset, len)) {
        tableCacheConfig = TableCacheConfig.parse(ji);
      } else if (fieldEquals("workDir", buf, offset, len)) {
        workDir = Path.of(ji.readString()).toAbsolutePath();
      } else if (fieldEquals("ballotFilePath", buf, offset, len)) {
        ballotFilePath = Path.of(ji.readString()).toAbsolutePath();
      } else if (fieldEquals("schedule", buf, offset, len)) {
        scheduleConfig = ScheduleConfig.parseConfig(ji);
      } else if (fieldEquals("minLockedToVote", buf, offset, len)) {
        minLockedToVote = ji.readBigDecimalDropZeroes();
      } else if (fieldEquals("stopVotingBeforeEndDuration", buf, offset, len)) {
        stopVotingBeforeEndDuration = parseDuration(ji);
      } else if (fieldEquals("confirmVoteTxAfterDuration", buf, offset, len)) {
        confirmVoteTxAfterDuration = parseDuration(ji);
      } else if (fieldEquals("newVoteBatchSize", buf, offset, len)) {
        newVoteBatchSize = ji.readInt();
      } else if (fieldEquals("changeVoteBatchSize", buf, offset, len)) {
        changeVoteBatchSize = ji.readInt();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
