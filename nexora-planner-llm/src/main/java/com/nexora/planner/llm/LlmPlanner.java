package com.nexora.planner.llm;

import com.nexora.core.intent.Intent;
import com.nexora.core.plan.Plan;
import com.nexora.spi.CapabilityDescriptor;
import com.nexora.spi.Planner;
import com.nexora.spi.PlannerDescriptor;
import com.nexora.spi.PlanningContext;
import com.nexora.spi.PlanningDeclinedException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plans an intent by asking an LLM to turn its goal into a DAG over the capabilities the intent's
 * tenant may use.
 *
 * <p>When the call fails or the answer is not a runnable plan, the planner logs a {@code WARN} and
 * throws {@link PlanningDeclinedException}, so the engine falls back to the next planner and in the
 * end to the rule-based planner. Valid plans are cached by goal, capabilities and context names.
 *
 * <p>Planning runs on the thread that submits the intent, so a slow model delays the submit by up
 * to the configured timeout per attempt.
 */
public final class LlmPlanner implements Planner {

    public static final String PLANNER_ID = "llm-planner";

    private static final Logger log = LoggerFactory.getLogger(LlmPlanner.class);
    /** Enough of the cache key to correlate log lines for one goal without logging the goal. */
    private static final int GOAL_HASH_LENGTH = 12;
    /** Log details can quote model output; keep them to one bounded line. */
    private static final int MAX_DETAIL_LENGTH = 300;

    private final PlanCompletionClient client;
    private final LlmPlannerConfig config;
    private final PlanCache cache;
    private final PlannerDescriptor descriptor;
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong modelCalls = new AtomicLong();
    private final AtomicLong declined = new AtomicLong();

    /** Counters since the planner was created. */
    public record Stats(long cacheHits, long modelCalls, long declined) {}

    public LlmPlanner(PlanCompletionClient client, LlmPlannerConfig config) {
        this(client, config, Clock.systemUTC());
    }

    LlmPlanner(PlanCompletionClient client, LlmPlannerConfig config, Clock clock) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.cache = new PlanCache(config.cacheTtl(), config.cacheMaxEntries(), clock);
        this.descriptor = new PlannerDescriptor(
                PLANNER_ID,
                "Plans goals with " + config.provider().name().toLowerCase(Locale.ROOT) + " model " + config.model(),
                config.priority());
    }

    @Override
    public PlannerDescriptor descriptor() {
        return descriptor;
    }

    /** Any goal, as long as there is at least one capability to plan with. */
    @Override
    public boolean canPlan(Intent intent, PlanningContext context) {
        return intent.getGoal() != null
                && !intent.getGoal().isBlank()
                && !context.availableCapabilities().isEmpty();
    }

    @Override
    public Plan plan(Intent intent, PlanningContext context) {
        List<CapabilityDescriptor> capabilities = context.availableCapabilities();
        Set<String> contextNames = intent.getContext().keySet();
        String key = PlanCache.key(
                intent.getGoal(),
                capabilities.stream().map(PlanCache::signature).toList(),
                contextNames);
        String goalHash = key.substring(0, GOAL_HASH_LENGTH);

        Optional<Plan> cached = cache.get(key);
        if (cached.isPresent()) {
            cacheHits.incrementAndGet();
            log.debug(
                    "LLM plan served from cache goalHash={} steps={}",
                    goalHash,
                    cached.get().getSteps().size());
            return cached.get();
        }

        PlanPrompt prompt = PlanPromptBuilder.build(intent.getGoal(), capabilities, contextNames);
        long started = System.nanoTime();
        modelCalls.incrementAndGet();
        try {
            Plan plan = PlanParser.parse(client.complete(prompt), capabilities, contextNames);
            cache.put(key, plan);
            log.info(
                    "LLM plan created provider={} model={} goalHash={} steps={} durationMs={}",
                    providerName(),
                    config.model(),
                    goalHash,
                    plan.getSteps().size(),
                    elapsedMs(started));
            return plan;
        } catch (LlmCallException e) {
            throw decline(goalHash, started, e.reason().name(), e.getMessage(), e);
        } catch (InvalidPlanException e) {
            throw decline(goalHash, started, "INVALID_PLAN", e.getMessage(), e);
        } catch (RuntimeException e) {
            // An SDK failure no client mapped: still a reason to fall back, never to fail the execution.
            log.error(
                    "LLM client failed unexpectedly provider={} model={} goalHash={}",
                    providerName(),
                    config.model(),
                    goalHash,
                    e);
            throw decline(goalHash, started, "CLIENT_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    public Stats stats() {
        return new Stats(cacheHits.get(), modelCalls.get(), declined.get());
    }

    private PlanningDeclinedException decline(
            String goalHash, long started, String reason, String detail, RuntimeException cause) {
        String safeDetail = oneLine(detail);
        declined.incrementAndGet();
        log.warn(
                "LLM planner declined, falling back to the next planner provider={} model={} goalHash={}"
                        + " reason={} detail={} durationMs={}",
                providerName(),
                config.model(),
                goalHash,
                reason,
                safeDetail,
                elapsedMs(started));
        return new PlanningDeclinedException("LLM planner could not plan (" + reason + "): " + safeDetail, cause);
    }

    /** Control characters (newlines included) become spaces, so a quoted answer cannot forge log lines. */
    static String oneLine(String detail) {
        if (detail == null) {
            return "";
        }
        String flat = detail.replaceAll("\\p{Cntrl}", " ");
        return flat.length() <= MAX_DETAIL_LENGTH ? flat : flat.substring(0, MAX_DETAIL_LENGTH) + "...";
    }

    private String providerName() {
        return config.provider().name().toLowerCase(Locale.ROOT);
    }

    private static long elapsedMs(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }
}
