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

package org.apache.ignite.internal.metastorage.server;

import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static java.util.stream.Collectors.toList;
import static org.apache.ignite.internal.failure.FailureType.CRITICAL_ERROR;
import static org.apache.ignite.internal.metastorage.server.raft.MetaStorageWriteHandler.IDEMPOTENT_COMMAND_PREFIX_BYTES;
import static org.apache.ignite.internal.thread.ThreadOperation.NOTHING_ALLOWED;
import static org.apache.ignite.internal.util.CompletableFutures.nullCompletedFuture;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.apache.ignite.internal.close.ManuallyCloseable;
import org.apache.ignite.internal.failure.FailureContext;
import org.apache.ignite.internal.failure.FailureManager;
import org.apache.ignite.internal.hlc.HybridTimestamp;
import org.apache.ignite.internal.lang.IgniteSystemProperties;
import org.apache.ignite.internal.lang.NodeStoppingException;
import org.apache.ignite.internal.logger.IgniteLogger;
import org.apache.ignite.internal.logger.Loggers;
import org.apache.ignite.internal.metastorage.CompactionRevisionUpdateListener;
import org.apache.ignite.internal.metastorage.Entry;
import org.apache.ignite.internal.metastorage.EntryEvent;
import org.apache.ignite.internal.metastorage.RevisionUpdateListener;
import org.apache.ignite.internal.metastorage.WatchEvent;
import org.apache.ignite.internal.metastorage.WatchListener;
import org.apache.ignite.internal.thread.IgniteThreadFactory;
import org.apache.ignite.internal.util.ExceptionUtils;
import org.apache.ignite.internal.util.IgniteUtils;

/**
 * Class for storing and notifying Meta Storage Watches.
 *
 * <p>Every Meta Storage update is processed by each registered Watch in parallel, however notifications for a single Watch are
 * linearised (Watches are always notified of one event at a time and in increasing order of revisions). It is also guaranteed that Watches
 * will not get notified of a new revision until all Watches have finished processing a previous revision.
 */
public class WatchProcessor implements ManuallyCloseable {
    /** Reads an entry from the storage using a given key and revision. */
    @FunctionalInterface
    public interface EntryReader {
        Entry get(byte[] key, long revision);
    }

    private static final IgniteLogger LOG = Loggers.forClass(WatchProcessor.class);

    /**
     * If watch event processing takes more time, than this constant, we will log warning message with some information.
     */
    private static final int WATCH_EVENT_PROCESSING_LOG_THRESHOLD_MILLIS = 100;

    /**
     * The number of keys in log message, that will be printed for long events.
     *
     * @see #WATCH_EVENT_PROCESSING_LOG_THRESHOLD_MILLIS
     */
    private static final int WATCH_EVENT_PROCESSING_LOG_KEYS = 10;

    /** Map that contains Watches and corresponding Watch notification process (represented as a CompletableFuture). */
    private final List<Watch> watches = new CopyOnWriteArrayList<>();

    /**
     * Future that represents the process of notifying registered Watches about a Meta Storage revision.
     *
     * <p>Since Watches are notified concurrently, this future is used to guarantee that no Watches get notified of a new revision,
     * until all Watches have finished processing the previous revision.
     */
    private volatile CompletableFuture<Void> notificationFuture = nullCompletedFuture();

    private final EntryReader entryReader;

    private volatile WatchEventHandlingCallback watchEventHandlingCallback;

    /** Executor for processing watch events. */
    private final ExecutorService watchExecutor;

    /** Meta Storage revision update listeners. */
    private final List<RevisionUpdateListener> revisionUpdateListeners = new CopyOnWriteArrayList<>();

    /** Metastorage compaction revision update listeners. */
    private final List<CompactionRevisionUpdateListener> compactionRevisionUpdateListeners = new CopyOnWriteArrayList<>();

    /** Failure processor that is used to handle critical errors. */
    private final FailureManager failureManager;

