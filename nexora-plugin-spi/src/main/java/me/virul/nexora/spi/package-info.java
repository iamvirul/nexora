/**
 * The plugin service provider interface: what a plugin JAR implements to contribute capabilities and
 * planners to the engine.
 *
 * <p>A plugin implements {@link me.virul.nexora.spi.NexoraPlugin} and lists it in
 * {@code META-INF/services/me.virul.nexora.spi.NexoraPlugin}. Its
 * {@link me.virul.nexora.spi.CapabilityProvider}s and {@link me.virul.nexora.spi.PlannerProvider}s
 * create the {@link me.virul.nexora.spi.Capability} and {@link me.virul.nexora.spi.Planner} instances
 * the engine runs.
 */
package me.virul.nexora.spi;
