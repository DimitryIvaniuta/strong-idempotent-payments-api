package com.github.dimitryivaniuta.gateway.payments.repo;

import com.github.dimitryivaniuta.gateway.payments.domain.OutboxEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link OutboxEvent}.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    /**
     * Locks the next batch of outbox events ready to be published.
     *
     * <p>Uses Postgres {@code FOR UPDATE SKIP LOCKED} so multiple publisher instances can run safely:
     * each event is processed by at most one instance at a time.</p>
     *
     * @param statuses statuses to fetch (typically NEW, RETRY)
     * @param now current timestamp
     * @param limit batch size
     * @return locked batch
     */
    @Query(value = """
            select *
            from outbox_events
            where status in (:statuses)
              and (next_attempt_at is null or next_attempt_at <= :now)
            order by created_at
            for update skip locked
            limit :limit
            """, nativeQuery = true)
    List<OutboxEvent> lockNextBatchForPublish(
            @Param("statuses") List<String> statuses,
            @Param("now") Instant now,
            @Param("limit") int limit
    );
}
