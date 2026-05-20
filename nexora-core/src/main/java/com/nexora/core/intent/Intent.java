package com.nexora.core.intent;

import java.util.Map;

public class Intent {

    private final String goal;
    private final Map<String, Object> context;

    public Intent(String goal, Map<String, Object> context) {
        this.goal = goal;
        this.context = context;
    }

    public String getGoal() {
        return goal;
    }

    public Map<String, Object> getContext() {
        return context;
    }
}
