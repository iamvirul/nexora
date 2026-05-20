import com.nexora.core.context.ExecutionContext;
import com.nexora.core.intent.Intent;
import com.nexora.core.plan.Plan;
import com.nexora.planner.engine.PlannerEngine;
import com.nexora.planner.model.StepDefinition;
import com.nexora.planner.registry.PlanRegistry;
import com.nexora.runtime.engine.ExecutionEngine;
import com.nexora.runtime.executor.DefaultStepExecutor;

import java.util.Map;

public class Demo {

    public static void main(String[] args) {

        // -------------------------
        // 1. INTENT
        // -------------------------
        Intent intent = new Intent(
                "process order payment notification",
                Map.of("orderId", "123")
        );

        // -------------------------
        // 2. PLANNER SETUP
        // -------------------------
        PlanRegistry registry = new PlanRegistry();

        registry.register(new StepDefinition(
                "validate_order",
                goal -> goal.contains("order")
        ));

        registry.register(new StepDefinition(
                "process_payment",
                goal -> goal.contains("payment")
        ));

        registry.register(new StepDefinition(
                "send_notification",
                goal -> true
        ));

        PlannerEngine planner = new PlannerEngine(registry);

        // -------------------------
        // 3. PLAN GENERATION
        // -------------------------
        Plan plan = planner.createPlan(intent);

        System.out.println("\n=== GENERATED PLAN ===");
        plan.getSteps().forEach(step ->
                System.out.println("STEP -> " + step.getName())
        );

        // -------------------------
        // 4. EXECUTION
        // -------------------------
        ExecutionEngine engine =
                new ExecutionEngine(new DefaultStepExecutor());

        ExecutionContext context = new ExecutionContext(intent);

        var result = engine.execute(plan, context);

        // -------------------------
        // 5. RESULT
        // -------------------------
        System.out.println("\n=== RESULT ===");
        System.out.println("STATUS: " + result.getStatus());
    }
}