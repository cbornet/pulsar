/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.service.persistent;

import static org.apache.pulsar.broker.service.StickyKeyConsumerSelector.STICKY_KEY_HASH_NOT_SET;
import static org.apache.pulsar.broker.service.persistent.PersistentTopic.MESSAGE_RATE_BACKOFF_MS;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Range;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Predicate;
import org.apache.bookkeeper.mledger.Entry;
import org.apache.bookkeeper.mledger.ManagedCursor;
import org.apache.bookkeeper.mledger.ManagedLedgerException;
import org.apache.bookkeeper.mledger.ManagedLedgerException.NoMoreEntriesToReadException;
import org.apache.bookkeeper.mledger.ManagedLedgerException.TooManyRequestsException;
import org.apache.bookkeeper.mledger.Position;
import org.apache.bookkeeper.mledger.PositionFactory;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.delayed.BucketDelayedDeliveryTrackerFactory;
import org.apache.pulsar.broker.delayed.DelayedDeliveryTracker;
import org.apache.pulsar.broker.delayed.DelayedDeliveryTrackerFactory;
import org.apache.pulsar.broker.delayed.InMemoryDelayedDeliveryTracker;
import org.apache.pulsar.broker.delayed.bucket.BucketDelayedDeliveryTracker;
import org.apache.pulsar.broker.loadbalance.extensions.data.BrokerLookupData;
import org.apache.pulsar.broker.service.AbstractDispatcherMultipleConsumers;
import org.apache.pulsar.broker.service.BrokerServiceException;
import org.apache.pulsar.broker.service.BrokerServiceException.ConsumerBusyException;
import org.apache.pulsar.broker.service.Consumer;
import org.apache.pulsar.broker.service.EntryAndMetadata;
import org.apache.pulsar.broker.service.EntryBatchIndexesAcks;
import org.apache.pulsar.broker.service.EntryBatchSizes;
import org.apache.pulsar.broker.service.InMemoryRedeliveryTracker;
import org.apache.pulsar.broker.service.RedeliveryTracker;
import org.apache.pulsar.broker.service.RedeliveryTrackerDisabled;
import org.apache.pulsar.broker.service.SendMessageInfo;
import org.apache.pulsar.broker.service.SharedConsumerAssignor;
import org.apache.pulsar.broker.service.Subscription;
import org.apache.pulsar.broker.transaction.exception.buffer.TransactionBufferException;
import org.apache.pulsar.common.api.proto.CommandSubscribe.SubType;
import org.apache.pulsar.common.api.proto.MessageMetadata;
import org.apache.pulsar.common.policies.data.stats.TopicMetricBean;
import org.apache.pulsar.common.protocol.Commands;
import org.apache.pulsar.common.util.Backoff;
import org.apache.pulsar.common.util.Codec;
import org.apache.pulsar.common.util.FutureUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 */
public class PersistentDispatcherMultipleConsumers extends AbstractPersistentDispatcherMultipleConsumers {
    protected final PersistentTopic topic;
    protected final ManagedCursor cursor;
    protected volatile Range<Position> lastIndividualDeletedRangeFromCursorRecovery;

    private CompletableFuture<Void> closeFuture = null;
    protected final MessageRedeliveryController redeliveryMessages;
    protected final RedeliveryTracker redeliveryTracker;

    private Optional<DelayedDeliveryTracker> delayedDeliveryTracker = Optional.empty();

    protected volatile boolean havePendingRead = false;
    protected volatile boolean havePendingReplayRead = false;
    protected volatile Position minReplayedPosition = null;
    protected boolean shouldRewindBeforeReadingOrReplaying = false;
    protected final String name;
    private boolean sendInProgress = false;
    protected static final AtomicIntegerFieldUpdater<PersistentDispatcherMultipleConsumers>
            TOTAL_AVAILABLE_PERMITS_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(PersistentDispatcherMultipleConsumers.class,
                    "totalAvailablePermits");
    protected volatile int totalAvailablePermits = 0;
    protected volatile int readBatchSize;
    protected final Backoff readFailureBackoff;
    private static final AtomicIntegerFieldUpdater<PersistentDispatcherMultipleConsumers>
            TOTAL_UNACKED_MESSAGES_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(PersistentDispatcherMultipleConsumers.class,
                    "totalUnackedMessages");
    protected volatile int totalUnackedMessages = 0;
    /**
     * A signature that relate to the check of "Dispatching has paused on cursor data can fully persist".
     * Note:  It is a tool that helps determine whether it should trigger a new reading after acknowledgments to avoid
     *   too many CPU circles, see {@link #afterAckMessages(Throwable, Object)} for more details. Do not use this
     *   to confirm whether the delivery should be paused, please call {@link #shouldPauseOnAckStatePersist}.
     */
    protected static final AtomicIntegerFieldUpdater<PersistentDispatcherMultipleConsumers>
            BLOCKED_DISPATCHER_ON_CURSOR_DATA_CAN_NOT_FULLY_PERSIST_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(PersistentDispatcherMultipleConsumers.class,
                    "blockedDispatcherOnCursorDataCanNotFullyPersist");
    private volatile int blockedDispatcherOnCursorDataCanNotFullyPersist = FALSE;
    private volatile int blockedDispatcherOnUnackedMsgs = FALSE;
    protected static final AtomicIntegerFieldUpdater<PersistentDispatcherMultipleConsumers>
            BLOCKED_DISPATCHER_ON_UNACKMSG_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(PersistentDispatcherMultipleConsumers.class,
                    "blockedDispatcherOnUnackedMsgs");
    protected Optional<DispatchRateLimiter> dispatchRateLimiter = Optional.empty();
    private AtomicBoolean isRescheduleReadInProgress = new AtomicBoolean(false);
    private final AtomicBoolean readMoreEntriesAsyncRequested = new AtomicBoolean(false);
    protected final ExecutorService dispatchMessagesThread;
    private final SharedConsumerAssignor assignor;
    // tracks how many entries were processed by consumers in the last trySendMessagesToConsumers call
    // the number includes also delayed messages, marker messages, aborted txn messages and filtered messages
    // When no messages were processed, the value is 0. This is also an indication that the dispatcher didn't
    // make progress in the last trySendMessagesToConsumers call.
    protected int lastNumberOfEntriesProcessed;
    protected boolean skipNextBackoff;
    private final Backoff retryBackoff;
    protected enum ReadType {
        Normal, Replay
    }
    private Position lastMarkDeletePositionBeforeReadMoreEntries;
    private volatile long readMoreEntriesCallCount;

    public PersistentDispatcherMultipleConsumers(PersistentTopic topic, ManagedCursor cursor,
            Subscription subscription) {
        this(topic, cursor, subscription, true);
    }

