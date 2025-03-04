package systems.glam.vote.jupiter;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static systems.glam.vote.jupiter.RecordedProposalVotes.writeBase58Key;

final class StateSerializationTests {

  private static final Path BASE_PATH = Path.of(".test");

  @BeforeAll
  static void setup() throws IOException {
    if (!Files.exists(BASE_PATH)) {
      Files.createDirectory(BASE_PATH);
    }
  }

  @AfterAll
  static void teardown() throws IOException {
    Files.deleteIfExists(BASE_PATH);
  }

  @Test
  void testBase58KeyFile() throws IOException {
    final var overridesFilePath = BASE_PATH.resolve("overridden.glams.json");
    System.out.println(overridesFilePath);
    if (Files.notExists(overridesFilePath)) {
      Files.createFile(overridesFilePath);
    }

    try (final var overridesFile = new RandomAccessFile(overridesFilePath.toFile(), "rwd")) {
      overridesFile.setLength(0);

      final int numKeys = 42;
      final var expectedKeys = HashSet.<PublicKey>newHashSet(numKeys);
      for (int i = 0; i < numKeys; i++) {
        final var keyPair = Signer.generatePrivateKeyPairBytes();
        final var pubKey = PublicKey.readPubKey(keyPair);
        writeBase58Key(overridesFile, pubKey);
        expectedKeys.add(pubKey);
      }
      assertEquals(numKeys, expectedKeys.size());

      overridesFile.seek(0);
      var keys = RecordedProposalVotes.readBase58KeyFile(overridesFile);
      assertEquals(expectedKeys.size(), keys.size());

      overridesFile.setLength(0);

      RecordedProposalVotes.writeBase58Keys(overridesFile, expectedKeys);
      overridesFile.seek(0);
      keys = RecordedProposalVotes.readBase58KeyFile(overridesFile);
      assertEquals(expectedKeys.size(), keys.size());
    }

    Files.delete(overridesFilePath);
  }
}
