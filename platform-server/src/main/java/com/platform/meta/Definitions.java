package com.platform.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.rpc.RpcException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Definitions {
    private Definitions() { }
    public static String table(JsonNode model) { return model.path("model").path("table").asText("t_" + model.path("model").path("code").asText()); }
    public static Map<String,JsonNode> fields(JsonNode model) {
        Map<String,JsonNode> fields = new LinkedHashMap<>();
        model.path("fields").forEach(f -> fields.put(f.path("code").asText(),f));
        return fields;
    }
    public static JsonNode model(MetaSnapshot snapshot, String code) {
        JsonNode def = snapshot.models().get(code);
        if (def == null) throw new RpcException(-32004,"模型不存在："+code);
        return def;
    }
    public static String q(String identifier) {
        if (identifier == null || !identifier.matches("[a-zA-Z][a-zA-Z0-9_]*"))
            throw new IllegalArgumentException("非法标识符");
        return "`" + identifier + "`";
    }
    public static boolean logicalDelete(JsonNode model) { return model.path("model").path("logicDelete").asBoolean(true); }
}
