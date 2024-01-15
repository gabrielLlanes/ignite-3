/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.tx.impl;

import static java.util.concurrent.CompletableFuture.failedFuture;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.ignite.internal.tx.TxState.ABANDONED;
import static org.apache.ignite.internal.tx.TxState.isFinalState;
import static org.apache.ignite.internal.util.CompletableFutures.falseCompletedFuture;
import static org.apache.ignite.internal.util.CompletableFutures.nullCompletedFuture;
import static org.apache.ignite.internal.util.FastTimestamps.coarseCurrentTimeMillis;
import static org.apache.ignite.lang.ErrorGroups.Transactions.ACQUIRE_LOCK_ERR;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.ignite.configuration.ConfigurationValue;
import org.apache.ignite.internal.hlc.HybridClock;
import org.apache.ignite.internal.logger.IgniteLogger;
import org.apache.ignite.internal.logger.Loggers;
import org.apache.ignite.internal.placementdriver.PlacementDriver;
import org.apache.ignite.internal.replicator.ReplicaService;
import org.apache.ignite.internal.replicator.ReplicationGroupId;
import org.apache.ignite.internal.replicator.TablePartitionId;
import org.apache.ignite.internal.tx.LockManager;
import org.apache.ignite.internal.tx.TxStateMeta;
import org.apache.ignite.internal.tx.TxStateMetaAbandoned;
import org.apache.ignite.internal.tx.event.LockEvent;
import org.apache.ignite.internal.tx.event.LockEventParameters;
import org.apache.ignite.internal.tx.message.TxMessagesFactory;
import org.apache.ignite.internal.tx.message.TxRecoveryMessage;
import org.apache.ignite.internal.util.IgniteSpinBusyLock;
import org.apache.ignite.network.ClusterNode;
import org.apache.ignite.network.TopologyService;
import org.apache.ignite.tx.TransactionException;

/**
 * The class detects transactions that are left without a coordinator but still hold locks. For that orphan transaction, the recovery
 * message is sent to the commit partition replication group.
 */
public class OrphanDetector {
    /** The logger. */
    private static final IgniteLogger LOG = Loggers.forClass(OrphanDetector.class);

    /** Tx messages factory. */
    private static final TxMessagesFactory FACTORY = new TxMessagesFactory();

    private static final long AWAIT_PRIMARY_REPLICA_TIMEOUT_SEC = 10;

    /** Busy lock to stop synchronously. */
    private final IgniteSpinBusyLock busyLock = new IgniteSpinBusyLock();

    /** Topology service. */
    private final TopologyService topologyService;

    /** Replica service. */
    private final ReplicaService replicaService;

    /** Placement driver. */
    private final PlacementDriver placementDriver;

    /** Lock manager. */
    private final LockManager lockManager;

    /** Hybrid clock. */
    private final HybridClock clock;

    /** Cleanup helper. */
    private final TxCleanupRequestSender requestSender;

    /**
     * The time interval in milliseconds in which the orphan resolution sends the recovery message again, in case the transaction is still
     * not finalized.
     */
    private long checkTxStateInterval;

    /** Local transaction state storage. */
    private VolatileTxStateMetaStorage txLocalStateStorage;

    /**
     * The constructor.
     *
     * @param topologyService Topology service.
     * @param replicaService Replica service.
     * @param placementDriver Placement driver.
     * @param lockManager Lock manager.
     * @param clock Clock.
     * @param requestSender Cleanup helper.
     */
    public OrphanDetector(
            TopologyService topologyService,
            ReplicaService replicaService,
            PlacementDriver placementDriver,
            LockManager lockManager,
            HybridClock clock,
            TxCleanupRequestSender requestSender
    ) {
        this.topologyService = topologyService;
        this.replicaService = replicaService;
        this.placementDriver = placementDriver;
        this.lockManager = lockManager;
        this.clock = clock;
        this.requestSender = requestSender;
    }

    /**
     * Starts the detector.
     *
     * @param txLocalStateStorage Local transaction state storage.
     * @param checkTxStateIntervalProvider Global provider of configuration check state interval.
     */
    public void start(VolatileTxStateMetaStorage txLocalStateStorage, ConfigurationValue<Long> checkTxStateIntervalProvider) {
        this.txLocalStateStorage = txLocalStateStorage;
        this.checkTxStateInterval = checkTxStateIntervalProvider.value();

        checkTxStateIntervalProvider.listen(ctx -> {
            this.checkTxStateInterval = ctx.newValue();

            return nullCompletedFuture();
        });

        lockManager.listen(LockEvent.LOCK_CONFLICT, this::lockConflictListener);
    }

    /**
     * Stops the detector.
     */
    public void stop() {
        busyLock.block();

        lockManager.removeListener(LockEvent.LOCK_CONFLICT, this::lockConflictListener);
    }

    /**
     * Sends {@link TxRecoveryMessage} if the transaction is orphaned.
     */
    private CompletableFuture<Boolean> lockConflictListener(LockEventParameters params, Throwable e) {
        if (busyLock.enterBusy()) {
            try {
                return checkTxOrphanedInternal(params.lockHolderTx());
            } finally {
                busyLock.leaveBusy();
            }
        }

        return falseCompletedFuture();
    }