    /**
     * Whether a failure in notification chain was passed to the FailureHandler. Used to make sure that we only pass first such a failure
     * because, as any failure in the chain will stop any notifications, it only makes sense to log the first one. Subsequent ones will
     * be instances of the same original exception.
     */
    private final AtomicBoolean firedFailureOnChain = new AtomicBoolean(false);

    /**
     * Creates a new instance.
     *
     * @param entryReader Function for reading an entry from the storage using a given key and revision.
     */
    public WatchProcessor(String nodeName, EntryReader entryReader, FailureManager failureManager) {
        this.entryReader = entryReader;

        this.watchExecutor = Executors.newFixedThreadPool(
                4,
                IgniteThreadFactory.create(nodeName, "metastorage-watch-executor", LOG, NOTHING_ALLOWED)
        );

        this.failureManager = failureManager;
    }

    /** Adds a watch. */
    public void addWatch(Watch watch) {
        watches.add(watch);
    }

    /** Removes a watch (identified by its listener). */
    void removeWatch(WatchListener listener) {
        watches.removeIf(watch -> watch.listener() == listener);
    }

    /**
     * Returns the minimal target revision of all registered watches.
     */
    public OptionalLong minWatchRevision() {
        return watches.stream()
                .mapToLong(Watch::startRevision)
                .min();
    }

    /** Sets the watch event handling callback. */
    public void setWatchEventHandlingCallback(WatchEventHandlingCallback callback) {
        assert this.watchEventHandlingCallback == null;

        this.watchEventHandlingCallback = callback;
    }

    /**
     * Queues the following set of actions that will be executed after the previous invocation of this method completes:
     *
     * <ol>
     *     <li>Notifies all registered watches about the changed entries;</li>
     *     <li>Notifies all registered revision listeners about the new revision;</li>
     *     <li>After all above notifications are processed, notifies about the Safe Time update.</li>
     * </ol>
     *
     * <p>This method is not thread-safe and must be performed under an exclusive lock in concurrent scenarios.
     *
     * @param updatedEntries Entries that were changed during a Meta Storage update.
     * @param time Timestamp of the Meta Storage update.
     * @return Future that gets completed when all registered watches have been notified of the given event.
     */
    public CompletableFuture<Void> notifyWatches(List<Entry> updatedEntries, HybridTimestamp time) {
        assert time != null;

        CompletableFuture<Void> newFuture = notificationFuture
                .thenComposeAsync(v -> {
                    // Revision must be the same for all entries.
                    long newRevision = updatedEntries.get(0).revision();

                    List<Entry> filteredUpdatedEntries = updatedEntries.stream()
                            .filter(WatchProcessor::isNotIdempotentCacheCommand)
                            .collect(toList());

                    List<WatchAndEvents> watchAndEvents = collectWatchesAndEvents(filteredUpdatedEntries, newRevision);

                    long startTimeNanos = System.nanoTime();

                    CompletableFuture<Void> notifyWatchesFuture = notifyWatches(watchAndEvents, newRevision, time);

                    // Revision update is triggered strictly after all watch listeners have been notified.
                    CompletableFuture<Void> notifyUpdateRevisionFuture = notifyUpdateRevisionListeners(newRevision);

                    CompletableFuture<Void> notificationFuture = allOf(notifyWatchesFuture, notifyUpdateRevisionFuture)
                            .thenRunAsync(() -> invokeOnRevisionCallback(newRevision, time), watchExecutor);

                    notificationFuture.whenComplete((unused, e) -> maybeLogLongProcessing(filteredUpdatedEntries, startTimeNanos));

                    return notificationFuture;
                }, watchExecutor)
                .whenComplete((unused, e) -> {
                    if (e != null) {
                        notifyFailureHandlerOnFirstFailureInNotificationChain(e);
                    }
                });

        notificationFuture = newFuture;

        return newFuture;
    }

