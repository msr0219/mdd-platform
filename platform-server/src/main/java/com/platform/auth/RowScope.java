package com.platform.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.meta.MetaSnapshot;
import com.platform.rpc.RpcException;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Supplier;
import static com.platform.meta.Definitions.*;

/** Server-owned SQL predicates; client filters can only narrow this scope. */
@Component
public class RowScope {
    private static final ThreadLocal<Integer> SYSTEM_DEPTH = new ThreadLocal<>();

    public boolean restricted(JsonNode definition) {
        var user = AuthContext.current();
        return user != null && SYSTEM_DEPTH.get() == null && !user.allows("data:scopeAll")
                && definition.path("model").has("rowScope");
    }

    public String sql(MetaSnapshot snapshot, JsonNode definition, String alias, List<Object> args) {
        if (!restricted(definition)) return "";
        return " AND (" + predicate(snapshot, definition, alias, args, 0) + ")";
    }

    private String predicate(MetaSnapshot snapshot, JsonNode definition, String alias, List<Object> args, int depth) {
        if (depth > 8) throw new RpcException(-32005, "数据范围关联超过8层");
        JsonNode policy = definition.path("model").path("rowScope");
        if (policy.path("creator").asBoolean()) {
            args.add(AuthContext.actor());
            return q(alias) + ".`created_by`=?";
        }
        String via = policy.path("via").asText();
        JsonNode field = fields(definition).get(via);
        if (field == null || !field.path("type").asText().equals("ref"))
            throw new RpcException(-32005, "数据范围必须沿已声明的引用字段");
        JsonNode target = model(snapshot, field.path("ref").asText());
        String parentAlias = alias + "_owner" + depth;
        String reference = q(alias) + "." + q(via);
        String exists = "EXISTS (SELECT 1 FROM " + q(table(target)) + " " + q(parentAlias)
                + " WHERE " + q(parentAlias) + ".`id`=" + reference
                + (logicalDelete(target) ? " AND " + q(parentAlias) + ".`deleted`=FALSE" : "")
                + " AND (" + predicate(snapshot, target, parentAlias, args, depth + 1) + "))";
        return policy.path("allowUnassigned").asBoolean() ? reference + " IS NULL OR " + exists : exists;
    }

    /** Only explicitly declared internal service calls enter this scope. Identity/audit stay unchanged. */
    public static <T> T system(Supplier<T> action) {
        Integer before = SYSTEM_DEPTH.get();
        SYSTEM_DEPTH.set(before == null ? 1 : before + 1);
        try { return action.get(); }
        finally { if (before == null) SYSTEM_DEPTH.remove(); else SYSTEM_DEPTH.set(before); }
    }

    public static void validate(Map<String, JsonNode> models, String code) {
        Set<String> visited = new HashSet<>();
        for (int depth = 0; depth <= 8; depth++) {
            if (!visited.add(code)) throw new IllegalArgumentException("数据范围引用存在循环");
            JsonNode definition = models.get(code), policy = definition.path("model").get("rowScope");
            if (policy == null || !policy.isObject()) throw new IllegalArgumentException("rowScope及其引用目标必须声明数据范围对象");
            Set<String> keys = new HashSet<>(); policy.fieldNames().forEachRemaining(keys::add);
            if (policy.has("creator")) {
                if (keys.size() != 1 || !policy.get("creator").isBoolean() || !policy.get("creator").booleanValue())
                    throw new IllegalArgumentException("本人创建范围仅接受{creator:true}");
                return;
            }
            if (!Set.of("via", "allowUnassigned").containsAll(keys) || !policy.path("via").isTextual()
                    || (policy.has("allowUnassigned") && !policy.get("allowUnassigned").isBoolean()))
                throw new IllegalArgumentException("继承范围仅接受via引用字段及可选allowUnassigned布尔值");
            JsonNode field = fields(definition).get(policy.path("via").asText());
            if (field == null || !field.path("type").asText().equals("ref") || !models.containsKey(field.path("ref").asText()))
                throw new IllegalArgumentException("via必须引用现有模型的ref字段");
            code = field.path("ref").asText();
        }
        throw new IllegalArgumentException("数据范围引用超过8层");
    }
}
