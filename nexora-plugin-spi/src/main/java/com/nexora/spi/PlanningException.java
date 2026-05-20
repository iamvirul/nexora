package com.nexora.spi;

public class PlanningException extends RuntimeException {
    public PlanningException(String message) { super(message); }
    public PlanningException(String message, Throwable cause) { super(message, cause); }
}
