package com.platform;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.platform.meta.*;
import com.platform.rpc.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** The same behavioral contract runs on isolated H2 and the explicitly configured MySQL test database. */
abstract class TraceabilityContract {
    @Autowired RpcDispatcher dispatcher;@Autowired MetaEngine engine;@Autowired ObjectMapper mapper;@Autowired JdbcTemplate jdbc;@Autowired DataBackfillRunner backfill;
    JsonNode invoke(String method,Object params){return mapper.valueToTree(dispatcher.dispatch(engine.snapshot(),method,mapper.valueToTree(params)));}
    String key(){return UUID.randomUUID().toString();}
    Map<String,Object> filter(String field,Object value){return Map.of("field",field,"op","eq","value",value);}
    JsonNode readRecord(String model,long id){return invoke(model+".read",Map.of("id",id));}
    long part(String name,String quality){return invoke("part_batch.create",Map.of("batchNo",key(),"partName",name,"quantity",20,"qualityStatus",quality)).path("id").asLong();}
    long order(){long id=invoke("service.createWorkOrder",Map.of("requestId",key(),"orderNo",key(),"product","多零件验收整机","plannedQty",10)).path("id").asLong();invoke("service.advanceWorkOrder",Map.of("requestId",key(),"workOrderId",id,"to","ASSEMBLING"));return id;}
    long pc(long main,long work){return invoke("service.registerAssembly",Map.of("requestId",key(),"sn",key(),"workOrderId",work,"batchId",main)).path("id").asLong();}
    long attach(long pc,long batch,String slot,int qty){return invoke("service.attachComponent",Map.of("requestId",key(),"assemblyId",pc,"batchId",batch,"slot",slot,"qty",qty)).path("id").asLong();}
    long fault(long batch,int qty){return invoke("service.registerDefect",Map.of("requestId",key(),"batchId",batch,"qty",qty,"reason","多零件质量验证")).path("id").asLong();}
    JsonNode paths(String model,String code){for(JsonNode relation:engine.snapshot().models().get(model).path("relations"))if(relation.path("code").asText().equals(code))return relation.get("paths");throw new IllegalStateException(code);}
    JsonNode related(String source,long id,String target,String relation,int page,int size){return invoke(source+".related",Map.of("id",id,"model",target,"paths",paths(source,relation),"page",page,"pageSize",size));}
    void concede(long defect){invoke("service.disposeDefect",Map.of("requestId",key(),"defectId",defect,"decision","CONCESSION","reason","质量负责人批准让步接收"));}
    @Test void graphQueriesDeduplicateMultipleSlotsAndPaginateDistinctAssemblies() {
        long main=part("主板","GOOD"),ram=part("内存","GOOD"),work=order(),first=pc(main,work),second=pc(main,work);
        attach(first,ram,"DIMM_A",1);attach(first,ram,"DIMM_B",1);attach(second,ram,"DIMM_A",1);
        JsonNode result=related("part_batch",ram,"assembly_record","current_assemblies",1,1);
        assertEquals(2,result.path("total").asInt());assertEquals(1,result.path("records").size());
        assertNotEquals(result.path("records").get(0).path("id"),related("part_batch",ram,"assembly_record","current_assemblies",2,1).path("records").get(0).path("id"));
        assertEquals(2,related("part_batch",main,"assembly_record","current_assemblies",1,20).path("total").asInt());
        assertEquals(2,related("assembly_record",first,"part_batch","current_batches",1,20).path("total").asInt());
    }
    @Test void aSecondaryBatchFreezesTheWholePcAndOtherOpenPartsPreventRelease() {
        long main=part("主板","GOOD"),ram=part("内存","GOOD"),work=order(),pc=pc(main,work);attach(pc,ram,"DIMM_A",1);
        long ramFault=fault(ram,1);assertEquals("HOLD",readRecord("assembly_record",pc).path("status").asText());assertEquals("FROZEN",readRecord("work_order",work).path("status").asText());
        long mainFault=fault(main,1);concede(ramFault);assertEquals("HOLD",readRecord("assembly_record",pc).path("status").asText());
        concede(mainFault);assertEquals("RELEASED",readRecord("assembly_record",pc).path("status").asText());assertEquals("ASSEMBLING",readRecord("work_order",work).path("status").asText());
    }
    @Test void installedReplacementKeepsHistoryAndDoesNotReleaseBeforeReinspection() {
        long main=part("主板","GOOD"),replacement=part("主板","GOOD"),bad=part("主板","BAD"),ram=part("内存","GOOD"),work=order(),pc=pc(main,work);attach(pc,ram,"DIMM_A",1);
        long defect=fault(main,1),other=fault(ram,1);
        JsonNode original=invoke("assembly_component.list",Map.of("filters",List.of(filter("assemblyId",pc),filter("slot","PRIMARY")))).path("records").get(0);
        Map<String,Object> params=Map.of("requestId",key(),"componentId",original.path("id").asLong(),"defectId",defect,"replacementBatchId",replacement,"returnBatchId",bad,"reason","替换故障主板");
        assertThrows(RpcException.class,()->invoke("service.rework",Map.of("requestId",key(),"defectId",defect,"replacementBatchId",replacement,"returnBatchId",bad)));
        JsonNode swapped=invoke("service.replaceComponent",params);assertEquals(swapped.toString(),invoke("service.replaceComponent",params).toString());
        assertFalse(readRecord("assembly_component",original.path("id").asLong()).path("active").asBoolean());assertEquals(replacement,readRecord("assembly_record",pc).path("batchId").asLong());
        assertEquals(0,related("part_batch",main,"assembly_record","current_assemblies",1,20).path("total").asInt());assertEquals(1,related("part_batch",main,"assembly_record","historical_assemblies",1,20).path("total").asInt());
        assertEquals(19,readRecord("part_batch",replacement).path("quantity").asInt());assertEquals(21,readRecord("part_batch",bad).path("quantity").asInt());
        invoke("service.submitForRecheck",Map.of("requestId",key(),"defectId",defect));invoke("service.submitRecheck",Map.of("requestId",key(),"defectId",defect,"result","PASS","remark","更换后全部检验通过"));
        assertEquals("HOLD",readRecord("assembly_record",pc).path("status").asText());concede(other);assertEquals("RELEASED",readRecord("assembly_record",pc).path("status").asText());
        assertThrows(RpcException.class,()->invoke("component_change.delete",Map.of("id",swapped.path("history").path("id").asLong(),"version",0)));
    }
    @Test void legacyBackfillDoesNotDuplicateOrOverwriteReplacedPrimaryComponents() throws Exception {
        long main=part("旧主板","GOOD"),work=order();long pc=invoke("assembly_record.create",Map.of("sn",key(),"workOrderId",work,"batchId",main)).path("id").asLong();
        backfill.apply();backfill.apply();JsonNode parts=invoke("assembly_component.list",Map.of("filters",List.of(filter("assemblyId",pc))));assertEquals(1,parts.path("total").asInt());
        JsonNode old=parts.path("records").get(0);invoke("assembly_component.update",Map.of("id",old.path("id").asLong(),"version",old.path("version").asLong(),"active",false));backfill.apply();
        assertEquals(1,invoke("assembly_component.list",Map.of("filters",List.of(filter("assemblyId",pc)))).path("total").asInt());assertFalse(readRecord("assembly_component",old.path("id").asLong()).path("active").asBoolean());
    }
    @Test void undeclaredFieldsDirectionsAndOversizedPathsAreRejectedBeforeSql() {
        long batch=part("防注入件","GOOD");
        for(Object paths:List.of(List.of(List.of(Map.of("model","assembly_record","field","batchId;DROP_TABLE","direction","in"))),List.of(List.of(Map.of("model","assembly_record","field","batchId","direction","out"))),List.of(List.of(Map.of("model","assembly_record","field","batchId","direction","in","filters",List.of(filter("unknown",1))))))){
            RpcException error=assertThrows(RpcException.class,()->invoke("part_batch.related",Map.of("id",batch,"model","assembly_record","paths",paths)));assertEquals(-32602,error.getCode());
        }
        assertNotNull(readRecord("part_batch",batch));
    }
    @Test void failureAfterReplacementHistoryRollsBackInventoryLinksAndReceipt() {
        long main=part("主板","GOOD"),replacement=part("主板","GOOD"),bad=part("主板","BAD"),work=order(),pc=pc(main,work),defect=fault(main,1);
        long component=invoke("assembly_component.list",Map.of("filters",List.of(filter("assemblyId",pc)))).path("records").get(0).path("id").asLong();
        String request=key();var params=Map.of("requestId",request,"componentId",component,"defectId",defect,"replacementBatchId",replacement,"returnBatchId",bad,"reason","故障注入验收");
        MetaSnapshot current=engine.snapshot();ObjectNode failing=current.services().get("replaceComponent").deepCopy();((ArrayNode)failing.get("steps")).addObject().put("call","work_order.read").putObject("map").putObject("id").put("const",Long.MAX_VALUE);
        var definitions=new HashMap<>(current.services());definitions.put("replaceComponent",failing);MetaSnapshot injected=new MetaSnapshot(current.version(),current.models(),current.views(),definitions,current.menus());
        assertThrows(RpcException.class,()->dispatcher.dispatch(injected,"service.replaceComponent",mapper.valueToTree(params)));
        assertTrue(readRecord("assembly_component",component).path("active").asBoolean());assertEquals(main,readRecord("assembly_record",pc).path("batchId").asLong());assertEquals(20,readRecord("part_batch",replacement).path("quantity").asInt());assertEquals(20,readRecord("part_batch",bad).path("quantity").asInt());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM t_component_change WHERE defectId=?",Integer.class,defect));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM t_service_idempotency WHERE request_key=?",Integer.class,request));invoke("service.replaceComponent",params);
    }
    @Test void competingAttachmentsCannotOccupyTheSameSlotTwice() throws Exception {
        long main=part("主板","GOOD"),first=part("内存","GOOD"),second=part("内存","GOOD"),work=order(),pc=pc(main,work);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try{List<Future<Boolean>> attempts=new ArrayList<>();for(long batch:List.of(first,second))attempts.add(pool.submit(()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("race timeout");try{attach(pc,batch,"DIMM_RACE",1);return true;}catch(RpcException ex){return false;}}));
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();int success=0;for(var result:attempts)if(result.get(20,TimeUnit.SECONDS))success++;assertEquals(1,success);
            assertEquals(1,invoke("assembly_component.list",Map.of("filters",List.of(filter("assemblyId",pc),filter("slot","DIMM_RACE"),filter("active",true)))).path("total").asInt());
        }finally{pool.shutdownNow();}
    }
}
