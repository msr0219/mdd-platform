package com.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.auth.AuthContext;
import com.platform.custom.StudentCrudHandler;
import com.platform.meta.MetaEngine;
import com.platform.meta.MetaSnapshot;
import com.platform.rpc.RpcController;
import com.platform.rpc.RpcDispatcher;
import com.platform.rpc.RpcException;
import com.platform.service.CrudExecutor;
import com.platform.service.extension.ModelCrudContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises model-specific Java rules through the real dispatcher, SQL and transaction manager. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:model-crud-extension-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "platform.demo.enabled=false", "platform.auth.bootstrap-password=InitialTest!12345",
        "spring.main.allow-bean-definition-overriding=true"
})
@Import(ModelCrudExtensionIntegrationTest.Handlers.class)
class ModelCrudExtensionIntegrationTest {
    static final Path META = metadata();
    @Autowired CrudExecutor crud;
    @Autowired RpcDispatcher dispatcher;
    @Autowired RpcController controller;
    @Autowired MetaEngine engine;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired StudentHandler handler;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("platform.meta-dir", () -> META.toString());
    }

    static Path metadata() {
        try {
            Path target = Files.createDirectories(Path.of("target/model-crud-metadata-" + UUID.randomUUID())).toAbsolutePath();
            Files.createDirectories(target.resolve("models"));
            Files.createDirectories(target.resolve("services"));
            Files.writeString(target.resolve("models/student.json"), """
                    {"model":{"code":"student","name":"学生","rowScope":{"creator":true}},"fields":[
                      {"code":"code","name":"编号","type":"string","length":100,"required":true,"unique":true,"listable":true},
                      {"code":"name","name":"名称","type":"string","length":80,"required":true,"listable":true,"searchable":true}
                    ]}
                    """);
            Files.writeString(target.resolve("models/course.json"), """
                    {"model":{"code":"course","name":"课程"},"fields":[
                      {"code":"code","name":"编号","type":"string","length":100,"required":true,"unique":true,"listable":true},
                      {"code":"name","name":"名称","type":"string","length":80,"required":true,"listable":true}
                    ]}
                    """);
            Files.writeString(target.resolve("services/student.json"), """
                    {"services":[{"code":"createStudent","name":"创建学生","transactional":true,
                      "params":[{"name":"code","type":"string","required":true},{"name":"name","type":"string","required":true}],
                      "steps":[{"call":"student.create","map":{"code":"params.code","name":"params.name"}}]
                    }]}
                    """);
            return target;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @BeforeEach
    void clearState() {
        AuthContext.clear();
        handler.createCalls.set(0);
        handler.deleteCalls.set(0);
    }

    @AfterEach
    void clearIdentity() {
        AuthContext.clear();
    }

    @Test
    void onlyTheRegisteredModelUsesTheOverrideAndOtherActionsStillUseGenericCrud() {
        ObjectNode original = record("  Alice  ");
        JsonNode created = execute("student", "create", original);
        assertThat(created.path("name").asText()).isEqualTo("Alice");
        assertThat(original.path("name").asText()).isEqualTo("  Alice  ");
        assertThat(handler.createCalls).hasValue(1);

        ObjectNode update = identity(created).put("name", "Updated");
        JsonNode updated = execute("student", "update", update);
        assertThat(updated.path("name").asText()).isEqualTo("Updated");
        assertThat(updated.path("version").asInt()).isEqualTo(1);
        assertThat(execute("student", "read", mapper.createObjectNode().set("id", created.get("id"))).path("name").asText())
                .isEqualTo("Updated");
        assertThat(handler.createCalls).hasValue(1);

        JsonNode course = execute("course", "create", record("  Original course  "));
        assertThat(course.path("name").asText()).isEqualTo("  Original course  ");
        assertThat(handler.createCalls).hasValue(1);
    }

    @Test
    void rpcAndMetadataServicesUseTheSameModelRule() throws Exception {
        MockMvc http = MockMvcBuilders.standaloneSetup(controller).build();
        JsonNode rpc = mapper.readTree(http.perform(post("/rpc").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1,
                        "method", "student.create", "params", record("  From RPC  ")))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(rpc.path("result").path("name").asText()).isEqualTo("From RPC");
        JsonNode service = mapper.valueToTree(dispatcher.dispatch(engine.snapshot(), "service.createStudent", record("  From service  ")));
        assertThat(service.path("name").asText()).isEqualTo("From service");
        assertThat(handler.createCalls).hasValue(2);
    }

    @Test
    void mutationsAcrossModelsRollBackWhenTheHandlerFailsAfterSaving() {
        ObjectNode params = record("FAIL_AFTER_SAVE");
        assertThat(assertThrows(RpcException.class, () -> execute("student", "create", params)).getCode()).isEqualTo(-32011);
        assertThat(count("t_student", params.path("code").asText())).isZero();
        assertThat(count("t_course", params.path("code").asText() + "-side")).isZero();
        assertThat(handler.createCalls).hasValue(1);
    }

    @Test
    void defaultBatchCreateRunsEachCreateRuleAndRollsBackTheEntireBatch() {
        ObjectNode first = record("  Batch first  ");
        ObjectNode second = record("FAIL_AFTER_SAVE");
        ObjectNode batch = mapper.createObjectNode();
        batch.putArray("records").add(first).add(second);
        assertThrows(RpcException.class, () -> execute("student", "batchCreate", batch));
        assertThat(handler.createCalls).hasValue(2);
        assertThat(count("t_student", first.path("code").asText())).isZero();
        assertThat(count("t_student", second.path("code").asText())).isZero();
        assertThat(count("t_course", second.path("code").asText() + "-side")).isZero();

        ObjectNode success = mapper.createObjectNode();
        success.putArray("records").add(record("  One  ")).add(record("  Two  "));
        JsonNode result = execute("student", "batchCreate", success);
        assertThat(result.path("records").get(0).path("name").asText()).isEqualTo("One");
        assertThat(result.path("records").get(1).path("name").asText()).isEqualTo("Two");
    }

    @Test
    void defaultBatchDeleteCannotBypassADeleteRuleAndEarlierDeletesRollBack() {
        JsonNode first = execute("student", "create", record("Deletable"));
        JsonNode protectedRecord = execute("student", "create", record("PROTECTED"));
        ObjectNode batch = mapper.createObjectNode();
        batch.putArray("records").add(identity(first)).add(identity(protectedRecord));
        assertThat(assertThrows(RpcException.class, () -> execute("student", "batchDelete", batch)).getCode()).isEqualTo(-32011);
        assertThat(handler.deleteCalls).hasValue(2);
        assertThat(execute("student", "read", identity(first)).path("version").asInt()).isZero();
        assertThat(execute("student", "read", identity(protectedRecord)).path("name").asText()).isEqualTo("PROTECTED");

        execute("student", "delete", identity(first));
        assertThat(jdbc.queryForObject("SELECT deleted FROM t_student WHERE id=?", Boolean.class, first.path("id").asLong())).isTrue();
    }

    @Test
    void proceedingKeepsValidationVersionsRowScopeAndAppendOnlyConstraints() {
        assertThat(assertThrows(RpcException.class,
                () -> execute("student", "create", mapper.createObjectNode().put("name", "Missing code"))).getCode()).isEqualTo(-32001);
        AuthContext.set(identity("owner"));
        JsonNode own = execute("student", "create", record("Owned"));
        execute("student", "update", identity(own).put("name", "Changed"));
        assertThat(assertThrows(RpcException.class, () -> execute("student", "delete", identity(own))).getCode()).isEqualTo(-32006);

        AuthContext.set(identity("outsider"));
        assertThat(assertThrows(RpcException.class,
                () -> execute("student", "read", identity(own))).getCode()).isEqualTo(-32002);
        assertThat(assertThrows(RpcException.class,
                () -> execute("student", "delete", identity(own).put("version", 1))).getCode()).isEqualTo(-32002);

        AuthContext.set(identity("owner"));
        MetaSnapshot snapshot = engine.snapshot();
        ObjectNode appendOnly = snapshot.models().get("student").deepCopy();
        ((ObjectNode) appendOnly.get("model")).put("appendOnly", true);
        Map<String, JsonNode> models = new HashMap<>(snapshot.models());
        models.put("student", appendOnly);
        MetaSnapshot restricted = new MetaSnapshot(snapshot.version(), models, snapshot.views(), snapshot.services(), snapshot.menus());
        assertThat(assertThrows(RpcException.class, () -> crud.execute(restricted, "student", "delete", identity(own).put("version", 1))).getCode())
                .isEqualTo(-32602);
    }

    @Test
    void permissionsAreCheckedBeforeInvokingTheHandler() throws Exception {
        AuthContext.set(identity("unprivileged"));
        MockMvc http = MockMvcBuilders.standaloneSetup(controller).build();
        JsonNode denied = mapper.readTree(http.perform(post("/rpc").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1,
                        "method", "student.create", "params", record("Denied")))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(denied.path("error").path("code").asInt()).isEqualTo(-32021);
        assertThat(handler.createCalls).hasValue(0);
    }

    @Test
    void sameActionRecursionIsRejectedAndTheNextCallCanStillSucceed() {
        assertThat(assertThrows(RpcException.class, () -> execute("student", "create", record("RECURSE"))).getCode()).isEqualTo(-32603);
        assertThat(execute("student", "create", record("  After recursion  ")).path("name").asText()).isEqualTo("After recursion");
        assertThat(handler.createCalls).hasValue(2);
    }

    @Test
    void handlerMetadataIsACopyAndCannotChangeTheRunningDefinition() {
        execute("student", "create", record("TOUCH_META"));
        assertThat(engine.snapshot().models().get("student").path("model").path("name").asText()).isEqualTo("学生");
    }

    @Test
    void aReloadCannotRemoveAModelWithARegisteredHandler() throws Exception {
        Path model = META.resolve("models/student.json"), service = META.resolve("services/student.json");
        String modelText = Files.readString(model), serviceText = Files.readString(service);
        long version = engine.snapshot().version();
        try {
            Files.delete(model);
            Files.delete(service);
            assertThat(assertThrows(RpcException.class, engine::reload).getCode()).isEqualTo(-32005);
            assertThat(engine.snapshot().version()).isEqualTo(version);
            assertThat(engine.snapshot().models()).containsKey("student");
        } finally {
            Files.writeString(model, modelText);
            Files.writeString(service, serviceText);
            engine.reload();
        }
    }

    ObjectNode record(String name) {
        return mapper.createObjectNode().put("code", UUID.randomUUID().toString()).put("name", name);
    }

    ObjectNode identity(JsonNode record) {
        ObjectNode request = mapper.createObjectNode();
        request.set("id", record.get("id"));
        request.set("version", record.get("version"));
        return request;
    }

    static AuthContext.Identity identity(String username) {
        return new AuthContext.Identity(101, username, username, List.of("reader"), Set.of(), false, "test-csrf", "test-token");
    }

    JsonNode execute(String model, String action, JsonNode params) {
        return mapper.valueToTree(crud.execute(engine.snapshot(), model, action, params));
    }

    int count(String table, String code) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE code=?", Integer.class, code);
    }

    @TestConfiguration
    static class Handlers {
        // Replace the scanned production handler, retaining one handler for the student model.
        @Bean("studentCrudHandler") StudentHandler studentHandler(ObjectMapper mapper) { return new StudentHandler(mapper); }
    }

    static class StudentHandler extends StudentCrudHandler {
        final ObjectMapper mapper;
        final AtomicInteger createCalls = new AtomicInteger(), deleteCalls = new AtomicInteger();

        StudentHandler(ObjectMapper mapper) { this.mapper = mapper; }

        @Override public String modelCode() { return "student"; }

        @Override public Object create(ModelCrudContext context, JsonNode supplied) {
            createCalls.incrementAndGet();
            if (supplied.path("name").asText().equals("RECURSE")) return context.call("create", supplied);
            // Reading this model from create is allowed; only repeated model.action is recursive.
            context.call("list", mapper.createObjectNode().put("pageSize", 1));
            if (supplied.path("name").asText().equals("TOUCH_META"))
                ((ObjectNode) context.model().get("model")).put("name", "handler-local copy");
            ObjectNode params = supplied.deepCopy();
            if (params.path("name").isTextual()) params.put("name", params.path("name").asText().trim());
            Object result = super.create(context, params);
            if (params.path("name").asText().equals("FAIL_AFTER_SAVE")) {
                context.call("course", "create", mapper.createObjectNode().put("code", params.path("code").asText() + "-side").put("name", "侧写"));
                throw new RpcException(-32011, "模拟保存后的业务校验失败");
            }
            return result;
        }

        @Override public Object delete(ModelCrudContext context, JsonNode params) {
            deleteCalls.incrementAndGet();
            JsonNode row = mapper.valueToTree(context.call("read", mapper.createObjectNode().set("id", params.get("id"))));
            if (row.path("name").asText().equals("PROTECTED")) throw new RpcException(-32011, "受保护记录不能删除");
            return super.delete(context, params);
        }
    }
}
