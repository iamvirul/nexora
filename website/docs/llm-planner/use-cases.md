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

The common path, *"process order"*, stays on fast, free rules. The unusual ones go to the model: *"Split ORD-42 into two shipments, charge the deposit now and the balance on delivery."* This is the pattern the [demo](overview#quick-start) shows: rules for the common path, the LLM planner for the rest, with automatic fallback between them.

### Incident runbooks

*"Payments API p99 is high: check recent deploys, scale the payments pool, and page the on-call if the error rate is above 2%."* The capabilities are your runbook actions (`list_deploys`, `scale_pool`, `get_error_rate`, `page_oncall`). The model cannot invent an action you did not register, such as restarting everything.

### Reporting and data requests

*"Pull last week's EU orders, join them with refunds, and send the summary to finance."* Chains `query_orders`, `join_refunds`, `render_report` and `send_email`, each step's output feeding the next.

### Drafting workflows for review

A business user describes a flow in plain language; the planner drafts it. Show the plan for approval, then save the approved one as a fixed [step definition](../modules/nexora-planner) so it runs on rules from then on. The cache means the same request is not re-planned within the TTL.

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

## A rollout that works

1. Keep rule-based step definitions for your top flows.
2. Add the LLM planner at a higher priority, so it handles everything else.
3. Watch the `WARN` reasons in your logs ([what they mean](overview#reading-the-logs)). Frequent `INVALID_PLAN` usually points at a vague capability description or missing `outputParameters`.
4. Promote LLM plans that recur often into rule definitions, so they become fast and deterministic.