    public PersistentDispatcherMultipleConsumers(PersistentTopic topic, ManagedCursor cursor, Subscription subscription,
            boolean allowOutOfOrderDelivery) {
        super(subscription, topic.getBrokerService().pulsar().getConfiguration());
        this.cursor = cursor;
        this.lastIndividualDeletedRangeFromCursorRecovery = cursor.getLastIndividualDeletedRange();
        this.name = topic.getName() + " / " + Codec.decode(cursor.getName());
        this.topic = topic;
        this.dispatchMessagesThread = topic.getBrokerService().getTopicOrderedExecutor().chooseThread();
        this.redeliveryMessages = new MessageRedeliveryController(allowOutOfOrderDelivery, false);
        this.redeliveryTracker = this.serviceConfig.isSubscriptionRedeliveryTrackerEnabled()
                ? new InMemoryRedeliveryTracker()
                : RedeliveryTrackerDisabled.REDELIVERY_TRACKER_DISABLED;
        this.readBatchSize = serviceConfig.getDispatcherMaxReadBatchSize();
        this.initializeDispatchRateLimiterIfNeeded();
        this.assignor = new SharedConsumerAssignor(this::getNextConsumer, this::addEntryToReplay);
        ServiceConfiguration serviceConfiguration = topic.getBrokerService().pulsar().getConfiguration();
        this.readFailureBackoff = new Backoff(
                serviceConfiguration.getDispatcherReadFailureBackoffInitialTimeInMs(),
                TimeUnit.MILLISECONDS,
                1, TimeUnit.MINUTES, 0, TimeUnit.MILLISECONDS);
        retryBackoff = new Backoff(
                serviceConfiguration.getDispatcherRetryBackoffInitialTimeInMs(), TimeUnit.MILLISECONDS,
                serviceConfiguration.getDispatcherRetryBackoffMaxTimeInMs(), TimeUnit.MILLISECONDS,
                0, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized CompletableFuture<Void> addConsumer(Consumer consumer) {
        if (IS_CLOSED_UPDATER.get(this) == TRUE) {
            log.warn("[{}] Dispatcher is already closed. Closing consumer {}", name, consumer);
            consumer.disconnect();
            return CompletableFuture.completedFuture(null);
        }
        if (consumerList.isEmpty()) {
            if (havePendingRead || havePendingReplayRead) {
                // There is a pending read from previous run. We must wait for it to complete and then rewind
                shouldRewindBeforeReadingOrReplaying = true;
            } else {
                cursor.rewind();
                shouldRewindBeforeReadingOrReplaying = false;
            }
            redeliveryMessages.clear();
            delayedDeliveryTracker.ifPresent(tracker -> {
                // Don't clean up BucketDelayedDeliveryTracker, otherwise we will lose the bucket snapshot
                if (tracker instanceof InMemoryDelayedDeliveryTracker) {
                    tracker.clear();
                }
            });
        }

        if (isConsumersExceededOnSubscription()) {
            log.warn("[{}] Attempting to add consumer to subscription which reached max consumers limit {}",
                    name, consumer);
            return FutureUtil.failedFuture(new ConsumerBusyException("Subscription reached max consumers limit"));
        }
        // This is not an expected scenario, it will never happen in expected. Just print a warn log if the unexpected
        // scenario happens. See more detail: https://github.com/apache/pulsar/pull/22283.
        if (consumerSet.contains(consumer)) {
            log.warn("[{}] Attempting to add a consumer that already registered {}", name, consumer);
        }

        consumerList.add(consumer);
        if (consumerList.size() > 1
                && consumer.getPriorityLevel() < consumerList.get(consumerList.size() - 2).getPriorityLevel()) {
            consumerList.sort(Comparator.comparingInt(Consumer::getPriorityLevel));
        }
        consumerSet.add(consumer);

        return CompletableFuture.completedFuture(null);
    }

    @Override
    protected boolean isConsumersExceededOnSubscription() {
        return isConsumersExceededOnSubscription(topic, consumerList.size());
    }

    @Override
    public synchronized void removeConsumer(Consumer consumer) throws BrokerServiceException {
        // decrement unack-message count for removed consumer
        addUnAckedMessages(-consumer.getUnackedMessages());
        if (consumerSet.removeAll(consumer) == 1) {
            consumerList.remove(consumer);
            log.info("Removed consumer {} with pending {} acks", consumer, consumer.getPendingAcks().size());
            if (consumerList.isEmpty()) {
                clearComponentsAfterRemovedAllConsumers();
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("[{}] Consumer are left, reading more entries", name);
                }
                MutableBoolean notifyAddedToReplay = new MutableBoolean(false);
                consumer.getPendingAcks().forEachAndClose((ledgerId, entryId, batchSize, stickyKeyHash) -> {
                    boolean addedToReplay = addMessageToReplay(ledgerId, entryId, stickyKeyHash);
                    if (addedToReplay) {
                        notifyAddedToReplay.setTrue();
                    }
                });
                totalAvailablePermits -= consumer.getAvailablePermits();
                if (log.isDebugEnabled()) {
                    log.debug("[{}] Decreased totalAvailablePermits by {} in PersistentDispatcherMultipleConsumers. "
                                    + "New dispatcher permit count is {}", name, consumer.getAvailablePermits(),
                            totalAvailablePermits);
                }
                if (notifyAddedToReplay.booleanValue()) {
                    notifyRedeliveryMessageAdded();
                }
            }
        } else {
            /**
             * This is not an expected scenario, it will never happen in expected.
             * Just add a defensive code to avoid the topic can not be unloaded anymore: remove the consumers which
             * are not mismatch with {@link #consumerSet}. See more detail: https://github.com/apache/pulsar/pull/22270.
             */
            log.error("[{}] Trying to remove a non-connected consumer: {}", name, consumer);
            consumerList.removeIf(c -> consumer.equals(c));
            if (consumerList.isEmpty()) {
                clearComponentsAfterRemovedAllConsumers();
            }
        }
    }

    protected synchronized void internalRemoveConsumer(Consumer consumer) {
        consumerSet.removeAll(consumer);
        consumerList.remove(consumer);
    }

    protected synchronized void clearComponentsAfterRemovedAllConsumers() {
        cancelPendingRead();

        redeliveryMessages.clear();
        redeliveryTracker.clear();
        if (closeFuture != null) {
            log.info("[{}] All consumers removed. Subscription is disconnected", name);
            closeFuture.complete(null);
        }
        totalAvailablePermits = 0;
    }

    @Override
    public void consumerFlow(Consumer consumer, int additionalNumberOfMessages) {
        topic.getBrokerService().executor().execute(() -> {
            internalConsumerFlow(consumer, additionalNumberOfMessages);
        });
    }

    private synchronized void internalConsumerFlow(Consumer consumer, int additionalNumberOfMessages) {
        if (!consumerSet.contains(consumer)) {
            if (log.isDebugEnabled()) {
                log.debug("[{}] Ignoring flow control from disconnected consumer {}", name, consumer);
            }
            return;
        }

        totalAvailablePermits += additionalNumberOfMessages;

        if (log.isDebugEnabled()) {
            log.debug("[{}-{}] Trigger new read after receiving flow control message with permits {} "
                            + "after adding {} permits", name, consumer,
                    totalAvailablePermits, additionalNumberOfMessages);
        }
        readMoreEntriesAsync();
    }

    /**
     * We should not call readMoreEntries() recursively in the same thread as there is a risk of StackOverflowError.
     *
     */
    @Override
    public void readMoreEntriesAsync() {
        // deduplication for readMoreEntriesAsync calls
        if (readMoreEntriesAsyncRequested.compareAndSet(false, true)) {
            topic.getBrokerService().executor().execute(() -> {
                readMoreEntriesAsyncRequested.set(false);
                readMoreEntries();
            });
        }
    }

    public synchronized void readMoreEntries() {
        if (cursor.isClosed()) {
            if (log.isDebugEnabled()) {
                log.debug("[{}] Cursor is already closed, skipping read more entries.", cursor.getName());
            }
            return;
        }
        if (isSendInProgress()) {
            // we cannot read more entries while sending the previous batch
            // otherwise we could re-read the same entries and send duplicates
            if (log.isDebugEnabled()) {
                log.debug("[{}] [{}] Skipping read for the topic, Due to sending in-progress.",
                        topic.getName(), getSubscriptionName());
            }
            return;
        }
        if (shouldPauseDeliveryForDelayTracker()) {
            if (log.isDebugEnabled()) {
                log.debug("[{}] [{}] Skipping read for the topic, Due to pause delivery for delay tracker.",
                        topic.getName(), getSubscriptionName());
            }
            return;
        }
        if (topic.isTransferring()) {
            // Do not deliver messages for topics that are undergoing transfer, as the acknowledgments would be ignored.
            return;
        }

        // increment the counter for readMoreEntries calls, to track the number of times readMoreEntries is called
        readMoreEntriesCallCount++;

        // remove possible expired messages from redelivery tracker and pending acks
        Position markDeletePosition = cursor.getMarkDeletedPosition();
        if (lastMarkDeletePositionBeforeReadMoreEntries != markDeletePosition) {
            redeliveryMessages.removeAllUpTo(markDeletePosition.getLedgerId(), markDeletePosition.getEntryId());
            for (Consumer consumer : consumerList) {
                consumer.getPendingAcks()
                        .removeAllUpTo(markDeletePosition.getLedgerId(), markDeletePosition.getEntryId());
            }
            lastMarkDeletePositionBeforeReadMoreEntries = markDeletePosition;
        }

        // totalAvailablePermits may be updated by other threads
        int firstAvailableConsumerPermits = getFirstAvailableConsumerPermits();
        int currentTotalAvailablePermits = Math.max(totalAvailablePermits, firstAvailableConsumerPermits);
        if (currentTotalAvailablePermits > 0 && firstAvailableConsumerPermits > 0) {
            Pair<Integer, Long> calculateResult = calculateToRead(currentTotalAvailablePermits);
            int messagesToRead = calculateResult.getLeft();
            long bytesToRead = calculateResult.getRight();

            if (messagesToRead == -1 || bytesToRead == -1) {
                // Skip read as topic/dispatcher has exceed the dispatch rate or previous pending read hasn't complete.
                return;
            }

            Set<Position> messagesToReplayNow =
                    canReplayMessages() ? getMessagesToReplayNow(messagesToRead, bytesToRead) : Collections.emptySet();
            if (!messagesToReplayNow.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("[{}] Schedule replay of {} messages for {} consumers", name,
                            messagesToReplayNow.size(), consumerList.size());
                }
                havePendingReplayRead = true;
                updateMinReplayedPosition();
                Set<? extends Position> deletedMessages = topic.isDelayedDeliveryEnabled()
                        ? asyncReplayEntriesInOrder(messagesToReplayNow)
                        : asyncReplayEntries(messagesToReplayNow);
                // clear already acked positions from replay bucket
                deletedMessages.forEach(position -> redeliveryMessages.remove(position.getLedgerId(),
                        position.getEntryId()));
                // if all the entries are acked-entries and cleared up from redeliveryMessages, try to read
                // next entries as readCompletedEntries-callback was never called
                if ((messagesToReplayNow.size() - deletedMessages.size()) == 0) {
                    havePendingReplayRead = false;
                    readMoreEntriesAsync();
                }
            } else if (BLOCKED_DISPATCHER_ON_UNACKMSG_UPDATER.get(this) == TRUE) {
                if (log.isDebugEnabled()) {
                    log.debug("[{}] Dispatcher read is blocked due to unackMessages {} reached to max {}", name,
                            totalUnackedMessages, topic.getMaxUnackedMessagesOnSubscription());
                }
            } else if (doesntHavePendingRead()) {
                if (!isNormalReadAllowed()) {
                    handleNormalReadNotAllowed();
                    return;
                }
                if (shouldPauseOnAckStatePersist(ReadType.Normal)) {
                    if (log.isDebugEnabled()) {
                        log.debug("[{}] [{}] Skipping read for the topic, Due to blocked on ack state persistent.",
                                topic.getName(), getSubscriptionName());
                    }
                    return;
                }
                if (log.isDebugEnabled()) {
                    log.debug("[{}] Schedule read of {} messages for {} consumers", name, messagesToRead,
                            consumerList.size());
                }
                havePendingRead = true;
                updateMinReplayedPosition();

                messagesToRead = Math.min(messagesToRead, getMaxEntriesReadLimit());
                cursor.asyncReadEntriesWithSkipOrWait(messagesToRead, bytesToRead, this, ReadType.Normal,
                        topic.getMaxReadPosition(), createReadEntriesSkipConditionForNormalRead());
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("[{}] Cannot schedule next read until previous one is done", name);
                }
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("[{}] Consumer buffer is full, pause reading", name);
            }
        }
    }

    protected Predicate<Position> createReadEntriesSkipConditionForNormalRead() {
        Predicate<Position> skipCondition = null;
        // Filter out and skip read delayed messages exist in DelayedDeliveryTracker
        if (delayedDeliveryTracker.isPresent()) {
            final DelayedDeliveryTracker deliveryTracker = delayedDeliveryTracker.get();
            if (deliveryTracker instanceof BucketDelayedDeliveryTracker) {
                skipCondition = position -> ((BucketDelayedDeliveryTracker) deliveryTracker)
                        .containsMessage(position.getLedgerId(), position.getEntryId());
            }
        }
        return skipCondition;
    }

    /**
     * Sets a hard limit on the number of entries to read from the Managed Ledger.
     * Subclasses can override this method to set a different limit.
     * By default, this method does not impose an additional limit.
     *
     * @return the maximum number of entries to read from the Managed Ledger
     */
    protected int getMaxEntriesReadLimit() {
        return Integer.MAX_VALUE;
    }

    /**
     * Checks if there's a pending read operation that hasn't completed yet.
     * This allows to avoid scheduling a new read operation while the previous one is still in progress.
     * @return true if there's a pending read operation
     */
    protected boolean doesntHavePendingRead() {
        return !havePendingRead;
    }

    protected void handleNormalReadNotAllowed() {
        // do nothing
    }

    protected long getReadMoreEntriesCallCount() {
        return readMoreEntriesCallCount;
    }

    /**
     * Controls whether replaying entries is currently enabled.
     * Subclasses can override this method to temporarily disable replaying entries.
     * @return true if replaying entries is currently enabled
     */
    protected boolean canReplayMessages() {
        return true;
    }

    private void updateMinReplayedPosition() {
        minReplayedPosition = getFirstPositionInReplay().orElse(null);
    }

    private boolean shouldPauseOnAckStatePersist(ReadType readType) {
        // Allows new consumers to consume redelivered messages caused by the just-closed consumer.
        if (readType != ReadType.Normal) {
            return false;
        }
        if (!((PersistentTopic) subscription.getTopic()).isDispatcherPauseOnAckStatePersistentEnabled()) {
            return false;
        }
        if (cursor == null) {
            return true;
        }
        return blockedDispatcherOnCursorDataCanNotFullyPersist == TRUE;
    }

    @Override
    protected void reScheduleRead() {
        reScheduleReadInMs(MESSAGE_RATE_BACKOFF_MS);
    }

    protected synchronized void reScheduleReadWithBackoff() {
        reScheduleReadInMs(retryBackoff.next());
    }

    protected void reScheduleReadInMs(long readAfterMs) {
        if (isRescheduleReadInProgress.compareAndSet(false, true)) {
            if (log.isDebugEnabled()) {
                log.debug("[{}] [{}] Reschedule message read in {} ms", topic.getName(), name, readAfterMs);
            }
            Runnable runnable = () -> {
                isRescheduleReadInProgress.set(false);
                readMoreEntries();
            };
            if (readAfterMs > 0) {
                topic.getBrokerService().executor().schedule(runnable, readAfterMs, TimeUnit.MILLISECONDS);
            } else {
                topic.getBrokerService().executor().execute(runnable);
            }
        }
    }

    // left pair is messagesToRead, right pair is bytesToRead
    protected Pair<Integer, Long> calculateToRead(int currentTotalAvailablePermits) {
        int messagesToRead = Math.min(currentTotalAvailablePermits, readBatchSize);
        long bytesToRead = serviceConfig.getDispatcherMaxReadSizeBytes();

        Consumer c = getRandomConsumer();
        // if turn on precise dispatcher flow control, adjust the record to read
        if (c != null && c.isPreciseDispatcherFlowControl()) {
            int avgMessagesPerEntry = Math.max(1, c.getAvgMessagesPerEntry());
            messagesToRead = Math.min(
                    (int) Math.ceil(currentTotalAvailablePermits * 1.0 / avgMessagesPerEntry),
                    readBatchSize);
        }

        if (!isConsumerWritable()) {
            // If the connection is not currently writable, we issue the read request anyway, but for a single
            // message. The intent here is to keep use the request as a notification mechanism while avoiding to
            // read and dispatch a big batch of messages which will need to wait before getting written to the
            // socket.
            messagesToRead = 1;
        }

        // throttle only if: (1) cursor is not active (or flag for throttle-nonBacklogConsumer is enabled) bcz
        // active-cursor reads message from cache rather from bookkeeper (2) if topic has reached message-rate
        // threshold: then schedule the read after MESSAGE_RATE_BACKOFF_MS
        if (serviceConfig.isDispatchThrottlingOnNonBacklogConsumerEnabled() || !cursor.isActive()) {
            if (hasAnyDispatchRateLimiter()) {
                Pair<Integer, Long> calculateToRead =
                        applyRateLimitsToMessagesAndBytesToRead(messagesToRead, bytesToRead);
                if (calculateToRead.getLeft() == -1 && calculateToRead.getRight() == -1) {
                    return calculateToRead;
                } else {
                    messagesToRead = calculateToRead.getLeft();
                    bytesToRead = calculateToRead.getRight();
                }
            }
        }

        if (havePendingReplayRead) {
            if (log.isDebugEnabled()) {
                log.debug("[{}] Skipping replay while awaiting previous read to complete", name);
            }
            return Pair.of(-1, -1L);
        }

        // If messagesToRead is 0 or less, correct it to 1 to prevent IllegalArgumentException
        messagesToRead = Math.max(messagesToRead, 1);
        bytesToRead = Math.max(bytesToRead, 1);
        return Pair.of(messagesToRead, bytesToRead);
    }

    protected Set<? extends Position> asyncReplayEntries(Set<? extends Position> positions) {
        return cursor.asyncReplayEntries(positions, this, ReadType.Replay);
    }

    protected Set<? extends Position> asyncReplayEntriesInOrder(Set<? extends Position> positions) {
        return cursor.asyncReplayEntries(positions, this, ReadType.Replay, true);
    }

    @Override
    public boolean isConsumerConnected() {
        return !consumerList.isEmpty();
    }

    @Override
    public CopyOnWriteArrayList<Consumer> getConsumers() {
        return consumerList;
    }

    @Override
    public synchronized boolean canUnsubscribe(Consumer consumer) {
        return consumerList.size() == 1 && consumerSet.contains(consumer);
    }

    @Override
    public CompletableFuture<Void> close(boolean disconnectConsumers,
                                         Optional<BrokerLookupData> assignedBrokerLookupData) {
        IS_CLOSED_UPDATER.set(this, TRUE);

        Optional<DelayedDeliveryTracker> delayedDeliveryTracker;
        synchronized (this) {
            delayedDeliveryTracker = this.delayedDeliveryTracker;
            this.delayedDeliveryTracker = Optional.empty();
        }

        delayedDeliveryTracker.ifPresent(DelayedDeliveryTracker::close);
        dispatchRateLimiter.ifPresent(DispatchRateLimiter::close);

        return disconnectConsumers
                ? disconnectAllConsumers(false, assignedBrokerLookupData) : CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Void> disconnectAllConsumers(
            boolean isResetCursor, Optional<BrokerLookupData> assignedBrokerLookupData) {
        closeFuture = new CompletableFuture<>();
        if (consumerList.isEmpty()) {
            closeFuture.complete(null);
        } else {
            // Iterator of CopyOnWriteArrayList uses the internal array to do the for-each, and CopyOnWriteArrayList
            // will create a new internal array when adding/removing a new item. So remove items in the for-each
            // block is safety when the for-each and add/remove are using a same lock.
            consumerList.forEach(consumer -> consumer.disconnect(isResetCursor, assignedBrokerLookupData));
            cancelPendingRead();
        }
        return closeFuture;
    }

    @Override
    protected void cancelPendingRead() {
        if (havePendingRead && cursor.cancelPendingReadRequest()) {
            havePendingRead = false;
        }
    }

    @Override
    public CompletableFuture<Void> disconnectActiveConsumers(boolean isResetCursor) {
        return disconnectAllConsumers(isResetCursor);
    }

    @Override
    public synchronized void resetCloseFuture() {
        closeFuture = null;
    }

    @Override
    public void reset() {
        resetCloseFuture();
        IS_CLOSED_UPDATER.set(this, FALSE);
    }

    @Override
    public SubType getType() {
        return SubType.Shared;
    }

    @Override
    public final synchronized void readEntriesComplete(List<Entry> entries, Object ctx) {
        ReadType readType = (ReadType) ctx;
        if (readType == ReadType.Normal) {
            havePendingRead = false;
        } else {
            havePendingReplayRead = false;
        }

        if (readBatchSize < serviceConfig.getDispatcherMaxReadBatchSize()) {
            int newReadBatchSize = Math.min(readBatchSize * 2, serviceConfig.getDispatcherMaxReadBatchSize());
            if (log.isDebugEnabled()) {
                log.debug("[{}] Increasing read batch size from {} to {}", name, readBatchSize, newReadBatchSize);
            }

            readBatchSize = newReadBatchSize;
        }

        readFailureBackoff.reduceToHalf();

        if (shouldRewindBeforeReadingOrReplaying && readType == ReadType.Normal) {
            // All consumers got disconnected before the completion of the read operation
            entries.forEach(Entry::release);
            cursor.rewind();
            shouldRewindBeforeReadingOrReplaying = false;
            readMoreEntriesAsync();
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("[{}] Distributing {} messages to {} consumers", name, entries.size(), consumerList.size());
        }

        long totalBytesSize = entries.stream().mapToLong(Entry::getLength).sum();
        updatePendingBytesToDispatch(totalBytesSize);

        // dispatch messages to a separate thread, but still in order for this subscription
        // sendMessagesToConsumers is responsible for running broker-side filters
        // that may be quite expensive
        if (serviceConfig.isDispatcherDispatchMessagesInSubscriptionThread()) {
            // setting sendInProgress here, because sendMessagesToConsumers will be executed
            // in a separate thread, and we want to prevent more reads
            acquireSendInProgress();
            dispatchMessagesThread.execute(() -> {
                handleSendingMessagesAndReadingMore(readType, entries, false, totalBytesSize);
            });
        } else {
            handleSendingMessagesAndReadingMore(readType, entries, true, totalBytesSize);
        }
    }

    private synchronized void handleSendingMessagesAndReadingMore(ReadType readType, List<Entry> entries,
                                                                  boolean needAcquireSendInProgress,
                                                                  long totalBytesSize) {
        boolean triggerReadingMore = sendMessagesToConsumers(readType, entries, needAcquireSendInProgress);
        int entriesProcessed = lastNumberOfEntriesProcessed;
        updatePendingBytesToDispatch(-totalBytesSize);
        boolean canReadMoreImmediately = false;
        if (entriesProcessed > 0 || skipNextBackoff) {
            // Reset the backoff when messages were processed
            retryBackoff.reset();
            // Reset the possible flag to skip the backoff delay
            skipNextBackoff = false;
            canReadMoreImmediately = true;
        }
        if (triggerReadingMore) {
            if (canReadMoreImmediately) {
                // Call readMoreEntries in the same thread to trigger the next read
                readMoreEntries();
            } else {
                // reschedule a new read with an increasing backoff delay
                reScheduleReadWithBackoff();
            }
        }
    }

    protected synchronized void acquireSendInProgress() {
        sendInProgress = true;
    }

    protected synchronized void releaseSendInProgress() {
        sendInProgress = false;
    }

    protected synchronized boolean isSendInProgress() {
        return sendInProgress;
    }

    protected final synchronized boolean sendMessagesToConsumers(ReadType readType, List<Entry> entries,
                                                                 boolean needAcquireSendInProgress) {
        if (needAcquireSendInProgress) {
            acquireSendInProgress();
        }
        try {
            return trySendMessagesToConsumers(readType, entries);
        } finally {
            releaseSendInProgress();
        }
    }

    /**
     * Dispatch the messages to the Consumers.
     * @return true if you want to trigger a new read.
     * This method is overridden by other classes, please take a look to other implementations
     * if you need to change it.
     */
    protected synchronized boolean trySendMessagesToConsumers(ReadType readType, List<Entry> entries) {
        if (needTrimAckedMessages()) {
            cursor.trimDeletedEntries(entries);
        }
        lastNumberOfEntriesProcessed = 0;

        int entriesToDispatch = entries.size();
        // Trigger read more messages
        if (entriesToDispatch == 0) {
            return true;
        }
        final MessageMetadata[] metadataArray = new MessageMetadata[entries.size()];
        int remainingMessages = 0;
        boolean hasChunk = false;
        for (int i = 0; i < metadataArray.length; i++) {
            Entry entry = entries.get(i);
            MessageMetadata metadata;
            if (entry instanceof EntryAndMetadata) {
                metadata = ((EntryAndMetadata) entry).getMetadata();
            } else {
                metadata = Commands.peekAndCopyMessageMetadata(entry.getDataBuffer(), subscription.toString(), -1);
                // cache the metadata in the entry with EntryAndMetadata for later use to avoid re-parsing the metadata
                // and to carry the metadata and calculated stickyKeyHash with the entry
                entries.set(i, EntryAndMetadata.create(entry, metadata));
            }
            if (metadata != null) {
                remainingMessages += metadata.getNumMessagesInBatch();
                if (!hasChunk && metadata.hasUuid()) {
                    hasChunk = true;
                }
            }
            metadataArray[i] = metadata;
        }
        if (hasChunk) {
            return sendChunkedMessagesToConsumers(readType, entries, metadataArray);
        }

        int start = 0;
        long totalMessagesSent = 0;
        long totalBytesSent = 0;
        long totalEntries = 0;
        long totalEntriesProcessed = 0;
        int avgBatchSizePerMsg = remainingMessages > 0 ? Math.max(remainingMessages / entries.size(), 1) : 1;

        // If the dispatcher is closed, firstAvailableConsumerPermits will be 0, which skips dispatching the
        // messages.
        while (entriesToDispatch > 0 && isAtleastOneConsumerAvailable()) {
            Consumer c = getNextConsumer();
            if (c == null) {
                // Do nothing, cursor will be rewind at reconnection
                log.info("[{}] rewind because no available consumer found from total {}", name, consumerList.size());
                entries.subList(start, entries.size()).forEach(Entry::release);
                cursor.rewind();
                lastNumberOfEntriesProcessed = (int) totalEntriesProcessed;
                return false;
            }
            // round-robin dispatch batch size for this consumer
            int availablePermits = c.isWritable() ? c.getAvailablePermits() : 1;
            if (log.isDebugEnabled() && !c.isWritable()) {
                log.debug("[{}-{}] consumer is not writable. dispatching only 1 message to {}; "
                                + "availablePermits are {}", topic.getName(), name,
                        c, c.getAvailablePermits());
            }

            int maxEntriesInThisBatch = getMaxEntriesInThisBatch(
                    remainingMessages, c.getMaxUnackedMessages(), c.getUnackedMessages(), avgBatchSizePerMsg,
                    availablePermits, serviceConfig.getDispatcherMaxRoundRobinBatchSize()
            );
            int end = Math.min(start + maxEntriesInThisBatch, entries.size());
            List<Entry> entriesForThisConsumer = entries.subList(start, end);

            // remove positions first from replay list first : sendMessages recycles entries
            if (readType == ReadType.Replay) {
                entriesForThisConsumer.forEach(entry -> {
                    redeliveryMessages.remove(entry.getLedgerId(), entry.getEntryId());
                });
            }

            SendMessageInfo sendMessageInfo = SendMessageInfo.getThreadLocal();

            EntryBatchSizes batchSizes = EntryBatchSizes.get(entriesForThisConsumer.size());
            EntryBatchIndexesAcks batchIndexesAcks = EntryBatchIndexesAcks.get(entriesForThisConsumer.size());

            totalEntries += filterEntriesForConsumer(metadataArray, start,
                    entriesForThisConsumer, batchSizes, sendMessageInfo, batchIndexesAcks, cursor,
                    readType == ReadType.Replay, c);
            totalEntriesProcessed += entriesForThisConsumer.size();

            c.sendMessages(entriesForThisConsumer, batchSizes, batchIndexesAcks, sendMessageInfo.getTotalMessages(),
                    sendMessageInfo.getTotalBytes(), sendMessageInfo.getTotalChunkedMessages(), redeliveryTracker);

            int msgSent = sendMessageInfo.getTotalMessages();
            remainingMessages -= msgSent;
            start += maxEntriesInThisBatch;
            entriesToDispatch -= maxEntriesInThisBatch;
            TOTAL_AVAILABLE_PERMITS_UPDATER.addAndGet(this,
                    -(msgSent - batchIndexesAcks.getTotalAckedIndexCount()));
            if (log.isDebugEnabled()) {
                log.debug("[{}] Added -({} minus {}) permits to TOTAL_AVAILABLE_PERMITS_UPDATER in "
                                + "PersistentDispatcherMultipleConsumers",
                        name, msgSent, batchIndexesAcks.getTotalAckedIndexCount());
            }
            totalMessagesSent += sendMessageInfo.getTotalMessages();
            totalBytesSent += sendMessageInfo.getTotalBytes();
        }

        lastNumberOfEntriesProcessed = (int) totalEntriesProcessed;
        acquirePermitsForDeliveredMessages(topic, cursor, totalEntries, totalMessagesSent, totalBytesSent);

        if (entriesToDispatch > 0) {
            if (log.isDebugEnabled()) {
                log.debug("[{}] No consumers found with available permits, storing {} positions for later replay", name,
                        entries.size() - start);
            }
            entries.subList(start, entries.size()).forEach(this::addEntryToReplay);
        }

        return true;
    }


    @VisibleForTesting
    static int getMaxEntriesInThisBatch(int remainingMessages,
                                        int maxUnackedMessages,
                                        int unackedMessages,
                                        int avgBatchSizePerMsg,
                                        int availablePermits,
                                        int dispatcherMaxRoundRobinBatchSize) {
        int maxMessagesInThisBatch = Math.min(remainingMessages, availablePermits);
        if (maxUnackedMessages > 0) {
            // Calculate the maximum number of additional unacked messages allowed
            int maxAdditionalUnackedMessages = Math.max(maxUnackedMessages - unackedMessages, 0);
            maxMessagesInThisBatch = Math.min(maxMessagesInThisBatch, maxAdditionalUnackedMessages);
        }
        int maxEntriesInThisBatch = Math.min(dispatcherMaxRoundRobinBatchSize,
                // use the average batch size per message to calculate the number of entries to
                // dispatch. round up to the next integer without using floating point arithmetic.
                (maxMessagesInThisBatch + avgBatchSizePerMsg - 1) / avgBatchSizePerMsg);
        // pick at least one entry to dispatch
        maxEntriesInThisBatch = Math.max(maxEntriesInThisBatch, 1);
        return maxEntriesInThisBatch;
    }

    protected boolean addEntryToReplay(Entry entry) {
        long stickyKeyHash = getStickyKeyHash(entry);
        boolean addedToReplay = addMessageToReplay(entry.getLedgerId(), entry.getEntryId(), stickyKeyHash);
        entry.release();
        return addedToReplay;
    }

    private boolean sendChunkedMessagesToConsumers(ReadType readType,
                                                   List<Entry> entries,
                                                   MessageMetadata[] metadataArray) {
        final List<EntryAndMetadata> originalEntryAndMetadataList = new ArrayList<>(metadataArray.length);
        for (int i = 0; i < metadataArray.length; i++) {
            originalEntryAndMetadataList.add(EntryAndMetadata.create(entries.get(i), metadataArray[i]));
        }

        final Map<Consumer, List<EntryAndMetadata>> assignResult =
                assignor.assign(originalEntryAndMetadataList, consumerList.size());
        long totalMessagesSent = 0;
        long totalBytesSent = 0;
        long totalEntries = 0;
        long totalEntriesProcessed = 0;
        final AtomicInteger numConsumers = new AtomicInteger(assignResult.size());
        boolean notifyAddedToReplay = false;
        for (Map.Entry<Consumer, List<EntryAndMetadata>> current : assignResult.entrySet()) {
            final Consumer consumer = current.getKey();
            final List<EntryAndMetadata> entryAndMetadataList = current.getValue();
            final int messagesForC = Math.min(consumer.getAvailablePermits(), entryAndMetadataList.size());
            if (log.isDebugEnabled()) {
                log.debug("[{}] select consumer {} with messages num {}, read type is {}",
                        name, consumer.consumerName(), messagesForC, readType);
            }
            if (messagesForC < entryAndMetadataList.size()) {
                for (int i = messagesForC; i < entryAndMetadataList.size(); i++) {
                    final EntryAndMetadata entry = entryAndMetadataList.get(i);
                    notifyAddedToReplay |= addEntryToReplay(entry);
                    entryAndMetadataList.set(i, null);
                }
            }
            if (messagesForC == 0) {
                numConsumers.decrementAndGet();
                continue;
            }
            if (readType == ReadType.Replay) {
                entryAndMetadataList.stream().limit(messagesForC)
                        .forEach(e -> redeliveryMessages.remove(e.getLedgerId(), e.getEntryId()));
            }
            final SendMessageInfo sendMessageInfo = SendMessageInfo.getThreadLocal();
            final EntryBatchSizes batchSizes = EntryBatchSizes.get(messagesForC);
            final EntryBatchIndexesAcks batchIndexesAcks = EntryBatchIndexesAcks.get(messagesForC);

            totalEntries += filterEntriesForConsumer(entryAndMetadataList, batchSizes, sendMessageInfo,
                    batchIndexesAcks, cursor, readType == ReadType.Replay, consumer);
            totalEntriesProcessed += entryAndMetadataList.size();
            consumer.sendMessages(entryAndMetadataList, batchSizes, batchIndexesAcks,
                    sendMessageInfo.getTotalMessages(), sendMessageInfo.getTotalBytes(),
                    sendMessageInfo.getTotalChunkedMessages(), getRedeliveryTracker()
            ).addListener(future -> {
                if (future.isDone() && numConsumers.decrementAndGet() == 0) {
                    readMoreEntriesAsync();
                }
            });

            TOTAL_AVAILABLE_PERMITS_UPDATER.getAndAdd(this,
                    -(sendMessageInfo.getTotalMessages() - batchIndexesAcks.getTotalAckedIndexCount()));
            totalMessagesSent += sendMessageInfo.getTotalMessages();
            totalBytesSent += sendMessageInfo.getTotalBytes();
        }

        lastNumberOfEntriesProcessed = (int) totalEntriesProcessed;
        acquirePermitsForDeliveredMessages(topic, cursor, totalEntries, totalMessagesSent, totalBytesSent);

        // trigger a new readMoreEntries() call
        return numConsumers.get() == 0 || notifyAddedToReplay;
    }

    @Override
    public synchronized void readEntriesFailed(ManagedLedgerException exception, Object ctx) {

        ReadType readType = (ReadType) ctx;
        long waitTimeMillis = readFailureBackoff.next();

        // Do not keep reading more entries if the cursor is already closed.
        if (exception instanceof ManagedLedgerException.CursorAlreadyClosedException) {
            if (log.isDebugEnabled()) {
                log.debug("[{}] Cursor is already closed, skipping read more entries", cursor.getName());
            }
            // Set the wait time to -1 to avoid rescheduling the read.
            waitTimeMillis = -1;
        } else if (exception instanceof NoMoreEntriesToReadException) {
            if (cursor.getNumberOfEntriesInBacklog(false) == 0) {
                // Topic has been terminated and there are no more entries to read
                // Notify the consumer only if all the messages were already acknowledged
                checkAndApplyReachedEndOfTopicOrTopicMigration(consumerList);
            }
        } else if (exception.getCause() instanceof TransactionBufferException.TransactionNotSealedException
                || exception.getCause() instanceof ManagedLedgerException.OffloadReadHandleClosedException) {
            waitTimeMillis = 1;
            if (log.isDebugEnabled()) {
                log.debug("[{}] Error reading transaction entries : {}, Read Type {} - Retrying to read in {} seconds",
                        name, exception.getMessage(), readType, waitTimeMillis / 1000.0);
            }
        } else if (!(exception instanceof TooManyRequestsException)) {
            log.error("[{}] Error reading entries at {} : {}, Read Type {} - Retrying to read in {} seconds", name,
                    cursor.getReadPosition(), exception.getMessage(), readType, waitTimeMillis / 1000.0);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("[{}] Error reading entries at {} : {}, Read Type {} - Retrying to read in {} seconds", name,
                        cursor.getReadPosition(), exception.getMessage(), readType, waitTimeMillis / 1000.0);
            }
        }

        if (shouldRewindBeforeReadingOrReplaying) {
            shouldRewindBeforeReadingOrReplaying = false;
            cursor.rewind();
        }

        if (readType == ReadType.Normal) {
            havePendingRead = false;
        } else {
            havePendingReplayRead = false;
            if (exception instanceof ManagedLedgerException.InvalidReplayPositionException) {
                Position markDeletePosition = cursor.getMarkDeletedPosition();
                redeliveryMessages.removeAllUpTo(markDeletePosition.getLedgerId(), markDeletePosition.getEntryId());
            }
        }

        readBatchSize = serviceConfig.getDispatcherMinReadBatchSize();
        // Skip read if the waitTimeMillis is a nagetive value.
        if (waitTimeMillis >= 0) {
            scheduleReadEntriesWithDelay(exception, readType, waitTimeMillis);
        }
    }

    @VisibleForTesting
    void scheduleReadEntriesWithDelay(Exception e, ReadType readType, long waitTimeMillis) {
        topic.getBrokerService().executor().schedule(() -> {
            synchronized (PersistentDispatcherMultipleConsumers.this) {
                // If it's a replay read we need to retry even if there's already
                // another scheduled read, otherwise we'd be stuck until
                // more messages are published.
                if (!havePendingRead || readType == ReadType.Replay) {
                    log.info("[{}] Retrying read operation", name);
                    readMoreEntries();
                } else {
                    log.info("[{}] Skipping read retry: havePendingRead {}", name, havePendingRead, e);
                }
            }
        }, waitTimeMillis, TimeUnit.MILLISECONDS);
    }

    private boolean needTrimAckedMessages() {
        if (lastIndividualDeletedRangeFromCursorRecovery == null) {
            return false;
        } else {
            return lastIndividualDeletedRangeFromCursorRecovery.upperEndpoint()
                    .compareTo(cursor.getReadPosition()) > 0;
        }
    }

    /**
     * returns true only if {@link AbstractDispatcherMultipleConsumers#consumerList}
     * has atleast one unblocked consumer and have available permits.
     *
     * @return
     */
    protected boolean isAtleastOneConsumerAvailable() {
        return getFirstAvailableConsumerPermits() > 0;
    }

    protected int getFirstAvailableConsumerPermits() {
        if (consumerList.isEmpty() || IS_CLOSED_UPDATER.get(this) == TRUE) {
            // abort read if no consumers are connected or if disconnect is initiated
            return 0;
        }
        for (Consumer consumer : consumerList) {
            if (consumer != null && !consumer.isBlocked() && consumer.cnx().isActive()) {
                int availablePermits = consumer.getAvailablePermits();
                if (availablePermits > 0) {
                    return availablePermits;
                }
            }
        }
        return 0;
    }

    private boolean isConsumerWritable() {
        for (Consumer consumer : consumerList) {
            if (consumer.isWritable()) {
                return true;
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[{}-{}] consumer is not writable", topic.getName(), name);
        }
        return false;
    }

    @Override
    public boolean isConsumerAvailable(Consumer consumer) {
        return consumer != null && !consumer.isBlocked() && consumer.cnx().isActive()
                && consumer.getAvailablePermits() > 0;
    }

    @Override
    public synchronized void redeliverUnacknowledgedMessages(Consumer consumer, long consumerEpoch) {
        if (log.isDebugEnabled()) {
            log.debug("[{}-{}] Redelivering unacknowledged messages for consumer {}", name, consumer,
                    redeliveryMessages);
        }
        MutableBoolean addedToReplay = new MutableBoolean(false);
        consumer.getPendingAcks().forEach((ledgerId, entryId, batchSize, stickyKeyHash) -> {
            if (addMessageToReplay(ledgerId, entryId, stickyKeyHash)) {
                redeliveryTracker.incrementAndGetRedeliveryCount((PositionFactory.create(ledgerId, entryId)));
                addedToReplay.setTrue();
            }
        });
        if (addedToReplay.booleanValue()) {
            notifyRedeliveryMessageAdded();
        }
    }

    @Override
    public synchronized void redeliverUnacknowledgedMessages(Consumer consumer, List<Position> positions) {
        if (log.isDebugEnabled()) {
            log.debug("[{}-{}] Redelivering unacknowledged messages for consumer {}", name, consumer, positions);
        }
        MutableBoolean addedToReplay = new MutableBoolean(false);
        positions.forEach(position -> {
            // TODO: We want to pass a sticky key hash as a third argument to guarantee the order of the messages
            // on Key_Shared subscription, but it's difficult to get the sticky key here
            if (addMessageToReplay(position.getLedgerId(), position.getEntryId())) {
                redeliveryTracker.incrementAndGetRedeliveryCount(position);
                addedToReplay.setTrue();
            }
        });
        if (addedToReplay.booleanValue()) {
            notifyRedeliveryMessageAdded();
        }
    }

    @Override
    public void addUnAckedMessages(int numberOfMessages) {
        int maxUnackedMessages = topic.getMaxUnackedMessagesOnSubscription();
        // don't block dispatching if maxUnackedMessages = 0
        if (maxUnackedMessages <= 0 && blockedDispatcherOnUnackedMsgs == TRUE
                && BLOCKED_DISPATCHER_ON_UNACKMSG_UPDATER.compareAndSet(this, TRUE, FALSE)) {
            log.info("[{}] Dispatcher is unblocked, since maxUnackedMessagesPerSubscription=0", name);
            readMoreEntriesAsync();
        }

        int unAckedMessages = TOTAL_UNACKED_MESSAGES_UPDATER.addAndGet(this, numberOfMessages);
        if (unAckedMessages >= maxUnackedMessages && maxUnackedMessages > 0
                && BLOCKED_DISPATCHER_ON_UNACKMSG_UPDATER.compareAndSet(this, FALSE, TRUE)) {
            // block dispatcher if it reaches maxUnAckMsg limit
            log.debug("[{}] Dispatcher is blocked due to unackMessages {} reached to max {}", name,
                    unAckedMessages, maxUnackedMessages);
        } else if (topic.getBrokerService().isBrokerDispatchingBlocked()
                && blockedDispatcherOnUnackedMsgs == TRUE) {
            // unblock dispatcher: if dispatcher is blocked due to broker-unackMsg limit and if it ack back enough
            // messages
            if (totalUnackedMessages < (topic.getBrokerService().maxUnackedMsgsPerDispatcher / 2)) {
                if (BLOCKED_DISPATCHER_ON_UNACKMSG_UPDATER.compareAndSet(this, TRUE, FALSE)) {
                    // it removes dispatcher from blocked list and unblocks dispatcher by scheduling read
                    topic.getBrokerService().unblockDispatchersOnUnAckMessages(Lists.newArrayList(this));
                }
            }
        } else if (blockedDispatcherOnUnackedMsgs == TRUE && unAckedMessages < maxUnackedMessages / 2) {
            // unblock dispatcher if it acks back enough messages
            if (BLOCKED_DISPATCHER_ON_UNACKMSG_UPDATER.compareAndSet(this, TRUE, FALSE)) {
                log.debug("[{}] Dispatcher is unblocked", name);
                readMoreEntriesAsync();
            }
        }
        // increment broker-level count
        topic.getBrokerService().addUnAckedMessages(this, numberOfMessages);
    }

    @Override
    public void afterAckMessages(Throwable exOfDeletion, Object ctxOfDeletion) {
        boolean unPaused = blockedDispatcherOnCursorDataCanNotFullyPersist == FALSE;
        // Trigger a new read if needed.
        boolean shouldPauseNow = !checkAndResumeIfPaused();
        // Switch stat to "paused" if needed.
        if (unPaused && shouldPauseNow) {
            if (!BLOCKED_DISPATCHER_ON_CURSOR_DATA_CAN_NOT_FULLY_PERSIST_UPDATER
                    .compareAndSet(this, FALSE, TRUE)) {
                // Retry due to conflict update.
                afterAckMessages(exOfDeletion, ctxOfDeletion);
            }
        }
    }

    @Override
    public boolean checkAndResumeIfPaused() {
        boolean paused = blockedDispatcherOnCursorDataCanNotFullyPersist == TRUE;
        // Calling "cursor.isCursorDataFullyPersistable()" will loop the collection "individualDeletedMessages". It is
        // not a light method.
        // If never enabled "dispatcherPauseOnAckStatePersistentEnabled", skip the following checks to improve
        // performance.
        if (!paused && !topic.isDispatcherPauseOnAckStatePersistentEnabled()){
            // "true" means no need to pause.
            return true;
        }
        // Enabled "dispatcherPauseOnAckStatePersistentEnabled" before.
        boolean shouldPauseNow = !cursor.isCursorDataFullyPersistable()
                && topic.isDispatcherPauseOnAckStatePersistentEnabled();
        // No need to change.
        if (paused == shouldPauseNow) {
            return !shouldPauseNow;
        }
        // Should change to "un-pause".
        if (paused && !shouldPauseNow) {
            // If there was no previous pause due to cursor data is too large to persist, we don't need to manually
            // trigger a new read. This can avoid too many CPU circles.
            if (BLOCKED_DISPATCHER_ON_CURSOR_DATA_CAN_NOT_FULLY_PERSIST_UPDATER.compareAndSet(this, TRUE, FALSE)) {
                readMoreEntriesAsync();
            } else {
                // Retry due to conflict update.
                checkAndResumeIfPaused();
            }
        }
        return !shouldPauseNow;
    }

    public boolean isBlockedDispatcherOnUnackedMsgs() {
        return blockedDispatcherOnUnackedMsgs == TRUE;
    }

    public void blockDispatcherOnUnackedMsgs() {
        blockedDispatcherOnUnackedMsgs = TRUE;
    }

    @Override
    public void unBlockDispatcherOnUnackedMsgs() {
        blockedDispatcherOnUnackedMsgs = FALSE;
    }

    public int getTotalUnackedMessages() {
        return totalUnackedMessages;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public RedeliveryTracker getRedeliveryTracker() {
        return redeliveryTracker;
    }

    @Override
    public Optional<DispatchRateLimiter> getRateLimiter() {
        return dispatchRateLimiter;
    }

    @Override
    public boolean initializeDispatchRateLimiterIfNeeded() {
        if (!dispatchRateLimiter.isPresent() && DispatchRateLimiter.isDispatchRateEnabled(
                topic.getSubscriptionDispatchRate(getSubscriptionName()))) {
            this.dispatchRateLimiter =
                    Optional.of(topic.getBrokerService().getDispatchRateLimiterFactory()
                            .createSubscriptionDispatchRateLimiter(topic, getSubscriptionName()));
            return true;
        }
        return false;
    }

    @Override
    public boolean trackDelayedDelivery(long ledgerId, long entryId, MessageMetadata msgMetadata) {
        if (!topic.isDelayedDeliveryEnabled()) {
            // If broker has the feature disabled, always deliver messages immediately
            return false;
        }

        synchronized (this) {
            if (delayedDeliveryTracker.isEmpty()) {
                if (!msgMetadata.hasDeliverAtTime()) {
                    // No need to initialize the tracker here
                    return false;
                }

                // Initialize the tracker the first time we need to use it
                delayedDeliveryTracker = Optional.of(
                        topic.getBrokerService().getDelayedDeliveryTrackerFactory().newTracker(this));
            }

            delayedDeliveryTracker.get().resetTickTime(topic.getDelayedDeliveryTickTimeMillis());

            long deliverAtTime = msgMetadata.hasDeliverAtTime() ? msgMetadata.getDeliverAtTime() : -1L;
            return delayedDeliveryTracker.get().addMessage(ledgerId, entryId, deliverAtTime);
        }
    }

    protected synchronized NavigableSet<Position> getMessagesToReplayNow(int maxMessagesToRead, long bytesToRead) {
        int cappedMaxMessagesToRead = cursor.applyMaxSizeCap(maxMessagesToRead, bytesToRead);
        if (cappedMaxMessagesToRead < maxMessagesToRead && log.isDebugEnabled()) {
            log.debug("[{}] Capped max messages to read from redelivery list to {} (max was {})",
                    name, cappedMaxMessagesToRead, maxMessagesToRead);
        }
        if (delayedDeliveryTracker.isPresent() && delayedDeliveryTracker.get().hasMessageAvailable()) {
            delayedDeliveryTracker.get().resetTickTime(topic.getDelayedDeliveryTickTimeMillis());
            NavigableSet<Position> messagesAvailableNow =
                    delayedDeliveryTracker.get().getScheduledMessages(cappedMaxMessagesToRead);
            messagesAvailableNow.forEach(p -> redeliveryMessages.add(p.getLedgerId(), p.getEntryId()));
        }
        if (!redeliveryMessages.isEmpty()) {
            return redeliveryMessages.getMessagesToReplayNow(cappedMaxMessagesToRead, createFilterForReplay());
        } else {
            return Collections.emptyNavigableSet();
        }
    }

    protected Optional<Position> getFirstPositionInReplay() {
        return redeliveryMessages.getFirstPositionInReplay();
    }

    /**
     * Creates a stateful filter for filtering replay positions.
     * This is only used for Key_Shared mode to skip replaying certain entries.
     * Filter out the entries that will be discarded due to the order guarantee mechanism of Key_Shared mode.
     * This method is in order to avoid the scenario below:
     * - Get positions from the Replay queue.
     * - Read entries from BK.
     * - The order guarantee mechanism of Key_Shared mode filtered out all the entries.
     * - Delivery non entry to the client, but we did a BK read.
     */
    protected Predicate<Position> createFilterForReplay() {
        // pick all positions from the replay
        return position -> true;
    }

    /**
     * Checks if the dispatcher is allowed to read messages from the cursor.
     */
    protected boolean isNormalReadAllowed() {
        return true;
    }



    protected synchronized boolean shouldPauseDeliveryForDelayTracker() {
        return delayedDeliveryTracker.isPresent() && delayedDeliveryTracker.get().shouldPauseAllDeliveries();
    }

    @Override
    public long getNumberOfDelayedMessages() {
        return delayedDeliveryTracker.map(DelayedDeliveryTracker::getNumberOfDelayedMessages).orElse(0L);
    }

    @Override
    public CompletableFuture<Void> clearDelayedMessages() {
        if (!topic.isDelayedDeliveryEnabled()) {
            return CompletableFuture.completedFuture(null);
        }

        if (delayedDeliveryTracker.isPresent()) {
            return this.delayedDeliveryTracker.get().clear();
        } else {
            DelayedDeliveryTrackerFactory delayedDeliveryTrackerFactory =
                    topic.getBrokerService().getDelayedDeliveryTrackerFactory();
            if (delayedDeliveryTrackerFactory instanceof BucketDelayedDeliveryTrackerFactory
                    bucketDelayedDeliveryTrackerFactory) {
                return bucketDelayedDeliveryTrackerFactory.cleanResidualSnapshots(cursor);
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    @Override
    public void cursorIsReset() {
        if (this.lastIndividualDeletedRangeFromCursorRecovery != null) {
            this.lastIndividualDeletedRangeFromCursorRecovery = null;
        }
    }

    protected boolean addMessageToReplay(long ledgerId, long entryId, long stickyKeyHash) {
        if (checkIfMessageIsUnacked(ledgerId, entryId)) {
            if (log.isDebugEnabled()) {
                log.debug("[{}] Adding message to replay for {}:{} hash: {}", name, ledgerId, entryId, stickyKeyHash);
            }
            redeliveryMessages.add(ledgerId, entryId, stickyKeyHash);
            return true;
        } else {
            return false;
        }
    }

    /**
     * Notify the dispatcher that a message has been added to the redelivery list.
     */
    private void notifyRedeliveryMessageAdded() {
        readMoreEntriesAsync();
    }

    protected boolean addMessageToReplay(long ledgerId, long entryId) {
        if (checkIfMessageIsUnacked(ledgerId, entryId)) {
            redeliveryMessages.add(ledgerId, entryId);
            return true;
        } else {
            return false;
        }
    }

    private boolean checkIfMessageIsUnacked(long ledgerId, long entryId) {
        Position markDeletePosition = cursor.getMarkDeletedPosition();
        return (markDeletePosition == null || ledgerId > markDeletePosition.getLedgerId()
                || (ledgerId == markDeletePosition.getLedgerId() && entryId > markDeletePosition.getEntryId()));
    }

    @Override
    public boolean checkAndUnblockIfStuck() {
        if (cursor.checkAndUpdateReadPositionChanged()) {
            return false;
        }
        // consider dispatch is stuck if : dispatcher has backlog, available-permits and there is no pending read
        if (isAtleastOneConsumerAvailable() && !havePendingReplayRead && !havePendingRead
                && cursor.getNumberOfEntriesInBacklog(false) > 0) {
            log.warn("{}-{} Dispatcher is stuck and unblocking by issuing reads", topic.getName(), name);
            readMoreEntriesAsync();
            return true;
        }
        return false;
    }

    public PersistentTopic getTopic() {
        return topic;
    }


    public long getDelayedTrackerMemoryUsage() {
        return delayedDeliveryTracker.map(DelayedDeliveryTracker::getBufferMemoryUsage).orElse(0L);
    }

    public Map<String, TopicMetricBean> getBucketDelayedIndexStats() {
        if (delayedDeliveryTracker.isEmpty()) {
            return Collections.emptyMap();
        }

        if (delayedDeliveryTracker.get() instanceof BucketDelayedDeliveryTracker) {
            return ((BucketDelayedDeliveryTracker) delayedDeliveryTracker.get()).genTopicMetricMap();
        }

        return Collections.emptyMap();
    }

    @Override
    public boolean isClassic() {
        return false;
    }

    public ManagedCursor getCursor() {
        return cursor;
    }

    protected int getStickyKeyHash(Entry entry) {
        // There's no need to calculate the hash for Shared subscription
        return STICKY_KEY_HASH_NOT_SET;
    }

    public Subscription getSubscription() {
        return subscription;
    }

    public long getNumberOfMessagesInReplay() {
        return redeliveryMessages.size();
    }

    @Override
    public boolean isHavePendingRead() {
        return havePendingRead;
    }

    @Override
    public boolean isHavePendingReplayRead() {
        return havePendingReplayRead;
    }

    private static final Logger log = LoggerFactory.getLogger(PersistentDispatcherMultipleConsumers.class);
}
