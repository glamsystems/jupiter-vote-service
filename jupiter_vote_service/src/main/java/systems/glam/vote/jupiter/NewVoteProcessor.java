package systems.glam.vote.jupiter;

import software.sava.anchor.programs.glam.GlamJupiterVoteClient;
import software.sava.anchor.programs.jupiter.governance.anchor.types.Proposal;
import software.sava.anchor.programs.jupiter.governance.anchor.types.Vote;
import software.sava.core.accounts.PublicKey;
import software.sava.core.tx.Instruction;

import java.time.ZonedDateTime;

import static java.time.ZoneOffset.UTC;

final class NewVoteProcessor extends VoteProcessor {

  private final long voteAccountMintRent;

  NewVoteProcessor(final VoteService voteService,
                   final PublicKey proposalKey,
                   final Proposal proposal,
                   final int side,
                   final GlamJupiterVoteClient[] voteClients,
                   final RecordedProposalVotes recordedProposalVotes,
                   final long voteAccountMintRent) {
    super(voteService, proposalKey, proposal, side, voteClients, recordedProposalVotes, 3, voteService.newVoteBatchSize());
    this.voteAccountMintRent = voteAccountMintRent;
  }

  @Override
  protected int appendInstructions(final GlamJupiterVoteClient voteClient,
                                   final PublicKey voteKey,
                                   final Instruction[] ixArray,
                                   final int index) {
    final var seed = ZonedDateTime.now(UTC).toString();
    final var uninitializedVoteAccount = voteClient.createOffCurveGovAccountWithSeed(seed);
    final var createVoteAccountIx = voteClient.createVoteAccountWithSeedIx(uninitializedVoteAccount, voteAccountMintRent);
    ixArray[index] = createVoteAccountIx;

    final var newVoteIx = voteClient.newVote(proposalKey, voteKey, voteService.servicePublicKey());
    ixArray[index + 1] = newVoteIx;

    final var castVoteIx = voteClient.castVote(proposalKey, voteKey, side);
    ixArray[index + 2] = castVoteIx;

    return index + 3;
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
  protected void persistVotes(final byte[] glamKeys) {
    recordedProposalVotes.persistVoted(glamKeys, side);
    for (int i = resetBatchClientIndex, bs = 1; i < voteClientIndex; ++i, bs <<= 1) {
      if ((batchBitSet & bs) == bs) {
        recordedProposalVotes.trackVoted(voteClients[i].glamKey(), side);
      }
    }
  }
}
