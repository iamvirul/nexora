package com.nexora.capability;


import com.nexora.core.context.ExecutionContext;

public interface Capability {
    String name();
    Object execute(ExecutionContext context );
}