    /**
     * Sends {@link TxRecoveryMessage} if the transaction is orphaned.
     *
     * @param txId Transaction id that holds a lock.
     * @return Future to complete.
     */
    private CompletableFuture<Boolean> checkTxOrphanedInternal(UUID txId) {
        TxStateMeta txState = txLocalStateStorage.state(txId);

        // Transaction state for full transactions is not stored in the local map, so it can be null.
        if (txState == null || isFinalState(txState.txState()) || isTxCoordinatorAlive(txState)) {
            return falseCompletedFuture();
        }

        if (makeTxAbandoned(txId, txState)) {
            LOG.info(
                    "Conflict was found, and the coordinator of the transaction that holds a lock is not available "
                            + "[txId={}, txCrd={}].",
                    txId,
                    txState.txCoordinatorId()
            );

            if (txState.commitPartitionId() == null) {
                // For external commit just remove locks. Write intent will be resolved lazily.
                requestSender.cleanup(topologyService.localMember().name(), txId);
            } else {
                sentTxRecoveryMessage(txState.commitPartitionId(), txId);
            }
        }

        // TODO: https://issues.apache.org/jira/browse/IGNITE-21153
        return failedFuture(
                new TransactionException(ACQUIRE_LOCK_ERR, "The lock is held by the abandoned transaction [abandonedTxId=" + txId + "]."));
    }

    /**
     * Sends transaction recovery message to commit partition for particular transaction.
     *
     * @param cmpPartGrp Replication group of commit partition.
     * @param txId Transaction id.
     */
    private void sentTxRecoveryMessage(ReplicationGroupId cmpPartGrp, UUID txId) {
        placementDriver.awaitPrimaryReplica(
                cmpPartGrp,
                clock.now(),
                AWAIT_PRIMARY_REPLICA_TIMEOUT_SEC,
                SECONDS
        ).thenCompose(replicaMeta -> {
            ClusterNode commitPartPrimaryNode = topologyService.getByConsistentId(replicaMeta.getLeaseholder());

            if (commitPartPrimaryNode == null) {
                LOG.warn(
                        "The primary replica of the commit partition is not available [commitPartGrp={}, tx={}]",
                        cmpPartGrp,
                        txId
                );

                return nullCompletedFuture();
            }

            return replicaService.invoke(commitPartPrimaryNode, FACTORY.txRecoveryMessage()
                    .groupId(cmpPartGrp)
                    .enlistmentConsistencyToken(replicaMeta.getStartTime().longValue())
                    .txId(txId)
                    .build());
        }).exceptionally(throwable -> {
            if (throwable != null) {
                LOG.warn("A recovery message for the transaction was handled with the error [tx={}].", throwable, txId);
            }

            return null;
        });
    }

    /**
     * Performs a life check for the transaction coordinator.
     *
     * @param txState Transaction state meta.
     * @return True when the transaction coordinator is alive, false otherwise.
     */
    private boolean isTxCoordinatorAlive(TxStateMeta txState) {
        return txState.txCoordinatorId() != null && topologyService.getById(txState.txCoordinatorId()) != null;
    }

    /**
     * Set TX state to {@link org.apache.ignite.internal.tx.TxState#ABANDONED}.
     *
     * @param txId Transaction id.
     * @param txState Transaction meta state.
     * @return True when TX state was set to ABANDONED.
     */
    private boolean makeTxAbandoned(UUID txId, TxStateMeta txState) {
        if (!isRecoveryNeeded(txState)) {
            return false;
        }

        TxStateMetaAbandoned txAbandonedState = txState.abandoned();

        TxStateMeta updatedTxState = txLocalStateStorage.updateMeta(txId, txStateMeta -> {
            if (isRecoveryNeeded(txStateMeta)) {
                return txAbandonedState;
            }

            return txStateMeta;
        });

        return txAbandonedState == updatedTxState;
    }

    /**
     * Checks whether the recovery transaction message should to be sent.
     *
     * @param txState Transaction meta state.
     * @return True when transaction recovery is needed, false otherwise.
     */
    private boolean isRecoveryNeeded(TxStateMeta txState) {
        return txState != null
                && !isFinalState(txState.txState())
                && !isTxAbandonedRecently(txState);
    }

    /**
     * Checks whether the transaction state is marked as abandoned recently (less than {@link #checkTxStateInterval} millis ago).
     *
     * @param txState Transaction state metadata.
     * @return True if the state recently updated to {@link org.apache.ignite.internal.tx.TxState#ABANDONED}.
     */
    private boolean isTxAbandonedRecently(TxStateMeta txState) {
        if (txState.txState() != ABANDONED) {
            return false;
        }

        assert txState instanceof TxStateMetaAbandoned : "The transaction state does not match the metadata [mata=" + txState + "].";

        var txStateAbandoned = (TxStateMetaAbandoned) txState;

        return txStateAbandoned.lastAbandonedMarkerTs() + checkTxStateInterval >= coarseCurrentTimeMillis();
    }
}
