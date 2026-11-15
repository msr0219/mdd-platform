package com.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.meta.MetaEngine;
import com.platform.rpc.RpcException;
import com.platform.service.CrudExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.platform.auth.AuthService;
import com.platform.auth.AuthFilter;
import jakarta.servlet.http.Cookie;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:platform-core-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "platform.demo.enabled=false", "platform.auth.bootstrap-password=InitialTest!12345"
})
@AutoConfigureMockMvc
class PlatformIntegrationTest {
    static final Path META = copyMetadata();
    @Autowired MetaEngine engine;
    @Autowired CrudExecutor crud;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc http;
    @Autowired AuthService auth;
    @Autowired AuthFilter filter;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    @BeforeEach void authenticatedHttp() {
        jdbc.update("UPDATE t_sys_user SET must_change=FALSE WHERE username='admin'");
        var login=auth.login("admin","InitialTest!12345","test");
        http=MockMvcBuilders.webAppContextSetup(context).defaultRequest(post("/").cookie(new Cookie("MDD_SESSION",login.token())).header("X-CSRF-Token",login.user().get("csrfToken"))).addFilters(filter).build();
    }

    static Path copyMetadata() {
        try {
            Path source = Path.of("../meta");
            if (!Files.isDirectory(source)) source = Path.of("meta");
            Path target = Files.createDirectories(Path.of("target/test-meta-" + UUID.randomUUID())).toAbsolutePath();
            try (var files = Files.walk(source)) {
                for (Path path : files.toList()) {
                    Path dest = target.resolve(source.relativize(path));
                    if (Files.isDirectory(path)) Files.createDirectories(dest); else Files.copy(path, dest);
                }
            }
            return target;
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("platform.meta-dir", () -> META.toString());
    }
    JsonNode call(String model, String action, ObjectNode data) {
        return mapper.valueToTree(crud.execute(engine.snapshot(), model, action, data));
    }
    ObjectNode obj() { return mapper.createObjectNode(); }
    ObjectNode student(String name) {
        return obj().put("studentNo", UUID.randomUUID().toString().replace("-", "").substring(0, 24)).put("name", name).put("age", 20);
    }

    @Test void scannedStudentHandlerNormalizesHttpCreatesAndKeepsRequiredValidation() throws Exception {
        ObjectNode params = student(" \t装配验证学生 \n");
        String originalStudentNo = "  H" + UUID.randomUUID().toString().replace("-", "").substring(0, 20) + "  ";
        params.put("studentNo", originalStudentNo);
        ObjectNode request = obj().put("jsonrpc", "2.0").put("id", 1).put("method", "student.create");
        request.set("params", params);
        JsonNode response = mapper.readTree(http.perform(post("/rpc").contentType("application/json").content(request.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertFalse(response.has("error"), response.toString());
        JsonNode created = response.path("result");
        assertEquals("装配验证学生", created.path("name").asText());
        assertEquals(originalStudentNo, created.path("studentNo").asText());
        var stored = jdbc.queryForMap("SELECT `name`,`studentNo` FROM `t_student` WHERE `id`=?", created.path("id").asLong());
        assertEquals("装配验证学生", stored.get("name"));
        assertEquals(originalStudentNo, stored.get("studentNo"));

        ObjectNode blank = student(" \t\r\n ");
        request.set("params", blank);
        JsonNode rejected = mapper.readTree(http.perform(post("/rpc").contentType("application/json").content(request.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(-32001, rejected.path("error").path("code").asInt());
        assertTrue(rejected.path("error").path("data").path("fields").findValuesAsText("code").contains("name"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM `t_student` WHERE `studentNo`=?", Integer.class, blank.path("studentNo").asText()));
    }

    @Test void modelCrudChecksVersionsAndSoftDeletion() {
        JsonNode created = call("student", "create", student("版本测试"));
        assertEquals(0, created.path("version").asInt());
        long id = created.path("id").asLong();
        JsonNode updated = call("student", "update", obj().put("id", id).put("version", 0).put("age", 21));
        assertEquals(21, updated.path("age").asInt());
        assertEquals(1, updated.path("version").asInt());
        assertThrows(RpcException.class, () -> call("student", "update", obj().put("id", id).put("version", 0).put("age", 99)));
        call("student", "delete", obj().put("id", id).put("version", 1));
        assertThrows(RpcException.class, () -> call("student", "read", obj().put("id", id)));
    }

    @Test void validationFilteringAndSqlIdentifiersAreEnforced() throws Exception {
        RpcException error = assertThrows(RpcException.class, () -> call("student", "create", student(" ").put("age", -1)));
        assertNotNull(error.getData());
        JsonNode created = call("student", "create", student("过滤专用"));
        ObjectNode filter = obj().put("page", 1).put("pageSize", 10);
        filter.set("filters", mapper.readTree("[{\"field\":\"name\",\"op\":\"like\",\"value\":\"过滤专用\"}]"));
        assertEquals(1, call("student", "list", filter).path("total").asInt());
        assertThrows(RpcException.class, () -> call("student", "list", obj().put("sortField", "name; DROP TABLE t_student")));
        ObjectNode badFilter = obj();
        badFilter.set("filters", mapper.readTree("[{\"field\":\"unknown\",\"op\":\"eq\",\"value\":1}]"));
        assertThrows(RpcException.class, () -> call("student", "list", badFilter));
        assertEquals(created.path("id").asLong(), call("student", "read", obj().put("id", created.path("id").asLong())).path("id").asLong());
    }

    @Test void stateChangesRequireConfiguredTransitions() {
        JsonNode work = call("work_order", "create", obj().put("orderNo", UUID.randomUUID().toString()).put("product", "测试整机").put("plannedQty", 2));
        long id = work.path("id").asLong();
        assertEquals("PLANNED", work.path("status").asText());
        assertThrows(RpcException.class, () -> call("work_order", "update", obj().put("id", id).put("version", 0).put("status", "COMPLETED")));
        assertThrows(RpcException.class, () -> call("work_order", "transition", obj().put("id", id).put("version", 0).put("to", "COMPLETED")));
        assertEquals("ASSEMBLING", call("work_order", "transition", obj().put("id", id).put("version", 0).put("to", "ASSEMBLING")).path("status").asText());
    }

    @Test void appendOnlyRecordsCannotBeChangedOrDeleted() {
        JsonNode batch = call("part_batch", "create", obj().put("batchNo", UUID.randomUUID().toString()).put("partName", "测试件").put("quantity", 5).put("qualityStatus", "GOOD"));
        JsonNode movement = call("stock_movement", "create", obj().put("batchId", batch.path("id").asLong()).put("type", "ISSUE").put("qty", 1));
        ObjectNode request = obj().put("id", movement.path("id").asLong()).put("version", 0);
        assertThrows(RpcException.class, () -> call("stock_movement", "delete", request));
        assertThrows(RpcException.class, () -> call("stock_movement", "update", request.put("qty", 2)));
    }

    @Test void reloadIsAtomicAndCanAddNewModelsWithoutCodeChanges() throws Exception {
        long before = engine.snapshot().version();
        Path invalid = META.resolve("models/broken.json");
        Files.writeString(invalid, "{broken");
        try {
            assertThrows(RuntimeException.class, engine::reload);
            assertEquals(before, engine.snapshot().version());
            assertTrue(engine.snapshot().models().containsKey("student"));
        } finally { Files.delete(invalid); }
        Path added = META.resolve("models/runtime_model.json");
        Files.writeString(added, "{\"model\":{\"code\":\"runtime_model\",\"name\":\"运行时模型\"},\"fields\":[{\"code\":\"title\",\"name\":\"标题\",\"type\":\"string\",\"required\":true,\"editable\":true,\"listable\":true,\"searchable\":true}]}");
        try {
            engine.reload();
            assertTrue(engine.snapshot().version() > before);
            assertEquals("热加载成功", call("runtime_model", "create", obj().put("title", "热加载成功")).path("title").asText());
        } finally { Files.delete(added); engine.reload(); }
    }

    @Test void jsonRpcRejectsMalformedRequestsAndHandlesNotifications() throws Exception {
        String response = http.perform(post("/rpc").contentType("application/json").content("{broken"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(-32700, mapper.readTree(response).path("error").path("code").asInt());
        response = http.perform(post("/rpc").contentType("application/json").content("{\"jsonrpc\":\"2.0\",\"method\":\"missing.action\",\"id\":7}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(7, mapper.readTree(response).path("id").asInt());
        assertEquals(-32601, mapper.readTree(response).path("error").path("code").asInt());
        http.perform(post("/rpc").contentType("application/json").content("{\"jsonrpc\":\"2.0\",\"method\":\"meta.status\"}"))
            .andExpect(status().isNoContent());
        response = http.perform(post("/rpc").contentType("application/json").content("[{\"jsonrpc\":\"2.0\",\"method\":\"meta.status\"},{\"jsonrpc\":\"2.0\",\"method\":\"meta.status\",\"id\":1}]"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(1, mapper.readTree(response).size());
    }
}
