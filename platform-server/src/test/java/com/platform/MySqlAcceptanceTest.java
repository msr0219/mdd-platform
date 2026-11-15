package com.platform;

import com.fasterxml.jackson.databind.*;
import com.platform.meta.MetaEngine;
import com.platform.rpc.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Supply a dedicated test database; never point these environment variables at business data. */
@EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL",matches="jdbc:mysql:.*")
@SpringBootTest(properties={"spring.datasource.url=${MYSQL_TEST_URL}","spring.datasource.username=${MYSQL_TEST_USERNAME}","spring.datasource.password=${MYSQL_TEST_PASSWORD}","platform.demo.enabled=false","platform.auth.bootstrap-password=InitialTest!12345"})
class MySqlAcceptanceTest extends BomContract {
    @Autowired RpcDispatcher rpc;@Autowired MetaEngine engine;@Autowired ObjectMapper mapper;
    @DynamicPropertySource static void meta(DynamicPropertyRegistry r){r.add("platform.meta-dir",()->(Files.isDirectory(Path.of("../meta"))?Path.of("../meta"):Path.of("meta")).toAbsolutePath().toString());}
    JsonNode call(String method,Object params){return mapper.valueToTree(rpc.dispatch(engine.snapshot(),method,mapper.valueToTree(params)));}
    @Test void mysqlDdlCrudIdempotencyAndRollback() {
        String suffix=UUID.randomUUID().toString();
        JsonNode batch=call("part_batch.create",Map.of("batchNo",suffix,"partName","MySQL验收件","quantity",30,"qualityStatus","GOOD"));
        JsonNode work=call("work_order.create",Map.of("orderNo",suffix,"product","MySQL验收工单","plannedQty",10));
        var issue=Map.of("requestId",suffix,"batchId",batch.path("id").asLong(),"workOrderId",work.path("id").asLong(),"qty",3);
        call("service.issueMaterial",issue);call("service.issueMaterial",issue);
        assertEquals(27,call("part_batch.read",Map.of("id",batch.path("id").asLong())).path("quantity").asInt());
        assertThrows(RpcException.class,()->call("service.issueMaterial",Map.of("requestId",suffix+"-over","batchId",batch.path("id").asLong(),"workOrderId",work.path("id").asLong(),"qty",100)));
        assertEquals(27,call("part_batch.read",Map.of("id",batch.path("id").asLong())).path("quantity").asInt());
    }
}
