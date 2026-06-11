package com.smartfreight.inbox.repository;

import com.smartfreight.inbox.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Locks and returns the next batch of unpublished events in insertion order.
     *
     * {@code FOR UPDATE SKIP LOCKED} makes the poll safe to run from several
     * application instances at once: each instance grabs a disjoint set of rows
     * instead of blocking on (or double-publishing) rows another instance holds.
     * Single-instance today, but this removes the need for ShedLock here later.
     *
     * Must be called inside an active transaction — row locks live until commit.
     *
     * @param batchSize maximum number of events to lock
     * @return pending events ordered by id (publication order)
     */
    @Query(value = """
            SELECT * FROM outbox_event
            WHERE published_at IS NULL
            ORDER BY id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockUnpublishedBatch(@Param("batchSize") int batchSize);

    /**
     * Number of events waiting to be relayed. Exposed as a Prometheus gauge —
     * a growing value means the relay is down or the broker is rejecting publishes.
     * Covered by the partial index, so the count is O(pending), not O(history).
     */
    long countByPublishedAtIsNull();

    /**
     * Creation time of the OLDEST unpublished event, or null when the outbox is
     * drained. Exposed as an age gauge: pending count alone cannot distinguish
     * "steady small backlog" from "one event stuck for an hour".
     */
    @Query("SELECT MIN(e.createdAt) FROM OutboxEvent e WHERE e.publishedAt IS NULL")
    java.time.LocalDateTime findOldestUnpublishedCreatedAt();
}
