package systems.glam.vote.jupiter;

import software.sava.anchor.programs.glam.GlamJupiterVoteClient;
import software.sava.anchor.programs.jupiter.governance.anchor.types.Proposal;
import software.sava.anchor.programs.jupiter.governance.anchor.types.Vote;
import software.sava.anchor.programs.jupiter.voter.anchor.types.Escrow;
import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.Base58;
import software.sava.core.tx.Instruction;
import software.sava.rpc.json.http.response.IxError;
import software.sava.rpc.json.http.response.TransactionError;
import software.sava.services.solana.remote.call.RpcCaller;
import software.sava.services.solana.transactions.TransactionProcessor;
import software.sava.services.solana.transactions.TxMonitorService;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static java.lang.System.Logger.Level.*;
import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.tx.Transaction.SIGNATURE_LENGTH;
import static software.sava.rpc.json.http.client.SolanaRpcClient.MAX_MULTIPLE_ACCOUNTS;
import static software.sava.rpc.json.http.request.Commitment.CONFIRMED;
import static software.sava.rpc.json.http.request.Commitment.FINALIZED;
import static software.sava.services.solana.transactions.TransactionProcessor.formatSimulationResult;

abstract class VoteProcessor {

  private static final System.Logger logger = System.getLogger(VoteProcessor.class.getName());

  protected final VoteService voteService;
  protected final RpcCaller rpcCaller;
  protected final TransactionProcessor transactionProcessor;
  protected final TxMonitorService txMonitorService;
  private final BigDecimal maxLamportPriorityFee;
  protected final PublicKey proposalKey;
  protected final Proposal proposal;
  protected final int side;
  protected final GlamJupiterVoteClient[] voteClients;
  protected final RecordedProposalVotes recordedProposalVotes;
  private final Map<PublicKey, Escrow> escrowMap;
  protected final Map<PublicKey, Vote> voteMap;
  private final Instruction[] instructionArray;
  private final int reduceBatchSize;

  protected int maxBatchSize;

  protected int voteClientIndex;
  protected int voteKeyIndex;
  protected int batchSize;
  protected int ix;

  protected long batchBitSet;
  protected int resetBatchClientIndex;
  protected int resetBatchVoteKeyIndex;

  VoteProcessor(final VoteService voteService,
                final PublicKey proposalKey,
                final Proposal proposal,
                final int side,
                final GlamJupiterVoteClient[] voteClients,
                final RecordedProposalVotes recordedProposalVotes,
                final int numInstructionsPerGlam,
                final int maxBatchSize) {
    this.voteService = voteService;
    this.rpcCaller = voteService.rpcCaller();
    this.transactionProcessor = voteService.transactionProcessor();
    this.txMonitorService = voteService.transactionMonitorService();
    this.maxLamportPriorityFee = voteService.maxLamportPriorityFee();
    this.proposalKey = proposalKey;
    this.proposal = proposal;
    this.side = side;
    this.voteClients = voteClients;
    this.recordedProposalVotes = recordedProposalVotes;
    this.reduceBatchSize = numInstructionsPerGlam;
    this.instructionArray = new Instruction[maxBatchSize * numInstructionsPerGlam];
    this.maxBatchSize = maxBatchSize;
    this.escrowMap = HashMap.newHashMap(MAX_MULTIPLE_ACCOUNTS);
    this.voteMap = new HashMap<>();
  }

  protected abstract boolean userVoted(final Vote voteAccount);

  protected abstract int appendInstructions(final GlamJupiterVoteClient voteClient,
                                            final PublicKey voteKey,
                                            final Instruction[] ixArray,
                                            final int index);

  protected abstract void persistVotes(final Collection<PublicKey> glamKeys);

