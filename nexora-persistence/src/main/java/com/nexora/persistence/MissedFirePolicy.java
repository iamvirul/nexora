package com.nexora.persistence;

public enum MissedFirePolicy {
    /** Skip all missed windows; wait for the next scheduled time. */
    SKIP,
    /** Fire exactly once to catch up, regardless of how many windows were missed. */
    FIRE_ONCE,
    /** Fire once for every missed window. */
    FIRE_ALL
}
