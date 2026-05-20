package com.nexora.event;

/**
 * Root sealed type for all engine events.
 * Using a sealed interface ensures every event type is explicit and
 * switch expressions over events are exhaustively checked at compile time.
 */
public sealed interface ExecutionEvent permits
        PlanStartedEvent,
        PlanCompletedEvent,
        PlanFailedEvent,
        PlanAmendedEvent,
        StepStartedEvent,
        StepCompletedEvent,
        StepFailedEvent,
        PluginActivatedEvent,
        PluginDeactivatedEvent {
}
