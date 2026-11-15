package com.platform.custom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.service.extension.ModelCrudContext;
import com.platform.service.extension.ModelCrudHandler;
import org.springframework.stereotype.Component;

/** Student-specific rules shared by direct CRUD calls, metadata services and batch creation. */
@Component
public class StudentCrudHandler extends ModelCrudHandler {
    @Override
    public String modelCode() {
        return "student";
    }

    @Override
    public Object create(ModelCrudContext context, JsonNode params) {
        ObjectNode normalized = ((ObjectNode) params).deepCopy();
        if (normalized.path("name").isTextual()) {
            normalized.put("name", normalized.get("name").asText().strip());
        }
        return super.create(context, normalized);
    }
}
