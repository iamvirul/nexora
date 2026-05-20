package com.nexora.cli;

import java.util.List;

/** Deserialize from nexora.json in the working directory, or from --config. */
public class CliConfig {

    public List<StepConfig> steps = List.of();
    public RetryConfig retry = new RetryConfig();

    public static class StepConfig {
        public String id;
        public String capabilityId;
        public String matchesGoalContains; // simple: matches if goal contains this string
    }

    public static class RetryConfig {
        public int maxAttempts = 3;
        public long initialDelayMs = 200;
        public double multiplier = 2.0;
        public long maxDelayMs = 10_000;
    }
}
