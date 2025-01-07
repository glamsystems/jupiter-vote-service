package systems.glam.vote.jupiter;

import software.sava.anchor.programs.jupiter.voter.JupiterVoteClient;
import software.sava.core.accounts.PublicKey;

record GlamVoteAccounts(JupiterVoteClient voteClient, PublicKey voteAccount) {

}
