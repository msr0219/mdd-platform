package com.platform;

import com.fasterxml.jackson.databind.*;
import com.platform.meta.MetaEngine;
import com.platform.rpc.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:lifecycle-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","platform.demo.enabled=false","platform.auth.bootstrap-password=InitialTest!12345"})
class ProductionLifecycleTest {
    @Autowired RpcDispatcher rpc;@Autowired MetaEngine engine;@Autowired ObjectMapper mapper;@Autowired JdbcTemplate jdbc;
    @DynamicPropertySource static void meta(DynamicPropertyRegistry r){r.add("platform.meta-dir",()->(Files.isDirectory(Path.of("../meta"))?Path.of("../meta"):Path.of("meta")).toAbsolutePath().toString());}
    JsonNode call(String method,Object params){return mapper.valueToTree(rpc.dispatch(engine.snapshot(),method,mapper.valueToTree(params)));}
    String id(){return UUID.randomUUID().toString();}
    long batch(String quality){return call("part_batch.create",Map.of("batchNo",id(),"partName","主板","quantity",10,"qualityStatus",quality,"supplier","生命周期供应商")).path("id").asLong();}
    long work(){return call("work_order.create",Map.of("orderNo",id(),"product","测试工作站","plannedQty",10)).path("id").asLong();}
    long assembly(long batch,long work){long assembly=call("assembly_record.create",Map.of("sn",id(),"batchId",batch,"workOrderId",work)).path("id").asLong();call("assembly_component.create",Map.of("assemblyId",assembly,"batchId",batch,"slot","PRIMARY","qty",2,"active",true));return assembly;}
    JsonNode read(String model,long id){return call(model+".read",Map.of("id",id));}
    JsonNode defect(long batch){return call("service.registerDefect",Map.of("requestId",id(),"batchId",batch,"qty",2,"reason","外观损伤"));}
    @Test void incomingFailureFreezesTraceChainThenReworkAndReinspectionRestoreIt() {
        long source=batch("GOOD"),replacement=batch("GOOD"),bad=batch("BAD"),work=work(),pc=assembly(source,work);
        String request=id();call("service.inspectIncoming",Map.of("requestId",request,"batchId",source,"qty",4,"passedQty",2,"workHours",1,"remark","来料外观抽检"));
        JsonNode listed=call("defect_record.list",Map.of("filters",List.of(Map.of("field","batchId","op","eq","value",source))));long defect=listed.path("records").get(0).path("id").asLong();
        assertEquals(8,read("part_batch",source).path("quantity").asInt());assertEquals("HOLD",read("assembly_record",pc).path("status").asText());assertEquals("FROZEN",read("work_order",work).path("status").asText());
        long component=call("assembly_component.list",Map.of("filters",List.of(Map.of("field","assemblyId","op","eq","value",pc)))).path("records").get(0).path("id").asLong();
        call("service.replaceComponent",Map.of("requestId",id(),"componentId",component,"defectId",defect,"replacementBatchId",replacement,"returnBatchId",bad,"reason","更换装机零件"));
        call("service.submitForRecheck",Map.of("requestId",id(),"defectId",defect));
        call("service.submitRecheck",Map.of("requestId",id(),"defectId",defect,"result","FAIL","workHours",1,"remark","上电检测失败"));
        assertEquals("REWORK",read("defect_record",defect).path("status").asText());assertEquals("FROZEN",read("work_order",work).path("status").asText());
        call("service.submitForRecheck",Map.of("requestId",id(),"defectId",defect));
        String passRequest=id();Map<String,Object> pass=Map.of("requestId",passRequest,"defectId",defect,"result","PASS","workHours",2,"remark","电气与外观检测全部合格");call("service.submitRecheck",pass);call("service.submitRecheck",pass);
        assertEquals("CLOSED",read("defect_record",defect).path("status").asText());assertEquals("RELEASED",read("assembly_record",pc).path("status").asText());assertEquals("PLANNED",read("work_order",work).path("status").asText());
        assertEquals(8,read("part_batch",replacement).path("quantity").asInt());assertEquals(12,read("part_batch",bad).path("quantity").asInt());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM t_quality_inspection WHERE batchId=?",Integer.class,source));
        JsonNode metrics=call("quality_inspection.aggregate",Map.of("groupBy",List.of("phase"),"filters",List.of(Map.of("field","batchId","op","eq","value",source)),"metrics",List.of(Map.of("op","sum","field","testedQty","as","tested"),Map.of("op","sum","field","passedQty","as","passed"))));
        assertEquals(2,metrics.path("records").size());
    }
    @Test void oneClosedDefectCannotReleaseAWorkOrderWithAnotherOpenDefect() {
        long batch=batch("GOOD"),work=work(),pc=assembly(batch,work);long d1=defect(batch).path("id").asLong(),d2=defect(batch).path("id").asLong();
        call("service.disposeDefect",Map.of("requestId",id(),"defectId",d1,"decision","CONCESSION","reason","审批同意让步接收"));assertEquals("HOLD",read("assembly_record",pc).path("status").asText());
        assertThrows(RpcException.class,()->call("service.disposeDefect",Map.of("requestId",id(),"defectId",d2,"decision","SCRAPPED","reason","已装机不得直接报废放行")));
        call("service.disposeDefect",Map.of("requestId",id(),"defectId",d2,"decision","CONCESSION","reason","审批同意让步接收"));assertEquals("RELEASED",read("assembly_record",pc).path("status").asText());assertEquals("PLANNED",read("work_order",work).path("status").asText());
        assertEquals(10,read("part_batch",batch).path("quantity").asInt());
    }
    @Test void downstreamFreezeFailureRollsBackDefectInventoryAndEvents() {
        long batch=batch("GOOD"),work=work(),pc=assembly(batch,work);jdbc.update("UPDATE t_assembly_record SET workOrderId=999999999 WHERE id=?",pc);
        String key=id();assertThrows(RpcException.class,()->call("service.registerDefect",Map.of("requestId",key,"batchId",batch,"qty",2,"reason","模拟冻结异常")));
        assertEquals(10,read("part_batch",batch).path("quantity").asInt());assertEquals("RELEASED",read("assembly_record",pc).path("status").asText());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM t_defect_record WHERE batchId=?",Integer.class,batch));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM t_service_idempotency WHERE request_key=?",Integer.class,key));
    }
    @Test void pendingBatchOnlyBecomesAvailableAfterWholeBatchInspection() {
        long batch=batch("PENDING");
        assertThrows(RpcException.class,()->call("service.inspectIncoming",Map.of("requestId",id(),"batchId",batch,"qty",5,"passedQty",5,"remark","禁止部分待检批次整体放行")));
        call("service.inspectIncoming",Map.of("requestId",id(),"batchId",batch,"qty",10,"passedQty",8,"remark","全检，2件不良"));
        assertEquals("GOOD",read("part_batch",batch).path("qualityStatus").asText());assertEquals(8,read("part_batch",batch).path("quantity").asInt());
    }
    @Test void frozenWorkOrderCannotBeAdvancedOrUsedForNewAssembly() {
        long batch=batch("GOOD"),work=work();
        call("service.advanceWorkOrder",Map.of("requestId",id(),"workOrderId",work,"to","ASSEMBLING"));
        call("service.registerAssembly",Map.of("requestId",id(),"sn",id(),"batchId",batch,"workOrderId",work));
        defect(batch);
        assertThrows(RpcException.class,()->call("service.advanceWorkOrder",Map.of("requestId",id(),"workOrderId",work,"to","TESTING")));
        assertThrows(RpcException.class,()->call("service.registerAssembly",Map.of("requestId",id(),"sn",id(),"batchId",batch,"workOrderId",work)));
    }
}
