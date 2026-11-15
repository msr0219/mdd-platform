package com.platform;

import com.platform.service.extension.ModelCrudHandler;
import com.platform.service.extension.ModelCrudRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelCrudRegistryTest {
    @Test
    void aModelHasExactlyOneHandlerAndUnregisteredModelsUseTheDefault() {
        ModelCrudHandler student = handler("student");
        ModelCrudRegistry registry = new ModelCrudRegistry(List.of(student));
        assertThat(registry.find("student")).isSameAs(student);
        assertThat(registry.find("course")).isNull();
        assertThat(registry.modelCodes()).containsExactly("student");
        assertThrows(IllegalStateException.class, () -> new ModelCrudRegistry(List.of(student, handler("student"))));
    }

    @Test
    void invalidOrReservedModelBindingsAreRejected() {
        for (String code : new String[]{null, "", "1student", "student.create", "meta", "service"})
            assertThrows(IllegalStateException.class, () -> new ModelCrudRegistry(List.of(handler(code))));
    }

    static ModelCrudHandler handler(String code) {
        return new ModelCrudHandler() {
            @Override public String modelCode() { return code; }
        };
    }
}
