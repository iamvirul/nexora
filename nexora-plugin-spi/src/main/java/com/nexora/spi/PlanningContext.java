package com.nexora.spi;

import java.util.List;
import java.util.Map;

/**
 * Read-only view of the engine state available to a Planner at plan time.
 */
public interface PlanningContext {

    /** All capabilities currently registered in the engine. */
    List<CapabilityDescriptor> availableCapabilities();

    /** Engine-level config passed through from the builder. */
    Map<String, Object> config();
}
