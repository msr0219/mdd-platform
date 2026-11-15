package com.platform.service.extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.rpc.RpcException;

/**
 * A Spring component bound to one metadata model. Override only the required operations;
 * every default method delegates to the platform implementation through {@code proceed}.
 * Implementations are singleton, trusted application code: keep request state in local variables.
 */
public abstract class ModelCrudHandler {
    public abstract String modelCode();

    public Object create(ModelCrudContext context, JsonNode params) { return context.proceed(params); }
    public Object read(ModelCrudContext context, JsonNode params) { return context.proceed(params); }
    public Object list(ModelCrudContext context, JsonNode params) { return context.proceed(params); }
    public Object update(ModelCrudContext context, JsonNode params) { return context.proceed(params); }
    public Object delete(ModelCrudContext context, JsonNode params) { return context.proceed(params); }
    public Object batchCreate(ModelCrudContext context, JsonNode params) { return context.proceed(params); }
    public Object batchDelete(ModelCrudContext context, JsonNode params) { return context.proceed(params); }
    public Object adjust(ModelCrudContext context, JsonNode params) { return context.proceed(params); }
    public Object transition(ModelCrudContext context, JsonNode params) { return context.proceed(params); }
    public Object aggregate(ModelCrudContext context, JsonNode params) { return context.proceed(params); }
    public Object related(ModelCrudContext context, JsonNode params) { return context.proceed(params); }

    /** The dispatcher stays fixed so inherited operations always retain the default behavior. */
    public final Object handle(ModelCrudContext context, JsonNode params) {
        return switch (context.action()) {
            case "create" -> create(context, params);
            case "read" -> read(context, params);
            case "list" -> list(context, params);
            case "update" -> update(context, params);
            case "delete" -> delete(context, params);
            case "batchCreate" -> batchCreate(context, params);
            case "batchDelete" -> batchDelete(context, params);
            case "adjust" -> adjust(context, params);
            case "transition" -> transition(context, params);
            case "aggregate" -> aggregate(context, params);
            case "related" -> related(context, params);
            default -> throw new RpcException(-32601, "模型动作不存在：" + context.action());
        };
    }
}
