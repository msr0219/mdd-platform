package com.platform.meta;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** Definitions are owned by MetaEngine; consumers must never mutate JsonNode trees. */
public record MetaSnapshot(long version, Map<String, JsonNode> models,
                           Map<String, JsonNode> views, Map<String, JsonNode> services,
                           List<JsonNode> menus) {
    public MetaSnapshot {
        models = Map.copyOf(models); views = Map.copyOf(views);
        services = Map.copyOf(services); menus = List.copyOf(menus);
    }
}
