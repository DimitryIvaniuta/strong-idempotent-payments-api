package com.github.dimitryivaniuta.gateway.payments.repo;

import com.github.dimitryivaniuta.gateway.payments.domain.IdempotencyRecord;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * JPA repository for {@link IdempotencyRecord}.
 */
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {

    /**
     * Finds the idempotency record by scope and key (no lock).
     *
     * @param scope operation scope
     * @param key idempotency key
     * @return record
     */
    @Query("select r from IdempotencyRecord r where r.scope = :scope and r.idempotencyKey = :key")
    Optional<IdempotencyRecord> findByScopeAndKey(@Param("scope") String scope, @Param("key") String key);

    /**
     * Finds the idempotency record by scope and key and locks it for the duration of the transaction.
     *
     * <p>Combined with a Postgres advisory lock, this ensures concurrent requests do not produce double-charges
     * and we can safely update the record in-place.</p>
     *
     * @param scope operation scope
     * @param key idempotency key
     * @return record
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from IdempotencyRecord r where r.scope = :scope and r.idempotencyKey = :key")
    Optional<IdempotencyRecord> findByScopeAndKeyForUpdate(@Param("scope") String scope, @Param("key") String key);
}
