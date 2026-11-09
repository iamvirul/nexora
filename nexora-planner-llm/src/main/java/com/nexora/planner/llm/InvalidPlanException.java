package com.nexora.planner.llm;

/** The model answered, but not with a plan the engine can run. The message says why. */
final class InvalidPlanException extends RuntimeException {
    InvalidPlanException(String message) {
        super(message, null, false, false);
    }
}
