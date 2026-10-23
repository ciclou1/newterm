package com.chatbi.service;

/**
 * Atomic in-flight budget reservations. Kept as an interface so the quota
 * logic can be tested without a Redis server and so a different shared store
 * can replace Redis later.
 */
public interface QuotaReservationStore {

    enum Status {
        ACQUIRED,
        TOKEN_EXCEEDED,
        COST_EXCEEDED
    }

    /**
     * Atomically check the remaining budget and reserve some of it.
     *
     * @param spentTokens     usage already recorded in query_audit today
     * @param spentCostMicros the same usage in micro-USD
     * @param tokenLimit      daily token budget, 0 when disabled
     * @param costLimitMicros daily cost budget in micro-USD, 0 when disabled
     * @param reserveTokens   tokens to hold while the request is running
     * @param reserveCostMicros cost to hold while the request is running
     */
    Result tryReserve(String username, long spentTokens, long spentCostMicros,
                      long tokenLimit, long costLimitMicros,
                      long reserveTokens, long reserveCostMicros);

    /** Return a previously acquired reservation. Best effort, never throws for callers. */
    void release(String username, String day, long tokens, long costMicros);

    record Result(Status status, long tokens, long costMicros, String day) {
        static Result acquired(long tokens, long costMicros, String day) {
            return new Result(Status.ACQUIRED, tokens, costMicros, day);
        }

        static Result rejected(Status status) {
            return new Result(status, 0, 0, null);
        }
    }
}
