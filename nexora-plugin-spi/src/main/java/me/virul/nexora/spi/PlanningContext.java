package me.virul.nexora.spi;

import java.util.List;
import java.util.Map;

/**
 * Read-only view of the engine state available to a Planner at plan time.
 */
public interface PlanningContext {

    /**
     * The capabilities the planner may use: those registered in the engine that the intent's tenant
     * may use.
     *
     * @return the descriptors
     */
    List<CapabilityDescriptor> availableCapabilities();

    /**
     * Engine-level config passed through from the builder.
     *
     * @return the configuration; never null
     */
    Map<String, Object> config();
}
