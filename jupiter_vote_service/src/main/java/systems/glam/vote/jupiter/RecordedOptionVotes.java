package systems.glam.vote.jupiter;

import software.sava.core.accounts.PublicKey;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

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
    try {
      file.write(glamKey.toByteArray());
      trackVoted(glamKey);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  void persistVoted(final byte[] glamKeys) {
    try {
      file.write(glamKeys);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  void deleteFile() {
    try {
      file.close();
      Files.delete(filePath);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }


  private static HashSet<PublicKey> readKeyFile(final RandomAccessFile keyFile) throws IOException {
    final int length = (int) keyFile.length();
    final int numKeys = length / PublicKey.PUBLIC_KEY_LENGTH;
    final byte[] buffer = new byte[length];
    keyFile.readFully(buffer);
    final var keys = HashSet.<PublicKey>newHashSet(numKeys << 1);
    for (int i = 0; i < length; i += PublicKey.PUBLIC_KEY_LENGTH) {
      final var glamAccount = PublicKey.readPubKey(buffer, i);
      keys.add(glamAccount);
    }
    return keys;
  }

  static RecordedOptionVotes createRecord(final Path filePath) {
    try {
      final var fileName = filePath.getFileName().toString();
      final int extensionOffset = fileName.indexOf('.');
      final int vote = Integer.parseInt(fileName, 0, extensionOffset, 10);
      final var file = new RandomAccessFile(filePath.toFile(), "rwd");
      final var keys = readKeyFile(file);
      return new RecordedOptionVotes(vote, filePath, file, keys);
    } catch (final IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }
}
