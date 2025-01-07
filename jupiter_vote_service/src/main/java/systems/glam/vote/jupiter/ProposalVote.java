package systems.glam.vote.jupiter;

import software.sava.anchor.programs.jupiter.governance.anchor.GovernConstants;
import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

record ProposalVote(PublicKey proposal, int side) {

  String toJson() {
    return String.format("""
            {
              "proposal": "%s",
              "side": %d
            }""",
        proposal.toBase58(), side);
  }

  static final class Parser implements FieldBufferPredicate {

    private PublicKey proposal;
    private int side;

    ProposalVote create() {
      if (side < 0 || side >= GovernConstants.MAX_OPTION) {
        throw new IllegalStateException(String.format(
            "'side' field must be between [0, %d), not %d.",
            side, GovernConstants.MAX_OPTION
        ));
      }
      if (proposal == null) {
        throw new IllegalStateException("'proposal' field must be configured with the public key of a proposal.");
      }
      return new ProposalVote(proposal, side);
    }


    void reset() {
      proposal = null;
      side = -1;
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("proposal", buf, offset, len)) {
        proposal = PublicKeyEncoding.parseBase58Encoded(ji);
      } else if (fieldEquals("side", buf, offset, len)) {
        side = ji.readInt();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