  final void processVotes() throws InterruptedException {
    final int numGlams = voteClients.length;
    logger.log(INFO, String.format(
            "Casting %d side vote for %d glams for proposal %s.",
            side, numGlams, proposalKey.toBase58()
        )
    );
    if (numGlams > 0) {
      recordedProposalVotes.truncateVoting();
    }
    for (int from = 0, to, len; from < numGlams; from = to, escrowMap.clear()) {
      to = Math.min(numGlams, from + MAX_MULTIPLE_ACCOUNTS);
      len = to - from;

      final var voteKeys = new PublicKey[len];
      final var escrowKeys = new PublicKey[len];
      for (int i = from, j = 0; i < to; ++i, ++j) {
        final var voteClient = voteClients[i];
        escrowKeys[j] = voteClient.escrowKey();
        voteKeys[j] = voteClient.deriveVoteKey(proposalKey);
      }

      logger.log(INFO, String.format("Fetching %d escrow and vote accounts.", len));
      final var escrowKeyList = Arrays.asList(escrowKeys);
      final var escrowAccountInfosFuture = rpcCaller.courteousCall(
          rpcClient -> rpcClient.getMultipleAccounts(escrowKeyList, Escrow.FACTORY),
          "rpcClient::getEscrowAccounts"
      );

      final var voteKeyList = Arrays.asList(voteKeys);
      final var voteAccountInfos = rpcCaller.courteousGet(
          rpcClient -> rpcClient.getMultipleAccounts(voteKeyList, Vote.FACTORY),
          "rpcClient::getVoteAccounts"
      );

      for (final var accountInfo : voteAccountInfos) {
        voteMap.put(accountInfo.pubKey(), accountInfo.data());
      }

      final var escrowAccountInfos = escrowAccountInfosFuture.join();
      for (final var accountInfo : escrowAccountInfos) {
        escrowMap.put(accountInfo.pubKey(), accountInfo.data());
      }

      logger.log(INFO, String.format(
              "Retrieved %d escrow and %d vote accounts.",
              escrowAccountInfos.size(), voteAccountInfos.size()
          )
      );
      processBatch(from, voteKeys);
    }
  }

  protected final GlamJupiterVoteClient getBatchVoteClient(final int offset) {
    for (int i = resetBatchClientIndex, bs = 1, c = 0; i < voteClientIndex; ++i, bs <<= 1) {
      if ((batchBitSet & bs) == bs) {
        if (c == offset) {
          return voteClients[i];
        } else {
          ++c;
        }
      }
    }
    throw new IllegalStateException("Offset out of bounds.");
  }

  private void processBatch(final int fromVoteClientIndex, final PublicKey[] voteKeys) throws InterruptedException {
    voteClientIndex = fromVoteClientIndex;
    resetBatchClientIndex = voteClientIndex;
    voteKeyIndex = 0;
    resetBatchVoteKeyIndex = voteKeyIndex;
    batchSize = 0;
    ix = 0;
    batchBitSet = 0;

    for (int b = 1; ; b = b == 0 ? 1 : b << 1) {
      if (voteKeyIndex < voteKeys.length) {
        final var voteClient = voteClients[voteClientIndex++];
        final var glamKey = voteClient.glamKey();
        final var voteKey = voteKeys[voteKeyIndex++];
        if (recordedProposalVotes.didUserOverrideVote(glamKey)) {
          continue;
        }

        final var existingVoteAccount = voteMap.get(voteKey);
        if (userVoted(existingVoteAccount)) {
          recordedProposalVotes.persistUserVoteOverride(glamKey);
          logger.log(INFO, String.format("""
                      Inferring that GLAM %s has overridden the vote for this proposal %s because a Vote account already exists.
                      """,
                  glamKey, proposalKey
              )
          );
          continue;
        }

        if (existingVoteAccount != null && existingVoteAccount.side() == side) {
          // Can happen if a service vote change was interrupted.
          // Once this processor is complete it will be cleared from the previous file.
          // It has already been persisted in this vote side file during start up recovery.
          continue;
        }

        final var escrow = escrowMap.get(voteClient.escrowKey());
        if (escrow == null) {
          final var msg = String.format("""
                  {
                    "event": "Delegated GLAM does not have Escrow.",
                    "escrowKey": "%s",
                    "glamStateKey": "%s"
                  }""",
              voteClient.escrowKey(),
              voteClient.glamKey()
          );
          voteService.postNotification(msg);
          continue;
        }

        if (!voteService.eligibleToVote(escrow)) {
          continue;
        }

        ix = appendInstructions(voteClient, voteKey, instructionArray, ix);
        ++batchSize;
        batchBitSet |= b;
      }

      if (voteKeyIndex == voteKeys.length || batchSize >= maxBatchSize) {
        recordedProposalVotes.truncateVoting();
        if (voteService.stopVotingForProposal(proposal)) {
          return;
        }
        if (publishBatch()) {
          resetBatchClientIndex = voteClientIndex;
          resetBatchVoteKeyIndex = voteKeyIndex;
        } else {
          // Completely reset batch because we do not know how far back to go if consecutive retries.
          voteClientIndex = resetBatchClientIndex;
          voteKeyIndex = resetBatchVoteKeyIndex;
        }
        ix = 0;
        batchSize = 0;
        batchBitSet = 0;
        b = 0;
        recordedProposalVotes.truncateVoting();
        if (voteKeyIndex == voteKeys.length) {
          return;
        }
      }
    }
  }

