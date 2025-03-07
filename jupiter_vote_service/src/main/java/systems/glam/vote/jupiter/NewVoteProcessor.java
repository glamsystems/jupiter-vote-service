package systems.glam.vote.jupiter;

import software.sava.anchor.programs.glam.GlamJupiterVoteClient;
import software.sava.anchor.programs.jupiter.governance.anchor.types.Proposal;
import software.sava.anchor.programs.jupiter.governance.anchor.types.Vote;
import software.sava.core.accounts.PublicKey;
import software.sava.core.tx.Instruction;

import java.util.Collection;
import java.util.List;

import static java.lang.System.Logger.Level.INFO;

final class NewVoteProcessor extends VoteProcessor {

  private static final System.Logger logger = System.getLogger(NewVoteProcessor.class.getName());

  NewVoteProcessor(final VoteService voteService,
                   final PublicKey proposalKey,
                   final Proposal proposal,
                   final int side,
                   final GlamJupiterVoteClient[] voteClients,
                   final RecordedProposalVotes recordedProposalVotes) {
    super(voteService, proposalKey, proposal, side, voteClients, recordedProposalVotes, 2, voteService.newVoteBatchSize());
  }

  @Override
  protected int appendInstructions(final GlamJupiterVoteClient voteClient,
                                   final PublicKey voteKey,
                                   final Instruction[] ixArray,
                                   final int index) {
    final var newVoteIx = voteClient.newVote(proposalKey, voteKey, voteService.servicePublicKey());
    ixArray[index] = newVoteIx;

    final var castVoteIx = voteClient.castVote(proposalKey, voteKey, side);
    ixArray[index + 1] = castVoteIx;

    return index + 2;
  }

  @Override
  protected boolean userVoted(final Vote voteAccount) {
    return voteAccount != null;
  }

  @Override
  protected int reduceBatchSize() {
    return voteService.reduceNewVoteBatchSize();
  }

  @Override
  protected boolean handledFailedIx(final int indexOffset,
                                    final long customErrorCode,
                                    final List<String> logs) {
    if ((indexOffset & 1) == 0) { // Even is new vote instructions.
      if (customErrorCode == 0) {
        for (final var log : logs) {
          if (log.startsWith("Allocate: account Address") && log.endsWith("already in use")) {
            final var key = "address: ";
            int from = log.indexOf(key);
            if (from > 0) {
              from += key.length();
              final var voteAccount = PublicKey.fromBase58Encoded(log.substring(from, log.indexOf(',', from + PublicKey.PUBLIC_KEY_LENGTH)));

              final var voteClient = getBatchVoteClient(indexOffset >> 1);
              final var voteKey = voteClient.deriveVoteKey(proposalKey);

              if (voteAccount.equals(voteKey)) {
                final var glamKey = voteClient.glamKey();
                recordedProposalVotes.persistUserVoteOverride(glamKey);
                logger.log(INFO, String.format("""
                            GLAM %s has overridden the vote for this proposal %s because Vote account, %s, already exists.
                            """,
                        glamKey, voteAccount, proposalKey
                    )
                );
                return true;
              }
            }
            return false;
          }
        }
      }
    }
    return false;
  }

  @Override
  protected void persistVotes(final Collection<PublicKey> glamKeys) {
    recordedProposalVotes.persistVoted(glamKeys, side);
    for (int i = resetBatchClientIndex, bs = 1; i < voteClientIndex; ++i, bs <<= 1) {
      if ((batchBitSet & bs) == bs) {
        recordedProposalVotes.trackVoted(voteClients[i].glamKey(), side);
      }
    }
  }
}
