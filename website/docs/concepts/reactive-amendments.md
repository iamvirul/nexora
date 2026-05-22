---
id: reactive-amendments
title: Reactive Plan Amendment
sidebar_position: 2
---

# Reactive plan amendment

A capability can reshape the remaining plan by returning amendments alongside its result:

```java
return CapabilityResult.success(
    Map.of("valid", true),
    List.of(
        // inject a new step that runs after this one
        new AddStepAmendment(new Step("audit_log", "audit_log",
            Map.of("orderId", InputBinding.literal(orderId)),
            null, Set.of("validate_order"), null, null)),

        // override an input for a downstream step
        new ModifyInputAmendment("send_receipt", "audited", true),

        // cancel a step that is no longer needed
        new SkipStepAmendment("legacy_check")
    )
);
```

Amendments are applied by the scheduler before any dependent step begins. A `PlanAmendedEvent` fires for each one so you can observe every mutation.