  private boolean publishBatch() throws InterruptedException {
    final var instructions = List.of(ix == instructionArray.length
        ? instructionArray
        : Arrays.copyOfRange(instructionArray, 0, ix)
    );

    final var simulationFutures = transactionProcessor.simulateAndEstimate(instructions);
    if (simulationFutures.exceedsSizeLimit()) {
      batchSize -= reduceBatchSize;
      return false;
    }

    logger.log(INFO, String.format(
            "Simulating %d side vote transaction %d glams for proposal %s.",
            side, batchSize, proposalKey.toBase58()
        )
    );

    final var keys = new ArrayList<PublicKey>(batchSize);
    final int keysByteLength = batchSize * PUBLIC_KEY_LENGTH;
    final byte[] votingFileBuffer = new byte[2 + keysByteLength + SIGNATURE_LENGTH];
    votingFileBuffer[0] = (byte) side;
    votingFileBuffer[1] = (byte) batchSize;
    for (int i = resetBatchClientIndex, offset = 2, bs = 1; i < voteClientIndex; ++i, bs <<= 1) {
      if ((batchBitSet & bs) == bs) {
        final var glamKey = voteClients[i].glamKey();
        keys.add(glamKey);
        glamKey.write(votingFileBuffer, offset);
        offset += PUBLIC_KEY_LENGTH;
      }
    }
    final var voting = recordedProposalVotes.voting();
    voting.write(votingFileBuffer);

    final var simulationResult = simulationFutures.simulationFuture().join();
    final var simulationError = simulationResult.error();
    if (simulationError != null) {
      handleError(
          simulationError,
          simulationResult.logs(),
          () -> formatSimulationResult(simulationResult)
      );
      return false;
    }

    final var transaction = transactionProcessor.createTransaction(simulationFutures, maxLamportPriorityFee, simulationResult);
    long blockHashHeight = transactionProcessor.setBlockHash(transaction, simulationResult);
    if (blockHashHeight == 0) {
      final var blockHash = rpcCaller.courteousGet(
          rpcClient -> rpcClient.getLatestBlockHash(FINALIZED),
          "rpcClient::getLatestBlockHash"
      );
      blockHashHeight = transactionProcessor.setBlockHash(transaction, blockHash);
    }

    transactionProcessor.signTransaction(transaction);
    final byte[] txSigBytes = transaction.getId();
    voting.write(txSigBytes);
    final var sendContext = transactionProcessor.publish(transaction, blockHashHeight);
    final var txSig = Base58.encode(txSigBytes);
    logPublishedTransaction(txSig);
    final var formattedSig = transactionProcessor.formatter().formatSig(txSig);
    logger.log(INFO, String.format("""
                Published %d vote instructions:
                %s
                """,
            instructions.size(), formattedSig
        )
    );

    final var txResult = txMonitorService.validateResponseAndAwaitCommitmentViaWebSocket(
        sendContext,
        FINALIZED,
        CONFIRMED,
        txSig
    );

    final TransactionError error;
    if (txResult != null) {
      error = txResult.error();
    } else {
      final var sigStatus = txMonitorService.queueResult(
          FINALIZED,
          CONFIRMED,
          txSig,
          sendContext,
          true
      ).join();
      if (sigStatus == null) {
        logger.log(INFO, String.format("""
                    Transaction expired, resetting batch.
                    %s
                    """,
                formattedSig
            )
        );
        return false;
      }
      error = sigStatus.error();
    }

    if (error != null) {
      handleError(
          error,
          simulationResult.logs(),
          () -> transactionProcessor.formatTxResult(txSig, txResult)
      );
      return false;
    } else {
      persistVotes(keys);
      logger.log(INFO, String.format("""
                  FINALIZED %d vote instructions:
                  %s
                  """,
              instructions.size(), formattedSig
          )
      );
      return true;
    }
  }

