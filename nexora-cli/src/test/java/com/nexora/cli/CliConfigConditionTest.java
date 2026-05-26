package com.nexora.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.core.plan.StepCondition;
import com.nexora.core.plan.condition.And;
import com.nexora.core.plan.condition.ContextValueEquals;
import com.nexora.core.plan.condition.StepOutputEquals;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CliConfigConditionTest {

    @Test
    void testConditionDeserialization() throws Exception {
        String json = """
        {
            "steps": [
                {
                    "id": "step1",
                    "capabilityId": "cap1",
                    "condition": {
                        "type": "and",
                        "conditions": [
                            {
                                "type": "contextValueEquals",
                                "key": "intent.context.type",
                                "value": "test"
                            },
                            {
                                "type": "stepOutputEquals",
                                "stepId": "step0",
                                "field": "valid",
                                "value": true
                            }
                        ]
                    }
                }
            ]
        }
        """;

        ObjectMapper mapper = new ObjectMapper();
        CliConfig config = mapper.readValue(json, CliConfig.class);

        assertEquals(1, config.steps.size());
        CliConfig.ConditionConfig condConfig = config.steps.get(0).condition;
        assertInstanceOf(CliConfig.AndConfig.class, condConfig);
        
        StepCondition condition = condConfig.toStepCondition();
        assertInstanceOf(And.class, condition);
        
        And andCond = (And) condition;
        assertEquals(2, andCond.conditions().size());
        
        assertInstanceOf(ContextValueEquals.class, andCond.conditions().get(0));
        ContextValueEquals cve = (ContextValueEquals) andCond.conditions().get(0);
        assertEquals("intent.context.type", cve.key());
        assertEquals("test", cve.value());
        
        assertInstanceOf(StepOutputEquals.class, andCond.conditions().get(1));
        StepOutputEquals soe = (StepOutputEquals) andCond.conditions().get(1);
        assertEquals("step0", soe.stepId());
        assertEquals("valid", soe.field());
        assertEquals(true, soe.value());
    }
}
