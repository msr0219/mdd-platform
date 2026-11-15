package com.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.meta.MetaEngine;
import com.platform.meta.MetaSnapshot;
import com.platform.rpc.RpcDispatcher;
import com.platform.rpc.RpcException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exercises real SQL transactions and competing requests, independent of the seeded demo data. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:service_tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "platform.demo.enabled=false", "platform.auth.bootstrap-password=InitialTest!12345"
})
class ServiceIntegrationTest {
    @Autowired RpcDispatcher dispatcher;
    @Autowired MetaEngine engine;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void metadataLocation(DynamicPropertyRegistry registry) {
        Path location = Files.isDirectory(Path.of("meta")) ? Path.of("meta") : Path.of("..", "meta");
        registry.add("platform.meta-dir", () -> location.toAbsolutePath().normalize().toString());
    }

    @Test
    void successfulIssueAndReplayOnlyDeductOnceWithCanonicalParameters() {
        JsonNode batch = batch(20, "GOOD");
        JsonNode work = workOrder();
        String key = UUID.randomUUID().toString();
        ObjectNode params = issueParams(key, batch, work, 3);
        JsonNode first = call("service.issueMaterial", params);
        ObjectNode reordered = mapper.createObjectNode();
        reordered.set("qty", params.get("qty"));
        reordered.set("workOrderId", params.get("workOrderId"));
        reordered.set("batchId", params.get("batchId"));
        reordered.put("requestId", key);
        JsonNode replay = call("service.issueMaterial", reordered);
        assertThat(replay.toString()).isEqualTo(first.toString());
        assertThat(readBatch(batch).path("quantity").asInt()).isEqualTo(17);
        assertThat(movementCount(batch)).isEqualTo(1);
        assertThat(receiptCount(key)).isEqualTo(1);
    }

    @Test
    void sameKeyWithChangedParametersIsRejected() {
        JsonNode batch = batch(20, "GOOD");
        JsonNode work = workOrder();
        String key = UUID.randomUUID().toString();
        call("service.issueMaterial", issueParams(key, batch, work, 3));
        RpcException error = assertThrows(RpcException.class,
                () -> call("service.issueMaterial", issueParams(key, batch, work, 5)));
        assertThat(error.getCode()).isEqualTo(-32012);
        assertThat(readBatch(batch).path("quantity").asInt()).isEqualTo(17);
        assertThat(movementCount(batch)).isEqualTo(1);
    }

    @Test
    void laterStepFailureRollsBackStockAndIdempotencyClaim() {
        JsonNode batch = batch(10, "GOOD");
        JsonNode work = workOrder();
        String key = UUID.randomUUID().toString();
        ObjectNode params = issueParams(key, batch, work, 4);
        // Inject a failure after the inventory write, independently of the newly added work-order guard.
        MetaSnapshot snapshot=engine.snapshot();ObjectNode failing=snapshot.services().get("issueMaterial").deepCopy();
        ((ObjectNode)failing.path("steps").get(2).path("map")).putObject("workOrderId").put("const",9_999_999L);
        Map<String,JsonNode> serviceDefinitions=new HashMap<>(snapshot.services());serviceDefinitions.put("issueMaterial",failing);
        MetaSnapshot injected=new MetaSnapshot(snapshot.version(),snapshot.models(),snapshot.views(),serviceDefinitions,snapshot.menus());
        RpcException error = assertThrows(RpcException.class, () -> dispatcher.dispatch(injected,"service.issueMaterial",params));
        assertThat(error.getCode()).isEqualTo(-32010);
        assertThat(mapper.valueToTree(error.getData()).path("step").asInt()).isEqualTo(2);
        assertThat(readBatch(batch).path("quantity").asInt()).isEqualTo(10);
        assertThat(readBatch(batch).path("version").asInt()).isZero();
        assertThat(movementCount(batch)).isZero();
        assertThat(receiptCount(key)).isZero();
        // A rolled back attempt has no committed receipt, so correcting the same request is allowed.
        call("service.issueMaterial", issueParams(key, batch, work, 4));
        assertThat(readBatch(batch).path("quantity").asInt()).isEqualTo(6);
        assertThat(receiptCount(key)).isEqualTo(1);
    }

    @Test
    void guardRejectsNonGoodStockWithoutLeavingReceipt() {
        JsonNode batch = batch(10, "BAD");
        String key = UUID.randomUUID().toString();
        JsonNode work = workOrder();
        RpcException error = assertThrows(RpcException.class,
                () -> call("service.issueMaterial", issueParams(key, batch, work, 1)));
        assertThat(error.getCode()).isEqualTo(-32011);
        assertThat(readBatch(batch).path("quantity").asInt()).isEqualTo(10);
        assertThat(receiptCount(key)).isZero();
    }