  protected abstract int reduceBatchSize();

  protected abstract boolean handledFailedIx(final int indexOffset,
                                             final long customErrorCode,
                                             final List<String> logs);

  protected final void handleError(final TransactionError error,
                                   final List<String> logs,
                                   final Supplier<String> logResult) {
    // TODO: handle escrow account updates such as deletion or transitions to fully unstaked.
    // TODO: handle vote account deletion
    switch (error) {
      case TransactionError.InstructionError(final int ixIndex, final var ixError) -> {
        switch (ixError) {
          case IxError.ComputationalBudgetExceeded _, IxError.MaxAccountsExceeded _ -> {
            this.maxBatchSize = reduceBatchSize();
            logger.log(WARNING, String.format("""
                        
                        Reducing vote batch size to %d because %s.
                        """,
                    this.maxBatchSize, ixError
                )
            );
          }
          case IxError.Custom(final long errorCode) -> {
            logger.log(ERROR, logResult.get());
            if (ixIndex >= 2) {
              final int indexOffset = ixIndex - 2;
              if (handledFailedIx(indexOffset, errorCode, logs)) {
                return;
              }
            }
            throw new IllegalStateException(String.format("""
                Unhandled ix error [index=%d] [error=%s]""", ixIndex, ixError
            ));
          }
          default -> {
            logger.log(ERROR, logResult.get());
            throw new IllegalStateException(String.format("""
                Unhandled ix error [index=%d] [error=%s]""", ixIndex, ixError
            ));
          }
        }
      }
      case TransactionError.InsufficientFundsForFee _, TransactionError.InsufficientFundsForRent _ -> {
        logger.log(ERROR, logResult.get());
        voteService.postNotification(String.format("""
                {
                 "event": "Insufficient funds for fee or rent.",
                 "action": "More lamports please.",
                 "account": "%s"
                }""",
            voteService.servicePublicKey()
        )).forEach(CompletableFuture::join);
        throw new IllegalStateException("Unhandled transaction error " + error);
      }
      default -> {
        logger.log(ERROR, logResult.get());
        throw new IllegalStateException("Unhandled transaction error " + error);
      }
    }
  }

  private void logPublishedTransaction(final String txSig) {
    final var glamKeys = new String[batchSize];
    for (int i = resetBatchClientIndex, bs = 1, k = 0; i < voteClientIndex; ++i, bs <<= 1) {
      if ((batchBitSet & bs) == bs) {
        glamKeys[k++] = voteClients[i].glamKey().toBase58();
      }
    }
    logger.log(INFO, String.format("""
                
                Published %d votes to the network.
                  sig: %s
                  glams: ["%s"]
                """,
            batchSize,
            voteService.chainItemFormatter().formatSig(txSig),
            String.join("\", \"", glamKeys)
        )
    );
  }
}