    private static CompletableFuture<Void> notifyWatches(List<WatchAndEvents> watchAndEventsList, long revision, HybridTimestamp time) {
        if (watchAndEventsList.isEmpty()) {
            return nullCompletedFuture();
        }

        CompletableFuture<?>[] notifyWatchFutures = new CompletableFuture[watchAndEventsList.size()];

        for (int i = 0; i < watchAndEventsList.size(); i++) {
            WatchAndEvents watchAndEvents = watchAndEventsList.get(i);

            CompletableFuture<Void> notifyWatchFuture;

            try {
                var event = new WatchEvent(watchAndEvents.events, revision, time);

                notifyWatchFuture = watchAndEvents.watch.onUpdate(event);
            } catch (Throwable throwable) {
                notifyWatchFuture = failedFuture(throwable);
            }

            notifyWatchFutures[i] = notifyWatchFuture;
        }

        return allOf(notifyWatchFutures);
    }

    private static void maybeLogLongProcessing(List<Entry> updatedEntries, long startTimeNanos) {
        if (!IgniteSystemProperties.getBoolean(IgniteSystemProperties.LONG_HANDLING_LOGGING_ENABLED, false)) {
            return;
        }

        long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTimeNanos);

        if (durationMillis > WATCH_EVENT_PROCESSING_LOG_THRESHOLD_MILLIS) {
            String keysHead = updatedEntries.stream()
                    .limit(WATCH_EVENT_PROCESSING_LOG_KEYS)
                    .map(entry -> new String(entry.key(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining(", "));

            String keysTail = updatedEntries.size() > WATCH_EVENT_PROCESSING_LOG_KEYS ? ", ..." : "";

            LOG.warn(
                    "Watch event processing has been too long [duration={}, keys=[{}{}]]",
                    durationMillis,
                    keysHead,
                    keysTail
            );
        }
    }

    private List<WatchAndEvents> collectWatchesAndEvents(List<Entry> updatedEntries, long revision) {
        if (watches.isEmpty()) {
            return List.of();
        }

        var watchAndEvents = new ArrayList<WatchAndEvents>();

        for (Watch watch : watches) {
            List<EntryEvent> events = List.of();

            for (Entry newEntry : updatedEntries) {
                byte[] newKey = newEntry.key();

                assert newEntry.revision() == revision;

                if (watch.matches(newKey, revision)) {
                    Entry oldEntry = entryReader.get(newKey, revision - 1);

                    if (events.isEmpty()) {
                        events = new ArrayList<>();
                    }

                    events.add(new EntryEvent(oldEntry, newEntry));
                }
            }

            if (!events.isEmpty()) {
                watchAndEvents.add(new WatchAndEvents(watch, events));
            }
        }

        return watchAndEvents;
    }

    private void invokeOnRevisionCallback(long revision, HybridTimestamp time) {
        watchEventHandlingCallback.onSafeTimeAdvanced(time);

        watchEventHandlingCallback.onRevisionApplied(revision);
    }

    /**
     * Advances safe time without notifying watches (as there is no new revision).
     *
     * <p>This method is not thread-safe and must be performed under an exclusive lock in concurrent scenarios.
     */
    public void advanceSafeTime(HybridTimestamp time) {
        assert time != null;

        //noinspection NonAtomicOperationOnVolatileField
        notificationFuture = notificationFuture
                .thenRunAsync(() -> watchEventHandlingCallback.onSafeTimeAdvanced(time), watchExecutor)
                .whenComplete((ignored, e) -> {
                    if (e != null) {
                        notifyFailureHandlerOnFirstFailureInNotificationChain(e);
                    }
                });
    }

    private void notifyFailureHandlerOnFirstFailureInNotificationChain(Throwable e) {
        if (firedFailureOnChain.compareAndSet(false, true)) {
            boolean nodeStopping = ExceptionUtils.hasCauseOrSuppressed(e, NodeStoppingException.class);

            if (!nodeStopping) {
                LOG.error("Notification chain encountered an error, so no notifications will be ever fired for subsequent revisions "
                        + "until a restart. Notifying the FailureManager");

                failureManager.process(new FailureContext(CRITICAL_ERROR, e));
            } else {
                LOG.info("Notification chain encountered a NodeStoppingException, so no notifications will be ever fired for "
                        + "subsequent revisions until a restart.");
            }
        }
    }

    @Override
    public void close() {
        notificationFuture.cancel(true);

        IgniteUtils.shutdownAndAwaitTermination(watchExecutor, 10, TimeUnit.SECONDS);
    }

    /** Registers a Meta Storage revision update listener. */
    void registerRevisionUpdateListener(RevisionUpdateListener listener) {
        revisionUpdateListeners.add(listener);
    }

    /** Unregisters a Meta Storage revision update listener. */
    void unregisterRevisionUpdateListener(RevisionUpdateListener listener) {
        revisionUpdateListeners.remove(listener);
    }

    /** Registers a metastorage compaction revision update listener. */
    void registerCompactionRevisionUpdateListener(CompactionRevisionUpdateListener listener) {
        compactionRevisionUpdateListeners.add(listener);
    }

    /** Unregisters a metastorage compaction revision update listener. */
    void unregisterCompactionRevisionUpdateListener(CompactionRevisionUpdateListener listener) {
        compactionRevisionUpdateListeners.remove(listener);
    }

    /** Explicitly notifies revision update listeners. */
    CompletableFuture<Void> notifyUpdateRevisionListeners(long newRevision) {
        // Lazy set.
        List<CompletableFuture<?>> futures = List.of();

        for (RevisionUpdateListener listener : revisionUpdateListeners) {
            if (futures.isEmpty()) {
                futures = new ArrayList<>();
            }

            futures.add(listener.onUpdated(newRevision));
        }

        return futures.isEmpty() ? nullCompletedFuture() : allOf(futures.toArray(CompletableFuture[]::new));
    }

    /**
     * Updates the metastorage compaction revision in the WatchEvent queue.
     *
     * <p>This method is not thread-safe and must be performed under an exclusive lock in concurrent scenarios.</p>
     *
     * @param compactionRevision New metastorage compaction revision.
     * @param time Metastorage compaction revision update timestamp.
     */
    void updateCompactionRevision(long compactionRevision, HybridTimestamp time) {
        //noinspection NonAtomicOperationOnVolatileField
        notificationFuture = notificationFuture
                .thenRunAsync(() -> {
                    compactionRevisionUpdateListeners.forEach(listener -> listener.onUpdate(compactionRevision));

                    watchEventHandlingCallback.onSafeTimeAdvanced(time);
                }, watchExecutor)
                .whenComplete((ignored, e) -> {
                    if (e != null) {
                        notifyFailureHandlerOnFirstFailureInNotificationChain(e);
                    }
                });
    }

    /**
     * Updates the metastorage revision in the WatchEvent queue. It should be used for those cases when the revision has been updated but
     * no {@link Entry}s have been updated.
     *
     * @param newRevision New metastorage revision.
     * @param time Metastorage revision update timestamp.
     */
    void updateOnlyRevision(long newRevision, HybridTimestamp time) {
        //noinspection NonAtomicOperationOnVolatileField
        notificationFuture = notificationFuture
                .thenComposeAsync(unused -> notifyUpdateRevisionListeners(newRevision), watchExecutor)
                .thenRunAsync(() -> invokeOnRevisionCallback(newRevision, time), watchExecutor)
                .whenComplete((ignored, e) -> {
                    if (e != null) {
                        notifyFailureHandlerOnFirstFailureInNotificationChain(e);
                    }
                });
    }

    private static boolean isNotIdempotentCacheCommand(Entry entry) {
        int prefixLength = IDEMPOTENT_COMMAND_PREFIX_BYTES.length;

        //noinspection SimplifiableIfStatement
        if (entry.key().length <= prefixLength) {
            return true;
        }

        return !Arrays.equals(
                entry.key(), 0, prefixLength,
                IDEMPOTENT_COMMAND_PREFIX_BYTES, 0, prefixLength
        );
    }
}
