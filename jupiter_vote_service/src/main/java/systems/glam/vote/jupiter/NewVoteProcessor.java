package systems.glam.vote.jupiter;

import software.sava.anchor.programs.glam.GlamJupiterVoteClient;
import software.sava.anchor.programs.jupiter.governance.anchor.types.Proposal;
import software.sava.anchor.programs.jupiter.governance.anchor.types.Vote;
import software.sava.core.accounts.PublicKey;
import software.sava.core.tx.Instruction;

final class NewVoteProcessor extends VoteProcessor {

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
    final var castVoteIx = voteClient.castVote(proposalKey, voteKey, side);
    ixArray[index] = newVoteIx;
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
  protected void persistVotes(final byte[] glamKeys) {
    recordedProposalVotes.persistVoted(glamKeys, side);
    for (int i = resetBatchClientIndex, bs = 1; i < voteClientIndex; ++i, bs <<= 1) {
      if ((batchBitSet & bs) == bs) {
        recordedProposalVotes.trackVoted(voteClients[i].glamKey(), side);
      }
    }
  }
}
