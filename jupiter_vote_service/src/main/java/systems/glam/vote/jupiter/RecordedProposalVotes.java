package systems.glam.vote.jupiter;

import software.sava.anchor.programs.jupiter.governance.anchor.GovernConstants;
import software.sava.core.accounts.PublicKey;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

record RecordedProposalVotes(Set<PublicKey> overrides,
                             RandomAccessFile overridesFile,
                             Voting voting,
                             RecordedOptionVotes[] optionVotes) {

  boolean serviceVotedFor(final PublicKey glamKey, final int side) {
    final var optionVotes = this.optionVotes[side];
    return optionVotes != null && optionVotes.votedFor(glamKey);
  }

  boolean didUserOverrideVote(final PublicKey glamKey) {
    return overrides.contains(glamKey);
  }

  boolean needsToVote(final PublicKey glamKey, final int side) {
    return !overrides.contains(glamKey) && !optionVotes[side].votedFor(glamKey);
  }

  static void writeBase58Key(final RandomAccessFile file, final PublicKey key) {
    try {
      file.write(key.toBase58().getBytes());
      file.write('\n');
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void writeBase58Keys(final RandomAccessFile file, final Collection<PublicKey> keys) {
    final byte[] keyData = keys.stream()
        .map(PublicKey::toBase58)
        .collect(Collectors.joining("\n", "", "\n"))
        .getBytes();
    try {
      file.write(keyData);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  void persistUserVoteOverride(final PublicKey glamKey) {
    overrides.add(glamKey);
    writeBase58Key(overridesFile, glamKey);
  }

  void trackVoted(final PublicKey glamKey, final int side) {
    optionVotes[side].trackVoted(glamKey);
  }

  void persistVoted(final PublicKey glamKey, final int side) {
    optionVotes[side].persistVoted(glamKey);
  }

  void persistVoted(final Collection<PublicKey> glamKeys, final int side) {
    optionVotes[side].persistVoted(glamKeys);
  }

  void truncateVoting() {
    voting.truncate();
  }

  static byte[] readFully(final RandomAccessFile file) throws IOException {
    final int length = (int) file.length();
    final byte[] buffer = new byte[length];
    file.readFully(buffer);
    return buffer;
  }

  static Set<PublicKey> readBase58KeyFile(final RandomAccessFile keyFile) throws IOException {
    final int length = (int) keyFile.length();
    final int numKeys = length / PublicKey.PUBLIC_KEY_LENGTH;
    final var keys = HashSet.<PublicKey>newHashSet(numKeys);
    for (String line; (line = keyFile.readLine()) != null; ) {
      final var key = PublicKey.fromBase58Encoded(line);
      keys.add(key);
    }
    return keys;
  }

  static final String VOTES_SUFFIX = ".vote.txt";
  static final String OVERRIDE_FILE_NAME = "overridden.glams.txt";

  static RecordedProposalVotes createRecord(final Path proposalsDirectory, final ProposalVote proposalVote) {
    final var proposal = proposalVote.proposal();
    final var proposalDir = proposalsDirectory.resolve(proposal.toBase58());
    final var overridesFilePath = proposalDir.resolve(OVERRIDE_FILE_NAME);
    final var votingFilePath = proposalDir.resolve("voting.glams");
    final var voteFilePath = proposalDir.resolve(proposalVote.side() + VOTES_SUFFIX);
    try {
      if (Files.exists(proposalDir)) {
        if (Files.notExists(overridesFilePath)) {
          Files.createFile(overridesFilePath);
        }
        if (Files.notExists(votingFilePath)) {
          Files.createFile(votingFilePath);
        }
        if (Files.notExists(voteFilePath)) {
          Files.createFile(voteFilePath);
        }
      } else {
        Files.createDirectories(proposalDir);
        Files.createFile(overridesFilePath);
        Files.createFile(votingFilePath);
        Files.createFile(voteFilePath);
      }

      final var overridesFile = new RandomAccessFile(overridesFilePath.toFile(), "rwd");
      final var overrides = readBase58KeyFile(overridesFile);
      final var votingFile = new RandomAccessFile(votingFilePath.toFile(), "rwd");
      final var voting = Voting.readVotingFile(votingFile);

      final var optionVotes = new RecordedOptionVotes[GovernConstants.MAX_OPTION];
      try (final var paths = Files.walk(proposalDir)) {
        paths
            .filter(Files::isRegularFile)
            .filter(path -> path.getFileName().toString().endsWith(VOTES_SUFFIX))
            .map(RecordedOptionVotes::createRecord)
            .forEach(record -> optionVotes[record.side()] = record);
      }

      return new RecordedProposalVotes(
          overrides,
          overridesFile,
          voting,
          optionVotes
      );
    } catch (final IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }
}
