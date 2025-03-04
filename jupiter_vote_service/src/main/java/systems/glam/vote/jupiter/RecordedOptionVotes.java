package systems.glam.vote.jupiter;

import software.sava.core.accounts.PublicKey;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;

import static systems.glam.vote.jupiter.RecordedProposalVotes.*;

record RecordedOptionVotes(int side,
                           Path filePath,
                           RandomAccessFile file,
                           Set<PublicKey> glamKeys) {

  boolean votedFor(final PublicKey glamKey) {
    return glamKeys.contains(glamKey);
  }

  void unTrackVote(final PublicKey glamKey) {
    glamKeys.remove(glamKey);
  }

  void trackVoted(final PublicKey glamKey) {
    glamKeys.add(glamKey);
  }

  void persistVoted(final PublicKey glamKey) {
    writeBase58Key(file, glamKey);
    trackVoted(glamKey);
  }

  void persistVoted(final Collection<PublicKey> glamKeys) {
    writeBase58Keys(file, glamKeys);
  }

  void deleteFile() {
    try {
      file.close();
      Files.delete(filePath);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static RecordedOptionVotes createRecord(final Path filePath) {
    try {
      final var fileName = filePath.getFileName().toString();
      final int extensionOffset = fileName.indexOf('.');
      final int vote = Integer.parseInt(fileName, 0, extensionOffset, 10);
      final var file = new RandomAccessFile(filePath.toFile(), "rwd");
      final var keys = readBase58KeyFile(file);
      return new RecordedOptionVotes(vote, filePath, file, keys);
    } catch (final IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }
}
