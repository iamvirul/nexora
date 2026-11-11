package me.virul.nexora.planner.llm;

/**
 * One LLM API call that answers a {@link PlanPrompt} with plan JSON. Implementations wrap an
 * official provider SDK and must be thread-safe; the planner calls them concurrently.
 */
public interface PlanCompletionClient extends AutoCloseable {

    /**
     * @return the model's text answer, expected to be JSON matching {@link PlanPrompt#schema()}
     * @throws LlmCallException when the call fails, is refused, or is cut off
     */
    String complete(PlanPrompt prompt);

    /** Releases the SDK client's connection pool. Must not throw. */
    @Override
    void close();
}
