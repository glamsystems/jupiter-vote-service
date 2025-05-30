package systems.glam.vote.jupiter;

import software.sava.anchor.programs.glam.GlamAccounts;
import software.sava.anchor.programs.glam.GlamJupiterVoteClient;
import software.sava.anchor.programs.glam.anchor.GlamProtocolPDAs;
import software.sava.anchor.programs.jupiter.JupiterAccounts;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public record GlamAccountsCache(SolanaAccounts solanaAccounts,
                                GlamAccounts glamAccounts,
                                JupiterAccounts jupiterAccounts,
                                PublicKey servicePublicKey,
                                Map<PublicKey, GlamJupiterVoteClient> escrowAccountsMap) implements Function<PublicKey, GlamJupiterVoteClient> {

  static GlamAccountsCache createCache(final SolanaAccounts solanaAccounts,
                                       final GlamAccounts glamAccounts,
                                       final JupiterAccounts jupiterAccounts,
                                       final PublicKey servicePublicKey) {
    return new GlamAccountsCache(
        solanaAccounts,
        glamAccounts,
        jupiterAccounts,
        servicePublicKey,
        HashMap.newHashMap(512)
    );
  }

  GlamJupiterVoteClient computeIfAbsent(final PublicKey glamAccount) {
    return escrowAccountsMap.computeIfAbsent(glamAccount, this);
  }

  GlamVoteAccounts createVoteAccount(final PublicKey glamAccount, final PublicKey proposalKey) {
    final var voteClient = computeIfAbsent(glamAccount);
    return new GlamVoteAccounts(
        voteClient,
        voteClient.deriveVoteKey(proposalKey)
    );
  }

  @Override
  public GlamJupiterVoteClient apply(final PublicKey glamAccount) {
    final var vaultAccount = GlamProtocolPDAs.glamVaultPDA(glamAccounts.program(), glamAccount).publicKey();
    return GlamJupiterVoteClient.createClient(
        solanaAccounts, jupiterAccounts, glamAccounts,
        glamAccount, vaultAccount,
        servicePublicKey
    );
  }
}
