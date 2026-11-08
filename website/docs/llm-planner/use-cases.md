---
id: use-cases
title: LLM Planner Use Cases
sidebar_label: Use cases
sidebar_position: 6
---

# Use cases

The LLM planner pays off when the requests people send are too varied to write rules for, but the actions that carry them out are a fixed, known set of capabilities. The model only decides which capabilities run, in what order, and how their inputs connect. It never runs anything you did not register, and every plan is checked before it runs.

## Where it fits

### Support and operations requests

A ticket or chat message says *"Customer ORD-881 was double-charged. Refund the second charge and email them an apology."*

| Capabilities | What the planner does |
|--------------|----------------------|
| `lookup_order`, `refund_charge`, `send_email`, `add_ticket_note` | Looks up the order, refunds the charge using the id from the lookup, then emails and notes the ticket |

Support agents phrase the same request a hundred ways. Writing a keyword rule for each does not scale; one planner over a small set of capabilities does.

### Internal "do this for me" requests

From a Slack bot or an admin console: *"Offboard jane@corp: disable SSO, revoke her GitHub access, and transfer her Drive files to her manager."*

| Capabilities | What the planner does |
|--------------|----------------------|
| `disable_sso`, `revoke_github`, `lookup_manager`, `transfer_drive` | Runs the independent steps in parallel, and feeds the manager from `lookup_manager` into `transfer_drive` |

Each capability keeps its own permission checks and audit trail. On a multi-tenant engine, the planner only sees the capabilities the request's tenant may use.

### Long-tail order and commerce flows

Keep the common path, *"process order"*, on fast, free rules, and send the unusual ones to the model: *"Split ORD-42 into two shipments, charge the deposit now and the balance on delivery."* The LLM planner does not leave common goals to rules on its own; add a rule-first planner for them, as in [Routing common goals to rules](#routing-common-goals-to-rules). The [demo](overview#quick-start) shows the two planners' plans for the same goal side by side.

### Incident runbooks

*"Payments API p99 is high: check recent deploys, scale the payments pool, and page the on-call if the error rate is above 2%."* The capabilities are your runbook actions (`list_deploys`, `scale_pool`, `get_error_rate`, `page_oncall`). The model cannot invent an action you did not register, such as restarting everything.

### Reporting and data requests

*"Pull last week's EU orders, join them with refunds, and send the summary to finance."* Chains `query_orders`, `join_refunds`, `render_report` and `send_email`, each step's output feeding the next.

### Drafting workflows for review

A business user describes a flow in plain language; the planner drafts it, and you save the approved flow as fixed [step definitions](../modules/nexora-planner) so it runs on rules from then on. The engine has no plan-only call: `execute(...)` and `submit(...)` plan and then run. To show a plan for approval before anything runs, your application calls the planner itself, using the module as a library (`new LlmPlanner(LlmPlannerPlugin.clientFor(config), config).plan(intent, planningContext)`, with `config` from `LlmPlannerConfig.fromEnvironment()`), and runs the approved plan as step definitions. That approval flow is application code, not something the engine does for you.

## Where it does not fit

- **Hot, latency-sensitive paths.** Planning takes seconds (5 to 20 in testing, depending on model and provider) and runs before the execution starts. Checkout and payment authorisation at high volume belong on rules.
- **Fixed flows.** If the goal is always the same, rules are free, instant and give the same plan every time.
- **Plans that must be identical every time without review,** such as regulated money movement. The model's plan can vary between calls. Draft with the model, review, then freeze the plan as a step definition.
- **Goals your capabilities cannot cover.** The model returns no steps and the engine runs the rule-based plan, which may not be what the user asked for. Tell the user when that happens.

## Making plans good

- **Describe capabilities well.** The model sees each capability's description and parameters. *"Charges the customer's card for the order"* plans better than *"charge"*.
- **Declare `outputParameters`.** The model can then bind the exact field a later step needs (`chargeId`) instead of the whole output.
- **Mark required inputs.** The planner rejects any plan that leaves a required input unbound, instead of letting the step fail at run time.
- **Name context values clearly.** The model sees context names like `orderId` and `email`, never their values.

## Routing common goals to rules

The LLM planner accepts every goal that has a capability to plan with, and it runs before the rule-based planner, whatever its priority. The rule-based planner only runs when the LLM planner declines. So to keep common goals off the model, and save its latency and cost, add a planner that claims them first: give it a priority above the LLM planner's (`NEXORA_LLM_PRIORITY`, default `100`) and let it plan those goals from rules.

```java
PlanRegistry common = new PlanRegistry();
common.register(StepDefinition.builder("validate", "validate_order")
        .withMatcher(goal -> goal.contains("order"))
        .withInput("orderId", InputBinding.fromContext("intent.context.orderId"))
        .build());
// ... the other step definitions for your common flows

RulePlanner commonRules = new RulePlanner(new PlannerEngine(common));
Planner commonGoals = new Planner() {
    @Override
    public PlannerDescriptor descriptor() {
        return new PlannerDescriptor("common-goals", "Rule plans for known goals", 200);
    }

    @Override
    public boolean canPlan(Intent intent, PlanningContext context) {
        return intent.getGoal().startsWith("process order"); // your known goals
    }

    @Override
    public Plan plan(Intent intent, PlanningContext context) {
        return commonRules.plan(intent, context);
    }
};

NexoraEngine.builder()
        .withPlanner(commonGoals)   // priority 200: known goals, rules, no model call
        .withPluginJar(llmPluginJar) // priority 100: everything else
        .withStepDefinition(...)    // last: the fallback when the LLM planner declines
        .build();
```

## A rollout that works

1. Keep rule-based step definitions for your top flows, behind a rule-first planner so they never reach the model (see [Routing common goals to rules](#routing-common-goals-to-rules)).
2. Add the LLM planner for everything else. Your step definitions remain the fallback when it declines.
3. Watch the `WARN` reasons in your logs ([what they mean](overview#reading-the-logs)). Frequent `INVALID_PLAN` usually points at a vague capability description or missing `outputParameters`.
4. Promote LLM plans that recur often into rule definitions, so they become fast and deterministic.
