package com.platform.service.extension;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.function.Function;

/** Request-local access to the current model and CRUD operations using the same metadata snapshot. */
public final class ModelCrudContext {
    @FunctionalInterface
    public interface ModelCall {
        Object execute(String modelCode, String action, JsonNode params);
    }

    private final String modelCode;
    private final String action;
    private final JsonNode definition;
    private final JsonNode params;
    private final Function<JsonNode, Object> defaultOperation;
    private final ModelCall modelCall;

    public ModelCrudContext(String modelCode, String action, JsonNode definition, JsonNode params,
                            Function<JsonNode, Object> defaultOperation, ModelCall modelCall) {
        this.modelCode = Objects.requireNonNull(modelCode);
        this.action = Objects.requireNonNull(action);
        this.definition = Objects.requireNonNull(definition);
        this.params = params;
        this.defaultOperation = Objects.requireNonNull(defaultOperation);
        this.modelCall = Objects.requireNonNull(modelCall);
    }

    public String modelCode() { return modelCode; }
    public String action() { return action; }

    /** A private copy: a handler must not modify the shared metadata snapshot. */
    public JsonNode model() { return definition.deepCopy(); }

    /** Invoke this operation's generic implementation without entering this handler again. */
    public Object proceed(JsonNode params) { return defaultOperation.apply(params); }
    public Object proceed() { return proceed(params); }

    /**
     * Invoke another model operation, including its handler, with the current caller's row scope.
     * Like metadata service steps, these are trusted internal calls, not new public permission grants.
     */
    public Object call(String modelCode, String action, JsonNode params) {
        return modelCall.execute(modelCode, action, params);
    }

    public Object call(String action, JsonNode params) { return call(modelCode, action, params); }
}
