package systems.glam.vote.jupiter;

import software.sava.anchor.programs.glam.GlamJupiterVoteClient;
import software.sava.anchor.programs.glam.anchor.types.StateAccount;
import software.sava.anchor.programs.jupiter.governance.anchor.types.Vote;
import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.Base58;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.services.solana.remote.call.RpcCaller;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.stream.Collectors;

import static java.lang.System.Logger.Level.INFO;

/// In-memory state is only used on startup to handle recovery if the process was killed before confirming a transaction.
record Voting(int side,
              Set<PublicKey> keys,
              String txSig,
              RandomAccessFile file) {

  private static final System.Logger logger = System.getLogger(Voting.class.getName());

  static Voting readVotingFile(final RandomAccessFile file) throws IOException {
    final byte[] buffer = RecordedProposalVotes.readFully(file);
    if (buffer.length == 0) {
      return new Voting(-1, null, null, file);
    }
    final int side = buffer[0] & 0xFF;
    final int numKeys = buffer[1] & 0xFF;

    final int expectedLength = 2 + (numKeys * PublicKey.PUBLIC_KEY_LENGTH) + Transaction.SIGNATURE_LENGTH;
    if (buffer.length < expectedLength) {
      file.setLength(0);
      return new Voting(-1, null, null, file);
    }

    if (buffer.length != expectedLength) {
      throw new IllegalStateException(String.format("""
              Unexpected voting file length %d for %d keys.
              """,
          buffer.length, numKeys
      ));
    }

    final var keys = HashSet.<PublicKey>newHashSet(numKeys);
    int i = 2;
    for (int k = 0; k < numKeys; ++k, i += PublicKey.PUBLIC_KEY_LENGTH) {
      final var key = PublicKey.readPubKey(buffer, i);
      keys.add(key);
    }

    final var txSig = Base58.encode(buffer, i, i + Transaction.SIGNATURE_LENGTH);
    return new Voting(side, keys, txSig, file);
  }

  void truncate() {
    try {
      file.setLength(0);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  void write(final byte[] data) {
    try {
      file.write(data);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  void recoverState(final PublicKey proposalKey,
                    final RecordedProposalVotes recordedProposalVotes,
                    final Map<PublicKey, StateAccount> delegatedGlams,
                    final GlamAccountsCache glamAccountsCache,
                    final RpcCaller rpcCaller) {
    if (txSig != null) {
      final var votingGlamClients = keys.stream()
          .map(delegatedGlams::get)
          .filter(Objects::nonNull)
          .map(glam -> glamAccountsCache.computeIfAbsent(glam._address()))
          .toArray(GlamJupiterVoteClient[]::new);
      if (votingGlamClients.length > 0) {
        final var txFuture = rpcCaller.courteousCall(
            rpcClient -> rpcClient.getTransaction(Commitment.CONFIRMED, txSig),
            "rpcClient::getTransaction"
        );
        final var voteKeys = Arrays.stream(votingGlamClients)
            .map(voteClient -> voteClient.deriveVoteKey(proposalKey))
            .toList();
        final var voteAccounts = rpcCaller.courteousGet(
            rpcClient -> rpcClient.getMultipleAccounts(voteKeys, Vote.FACTORY),
            "rpcClient::getMultipleAccounts"
        ).stream().collect(Collectors.toUnmodifiableMap(AccountInfo::pubKey, AccountInfo::data));

        final var tx = txFuture.join();
        final var txLanded = tx != null && tx.meta().error() == null;
        for (final var voteClient : votingGlamClients) {
          final var voteAccountKey = voteClient.deriveVoteKey(proposalKey);
          final var voteAccount = voteAccounts.get(voteAccountKey);
          if (voteAccount != null) {
            final var glamKey = voteClient.glamKey();
            final int onChainSide = voteAccount.side();
            if (txLanded && onChainSide == side) {
              recordedProposalVotes.persistVoted(glamKey, side);
              // If the service was changing this vote it will be cleared from the previous vote file by the vote service.
            } else if (!recordedProposalVotes.serviceVotedFor(glamKey, onChainSide)) {
              recordedProposalVotes.persistUserVoteOverride(glamKey);
              logger.log(INFO, String.format("""
                          Inferring that GLAM %s has overridden the vote for this proposal %s because the Vote account retrieved after recovery does not match what the service was voting for despite the vote transaction landing.
                          """,
                      glamKey, proposalKey
                  )
              );
            }
          }
        }
      }
      truncate();
      keys.clear();
    }
  }
}
