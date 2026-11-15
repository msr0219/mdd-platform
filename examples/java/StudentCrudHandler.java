package com.platform.custom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.service.extension.ModelCrudContext;
import com.platform.service.extension.ModelCrudHandler;
import org.springframework.stereotype.Component;

/**
 * Reference copy of the registered handler in
 * platform-server/src/main/java/com/platform/custom/StudentCrudHandler.java.
 * Edit the registered source to change student rules; do not register this copy again.
 * To customize another model, change both the class name and modelCode().
 * Files under examples/ are not scanned or compiled by the server.
 */
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
        // The base method keeps required/type/unique checks and the normal save.
        return super.create(context, normalized);
    }
}
