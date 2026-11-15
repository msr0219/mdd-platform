package com.platform.service.extension;

import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic, one-handler-per-model registration from Spring components. */
@Component
public final class ModelCrudRegistry {
    private final Map<String, ModelCrudHandler> handlers;

    public ModelCrudRegistry(List<ModelCrudHandler> candidates) {
        Map<String, ModelCrudHandler> registered = new LinkedHashMap<>();
        for (ModelCrudHandler handler : candidates) {
            String code = handler.modelCode();
            if (code == null || !code.matches("[a-zA-Z][a-zA-Z0-9_]*") || Set.of("meta", "service").contains(code))
                throw new IllegalStateException("Java CRUD 处理器的模型编码非法：" + code);
            ModelCrudHandler previous = registered.putIfAbsent(code, handler);
            if (previous != null)
                throw new IllegalStateException("模型 " + code + " 注册了多个 Java CRUD 处理器："
                        + previous.getClass().getName() + "、" + handler.getClass().getName());
        }
        handlers = Map.copyOf(registered);
    }

    public ModelCrudHandler find(String modelCode) { return handlers.get(modelCode); }
    public Set<String> modelCodes() { return handlers.keySet(); }
}
