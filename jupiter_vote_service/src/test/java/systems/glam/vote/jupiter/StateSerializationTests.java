package systems.glam.vote.jupiter;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.encoding.Base58;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.tx.Transaction.SIGNATURE_LENGTH;
import static systems.glam.vote.jupiter.RecordedProposalVotes.OVERRIDE_FILE_NAME;
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
  void testVotingFile() throws IOException {
    final var votingFilePath = BASE_PATH.resolve("voting.glams");
    if (Files.notExists(votingFilePath)) {
      Files.createFile(votingFilePath);
    }

    final int side = 1;
    final int batchSize = 8;
    final int keysByteLength = batchSize * PUBLIC_KEY_LENGTH;
    final byte[] votingFileBuffer = new byte[2 + keysByteLength + SIGNATURE_LENGTH];

    votingFileBuffer[0] = (byte) side;
    votingFileBuffer[1] = (byte) batchSize;

    int offset = 2;
    final var expectedKeys = HashSet.<PublicKey>newHashSet(batchSize);
    for (int i = 0; i < batchSize; i++) {
      final var keyPair = Signer.generatePrivateKeyPairBytes();
      final var pubKey = PublicKey.readPubKey(keyPair);

      pubKey.write(votingFileBuffer, offset);
      offset += PUBLIC_KEY_LENGTH;

      expectedKeys.add(pubKey);
    }
    assertEquals(batchSize, expectedKeys.size());

    final var random = new SecureRandom();
    final var sig = random.generateSeed(SIGNATURE_LENGTH);
    System.arraycopy(sig, 0, votingFileBuffer, offset, SIGNATURE_LENGTH);

    try (final var votingFile = new RandomAccessFile(votingFilePath.toFile(), "rwd")) {
      votingFile.setLength(0);

      votingFile.write(votingFileBuffer);
      votingFile.seek(0);

      final var voting = Voting.readVotingFile(votingFile);
      assertEquals(side, voting.side());

      final var keys = voting.keys();
      assertEquals(expectedKeys.size(), keys.size());
      assertTrue(expectedKeys.containsAll(keys));

      assertArrayEquals(sig, Base58.decode(voting.txSig()));
    } finally {
      Files.delete(votingFilePath);
    }
  }

  @Test
  void testBase58KeyFile() throws IOException {
    final var overridesFilePath = BASE_PATH.resolve(OVERRIDE_FILE_NAME);
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
      assertTrue(expectedKeys.containsAll(keys));

      overridesFile.setLength(0);

      RecordedProposalVotes.writeBase58Keys(overridesFile, expectedKeys);
      overridesFile.seek(0);
      keys = RecordedProposalVotes.readBase58KeyFile(overridesFile);
      assertEquals(expectedKeys.size(), keys.size());
      assertTrue(expectedKeys.containsAll(keys));
    } finally {
      Files.delete(overridesFilePath);
    }
  }
}
