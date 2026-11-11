package me.virul.nexora.samples.springboot;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import me.virul.nexora.core.capability.CapabilityRequest;
import me.virul.nexora.core.capability.CapabilityResult;
import me.virul.nexora.spi.Capability;
import me.virul.nexora.spi.CapabilityDescriptor;
import me.virul.nexora.spi.CapabilityProvider;
import me.virul.nexora.spi.NexoraPlugin;
import me.virul.nexora.spi.PluginContext;
import me.virul.nexora.spi.PluginDescriptor;

/**
 * Order capabilities: {@value #VALIDATE_ORDER} rejects orders above {@link #ORDER_LIMIT}, and
 * {@value #CHARGE_PAYMENT} charges an in-memory ledger. The charge is idempotent on the step's
 * idempotency key, so a retry or replay of the same step never charges twice.
 */
public final class OrderPlugin implements NexoraPlugin {

    public static final String PLUGIN_ID = "order-plugin";
    public static final String VALIDATE_ORDER = "validate_order";
    public static final String CHARGE_PAYMENT = "charge_payment";
    public static final String ORDER_REJECTED = "ORDER_REJECTED";
    public static final String INVALID_INPUT = "INVALID_INPUT";
    public static final BigDecimal ORDER_LIMIT = new BigDecimal("10000");

    static final String INPUT_ORDER_ID = "orderId";
    static final String INPUT_AMOUNT = "amount";
    static final String OUTPUT_PAYMENT_ID = "paymentId";

    private final ConcurrentMap<String, String> paymentsByIdempotencyKey = new ConcurrentHashMap<>();

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(PLUGIN_ID, "1.0.0", "Validates and charges orders", List.of(), null);
    }

    @Override
    public void initialize(PluginContext context) {}

    @Override
    public List<CapabilityProvider> capabilityProviders() {
        return List.of(
                provider(VALIDATE_ORDER, "Rejects orders above the limit", this::validate),
                provider(CHARGE_PAYMENT, "Charges the order amount", this::charge));
    }

    @Override
    public void shutdown() {
        paymentsByIdempotencyKey.clear();
    }

    /** Payments charged so far, for the demo's tests. */
    int paymentCount() {
        return paymentsByIdempotencyKey.size();
    }

    private CapabilityResult validate(CapabilityRequest request) {
        Object rawAmount = request.inputs().get(INPUT_AMOUNT);
        BigDecimal amount;
        try {
            amount = new BigDecimal(String.valueOf(rawAmount));
        } catch (NumberFormatException e) {
            return CapabilityResult.failure(INVALID_INPUT, "amount is missing or not a number");
        }
        if (amount.compareTo(ORDER_LIMIT) > 0) {
            return CapabilityResult.failure(ORDER_REJECTED, "Order amount exceeds " + ORDER_LIMIT);
        }
        return CapabilityResult.success(Map.of(INPUT_ORDER_ID, request.inputs().get(INPUT_ORDER_ID)));
    }

    private CapabilityResult charge(CapabilityRequest request) {
        String paymentId =
                paymentsByIdempotencyKey.computeIfAbsent(request.idempotencyKey(), key -> "PAY-" + UUID.randomUUID());
        return CapabilityResult.success(Map.of(OUTPUT_PAYMENT_ID, paymentId));
    }

    private static CapabilityProvider provider(String id, String description, Capability capability) {
        CapabilityDescriptor descriptor = new CapabilityDescriptor(id, description, List.of(), List.of(), true, false);
        return new CapabilityProvider() {
            @Override
            public CapabilityDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public Capability create(PluginContext context) {
                return capability;
            }
        };
    }
}