    @Test
    void unknownAndInvalidParametersFailBeforeExecution() {
        JsonNode batch = batch(10, "GOOD");
        JsonNode work = workOrder();
        ObjectNode params = issueParams(UUID.randomUUID().toString(), batch, work, 1);
        params.put("injectedAdmin", true);
        assertThatThrownBy(() -> call("service.issueMaterial", params))
                .isInstanceOfSatisfying(RpcException.class, error -> assertThat(error.getCode()).isEqualTo(-32602));
        params.remove("injectedAdmin");
        params.put("qty", 0);
        assertThatThrownBy(() -> call("service.issueMaterial", params))
                .isInstanceOfSatisfying(RpcException.class, error -> assertThat(error.getCode()).isEqualTo(-32602));
        assertThat(readBatch(batch).path("quantity").asInt()).isEqualTo(10);
    }

    @Test
    void concurrentSameKeyWithDifferentQuantitiesCannotDeductTwice() throws Exception {
        JsonNode batch = batch(10, "GOOD");
        JsonNode work = workOrder();
        String key = UUID.randomUUID().toString();
        List<Attempt> outcomes = race(List.of(
                issueParams(key, batch, work, 2), issueParams(key, batch, work, 3)));
        List<Attempt> successes = outcomes.stream().filter(result -> result.error == null).toList();
        assertThat(successes).hasSize(1);
        assertThat(outcomes.stream().filter(result -> result.error != null).toList()).hasSize(1);
        assertThat(outcomes.stream().filter(result -> result.error != null).findFirst().orElseThrow().error.getCode())
                .isEqualTo(-32012);
        assertThat(readBatch(batch).path("quantity").asInt()).isEqualTo(10 - successes.get(0).qty);
        assertThat(movementCount(batch)).isEqualTo(1);
        assertThat(receiptCount(key)).isEqualTo(1);
    }

    @Test
    void concurrentDistinctRequestsNeverOversell() throws Exception {
        JsonNode batch = batch(3, "GOOD");
        JsonNode work = workOrder();
        List<ObjectNode> requests = new ArrayList<>();
        for (int i = 0; i < 6; i++) requests.add(issueParams(UUID.randomUUID().toString(), batch, work, 2));
        List<Attempt> outcomes = race(requests);
        long successful = outcomes.stream().filter(result -> result.error == null).count();
        assertThat(successful).isEqualTo(1);
        assertThat(readBatch(batch).path("quantity").asInt()).isEqualTo(1);
        assertThat(movementCount(batch)).isEqualTo(1);
        assertThat(outcomes.stream().filter(result -> result.error != null)
                .allMatch(result -> result.error.getCode() == -32010)).isTrue();
    }

    @Test
    void configuredDefectAndReworkMoveInventoryInOneTransaction() {
        JsonNode original = batch(10, "GOOD");
        JsonNode replacement = batch(10, "GOOD");
        JsonNode returned = batch(0, "BAD");
        JsonNode defect = call("service.registerDefect", Map.of(
                "requestId", UUID.randomUUID().toString(), "batchId", original.path("id").asLong(),
                "qty", 2, "reason", "检测发现引脚损坏"));
        assertThat(defect.path("status").asText()).isEqualTo("ISOLATED");
        assertThat(readBatch(original).path("quantity").asInt()).isEqualTo(8);
        JsonNode rework = call("service.rework", Map.of(
                "requestId", UUID.randomUUID().toString(), "defectId", defect.path("id").asLong(),
                "replacementBatchId", replacement.path("id").asLong(),
                "returnBatchId", returned.path("id").asLong()));
        assertThat(rework.path("defect").path("status").asText()).isEqualTo("REWORK");
        assertThat(rework.path("record").path("qty").asInt()).isEqualTo(2);
        assertThat(readBatch(replacement).path("quantity").asInt()).isEqualTo(8);
        assertThat(readBatch(returned).path("quantity").asInt()).isEqualTo(2);
        assertThat(movementCount(replacement)).isEqualTo(1);
        assertThat(movementCount(returned)).isEqualTo(1);
    }

    @Test
    void reworkRejectsAReplacementOfADifferentPart() {
        assertMismatchedPartRejected("主板", "内存");
    }

    @Test
    void reworkRejectsAReturnBatchOfADifferentPart() {
        assertMismatchedPartRejected("内存", "主板");
    }

    private void assertMismatchedPartRejected(String replacementPart, String returnPart) {
        JsonNode original = batch(10, "GOOD", "内存");
        JsonNode replacement = batch(10, "GOOD", replacementPart);
        JsonNode returned = batch(0, "BAD", returnPart);
        JsonNode defect = call("service.registerDefect", Map.of(
                "requestId", UUID.randomUUID().toString(), "batchId", original.path("id").asLong(),
                "qty", 2, "reason", "零件不良"));
        String key = UUID.randomUUID().toString();
        RpcException error = assertThrows(RpcException.class, () -> call("service.rework", Map.of(
                "requestId", key, "defectId", defect.path("id").asLong(),
                "replacementBatchId", replacement.path("id").asLong(),
                "returnBatchId", returned.path("id").asLong())));
        assertThat(error.getCode()).isEqualTo(-32011);
        assertThat(error.getMessage()).contains("与原零件相同");
        assertThat(readBatch(original).path("quantity").asInt()).isEqualTo(8);
        assertThat(readBatch(replacement).path("quantity").asInt()).isEqualTo(10);
        assertThat(readBatch(returned).path("quantity").asInt()).isZero();
        assertThat(readBatch(replacement).path("version").asInt()).isZero();
        assertThat(readBatch(returned).path("version").asInt()).isZero();
        assertThat(call("defect_record.read", Map.of("id", defect.path("id").asLong()))
                .path("status").asText()).isEqualTo("ISOLATED");
        assertThat(movementCount(replacement)).isZero();
        assertThat(movementCount(returned)).isZero();
        assertThat(call("rework_record.list", Map.of("filters", List.of(
                Map.of("field", "defectId", "op", "eq", "value", defect.path("id").asLong()))))
                .path("total").asInt()).isZero();
        assertThat(receiptCount(key)).isZero();
    }

