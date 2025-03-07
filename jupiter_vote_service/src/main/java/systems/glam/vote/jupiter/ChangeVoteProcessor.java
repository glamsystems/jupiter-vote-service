package systems.glam.vote.jupiter;

import software.sava.anchor.programs.glam.GlamJupiterVoteClient;
import software.sava.anchor.programs.glam.anchor.GlamError;
import software.sava.anchor.programs.jupiter.governance.anchor.types.Proposal;
import software.sava.anchor.programs.jupiter.governance.anchor.types.Vote;
import software.sava.core.accounts.PublicKey;
import software.sava.core.tx.Instruction;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.List;

import static java.lang.System.Logger.Level.INFO;

final class ChangeVoteProcessor extends VoteProcessor {

  private static final System.Logger logger = System.getLogger(ChangeVoteProcessor.class.getName());

  private final int previousSide;
  private final RecordedOptionVotes previousOptionVotes;
  private final RandomAccessFile previousVotesFile;

  ChangeVoteProcessor(final VoteService voteService,
                      final PublicKey proposalKey,
                      final Proposal proposal,
                      final int side,
                      final GlamJupiterVoteClient[] voteClients,
                      final RecordedProposalVotes recordedProposalVotes,
                      final RecordedOptionVotes previousOptionVotes) {
    super(
        voteService,
        proposalKey,
        proposal,
        side,
        voteClients,
        recordedProposalVotes,
        1,
        voteService.changeVoteBatchSize()
    );
    this.previousSide = previousOptionVotes.side();
    this.previousOptionVotes = previousOptionVotes;
    this.previousVotesFile = previousOptionVotes.file();

    // Ensure file matches the same set of glams we are processing,
    // and it is in reverse order so that file can simply be truncated to persist progress.
    final var glamKeys = new byte[voteClients.length * PublicKey.PUBLIC_KEY_LENGTH];
    for (int i = voteClients.length - 1, offset = 0; i >= 0; --i) {
      final var voteClient = voteClients[i];
      voteClient.glamKey().write(glamKeys, offset);
      offset += PublicKey.PUBLIC_KEY_LENGTH;
    }
    try {
      previousVotesFile.seek(0);
      previousVotesFile.write(glamKeys);
      previousVotesFile.setLength(glamKeys.length);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  protected boolean userVoted(final Vote voteAccount) {
    return voteAccount.side() != previousSide;
  }

  @Override
  protected int reduceBatchSize() {
    return voteService.reduceChangeVoteBatchSize();
  }

  @Override
  protected boolean handledFailedIx(final int indexOffset, final long customErrorCode, final List<String> logs) {
    if (customErrorCode > 0 && customErrorCode < Integer.MAX_VALUE) {
      try {
        final var glamError = GlamError.getInstance((int) customErrorCode);
        if (glamError instanceof GlamError.InvalidVoteSide) {
          final var voteClient = getBatchVoteClient(indexOffset);
          final var glamKey = voteClient.glamKey();
          recordedProposalVotes.persistUserVoteOverride(glamKey);
          logger.log(INFO, String.format("""
                      GLAM %s has overridden the vote for this proposal %s because the witnessed vote side does not match expected.
                      """,
                  glamKey, proposalKey
              )
          );
          return true;
        }
      } catch (final RuntimeException ignored) {
        return false;
      }
    }
    return false;
  }

  @Override
  protected int appendInstructions(final GlamJupiterVoteClient voteClient,
                                   final PublicKey voteKey,
                                   final Instruction[] ixArray,
                                   final int index) {
    ixArray[index] = voteClient.castVote(proposalKey, voteKey, side, previousSide);
    return index + 1;
  }

  @Override
  protected void persistVotes(final Collection<PublicKey> glamKeys) {
    // At this point the glam has been voted for by the service or themselves so clear previous service vote regardless.
    // If they voted themselves it has been recorded in the override file.
    // If the service has voted it still is recorded in the voting file in case there is a server failure in-between
    // removing the vote and recording the new service vote.
    final long numKeys = voteClientIndex - resetBatchClientIndex;
    try {
      final long length = previousVotesFile.length();
      previousVotesFile.setLength(length - (numKeys * PublicKey.PUBLIC_KEY_LENGTH));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    recordedProposalVotes.persistVoted(glamKeys, side);
    for (int i = resetBatchClientIndex, bs = 1; i < voteClientIndex; ++i, bs <<= 1) {
      final var glamKey = voteClients[i].glamKey();
      if ((batchBitSet & bs) == bs) {
        recordedProposalVotes.trackVoted(glamKey, side);
      }
      previousOptionVotes.unTrackVote(glamKey);
    }
  }
}
