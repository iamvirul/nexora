package me.virul.nexora.samples.springboot;

import me.virul.nexora.core.plan.InputBinding;
import me.virul.nexora.core.plan.condition.StepOutputPresent;
import me.virul.nexora.planner.model.StepDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The order workflow as Spring beans. nexora-spring-boot-starter registers the plugin and both
 * step definitions with the auto-configured engine; nothing here builds an engine.
 */
@Configuration(proxyBeanMethods = false)
public class OrderWorkflowConfig {

    /** Goals containing this phrase plan the order workflow. */
    public static final String ORDER_GOAL = "process order";

    static final String STEP_VALIDATE = "validate";
    static final String STEP_CHARGE = "charge";

    // Intent values are read under intent.context; a bare path reads the execution's working data.
    private static final String INTENT_CONTEXT = "intent.context.";

    // The engine owns the plugin lifecycle: no inferred destroy method calling shutdown() a second time.
    @Bean(destroyMethod = "")
    OrderPlugin orderPlugin() {
        return new OrderPlugin();
    }

    @Bean
    StepDefinition validateOrderStep() {
        return StepDefinition.builder(STEP_VALIDATE, OrderPlugin.VALIDATE_ORDER)
                .withMatcher(goal -> goal.contains(ORDER_GOAL))
                .withInput(
                        OrderPlugin.INPUT_ORDER_ID,
                        InputBinding.fromContext(INTENT_CONTEXT + OrderPlugin.INPUT_ORDER_ID))
                .withInput(
                        OrderPlugin.INPUT_AMOUNT, InputBinding.fromContext(INTENT_CONTEXT + OrderPlugin.INPUT_AMOUNT))
                .build();
    }

    @Bean
    StepDefinition chargePaymentStep() {
        return StepDefinition.builder(STEP_CHARGE, OrderPlugin.CHARGE_PAYMENT)
                .withMatcher(goal -> goal.contains(ORDER_GOAL))
                .withInput(OrderPlugin.INPUT_ORDER_ID, InputBinding.fromStep(STEP_VALIDATE, OrderPlugin.INPUT_ORDER_ID))
                .withInput(
                        OrderPlugin.INPUT_AMOUNT, InputBinding.fromContext(INTENT_CONTEXT + OrderPlugin.INPUT_AMOUNT))
                .dependsOn(STEP_VALIDATE)
                // dependsOn orders steps but does not stop this one when validate fails: only charge an
                // order validate accepted, which it marks by returning the order id.
                .withCondition(new StepOutputPresent(STEP_VALIDATE, OrderPlugin.INPUT_ORDER_ID))
                .build();
    }
}