    @Test
    void mutationAfterGuardCannotUseANewVersionToBypassItsDecision() throws Exception {
        JsonNode batch = batch(10, "GOOD");
        String key = UUID.randomUUID().toString();
        JsonNode definition = mapper.readTree("""
                {
                  "code":"guardVersionCheck", "transactional":true, "idempotencyKey":"requestId",
                  "params":[{"name":"requestId","type":"string","required":true},
                            {"name":"batchId","type":"ref","ref":"part_batch","required":true}],
                  "guards":[{"call":"part_batch.read","map":{"id":"params.batchId"},
                    "condition":{"left":"result.qualityStatus","op":"eq","right":{"const":"GOOD"}}}],
                  "steps":[
                    {"call":"part_batch.update","map":{"id":"params.batchId",
                      "version":"guards[0].result.version","qualityStatus":{"const":"BAD"}}},
                    {"call":"part_batch.adjust","map":{"id":"params.batchId","field":{"const":"quantity"},
                      "delta":{"const":-1},"version":"guards[0].result.version"}}
                  ]
                }
                """);
        // Deterministically invalidate the guard's snapshot before the guarded write, without timing sleeps.
        MetaSnapshot current = engine.snapshot();
        Map<String, JsonNode> services = new HashMap<>(current.services());
        services.put("guardVersionCheck", definition);
        MetaSnapshot testSnapshot = new MetaSnapshot(current.version(), current.models(), current.views(), services, current.menus());
        JsonNode params = mapper.valueToTree(Map.of("requestId", key, "batchId", batch.path("id").asLong()));
        RpcException error = assertThrows(RpcException.class,
                () -> dispatcher.dispatch(testSnapshot, "service.guardVersionCheck", params));
        assertThat(error.getCode()).isEqualTo(-32010);
        assertThat(mapper.valueToTree(error.getData()).path("step").asInt()).isEqualTo(1);
        assertThat(readBatch(batch).path("qualityStatus").asText()).isEqualTo("GOOD");
        assertThat(readBatch(batch).path("quantity").asInt()).isEqualTo(10);
        assertThat(readBatch(batch).path("version").asInt()).isZero();
        assertThat(receiptCount(key)).isZero();
    }

    private List<Attempt> race(List<ObjectNode> requests) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(requests.size());
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Attempt>> futures = new ArrayList<>();
        try {
            for (ObjectNode request : requests) futures.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start gate timed out");
                try {
                    call("service.issueMaterial", request);
                    return new Attempt(request.path("qty").asInt(), null);
                } catch (RpcException error) {
                    return new Attempt(request.path("qty").asInt(), error);
                }
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Attempt> outcomes = new ArrayList<>();
            for (Future<Attempt> future : futures) outcomes.add(future.get(30, TimeUnit.SECONDS));
            return outcomes;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private JsonNode batch(int quantity, String quality) {
        return batch(quantity, quality, "测试零件");
    }

    private JsonNode batch(int quantity, String quality, String partName) {
        return call("part_batch.create", Map.of("batchNo", "TEST-" + UUID.randomUUID(),
                "partName", partName, "quantity", quantity, "qualityStatus", quality));
    }

    private JsonNode workOrder() {
        return call("work_order.create", Map.of("orderNo", "TEST-" + UUID.randomUUID(),
                "product", "测试整机", "plannedQty", 10));
    }

    private ObjectNode issueParams(String key, JsonNode batch, JsonNode workOrder, int quantity) {
        return mapper.createObjectNode().put("requestId", key).put("batchId", batch.path("id").asLong())
                .put("workOrderId", workOrder.path("id").asLong()).put("qty", quantity);
    }

    private JsonNode readBatch(JsonNode batch) {
        return call("part_batch.read", Map.of("id", batch.path("id").asLong()));
    }

    private long movementCount(JsonNode batch) {
        JsonNode result = call("stock_movement.list", Map.of("filters", List.of(
                Map.of("field", "batchId", "op", "eq", "value", batch.path("id").asLong()))));
        return result.path("total").asLong();
    }

    private long receiptCount(String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_service_idempotency WHERE request_key=?", Long.class, key);
    }

    private JsonNode call(String method, Object params) {
        return mapper.valueToTree(dispatcher.dispatch(engine.snapshot(), method, mapper.valueToTree(params)));
    }

    private record Attempt(int qty, RpcException error) {}
}
