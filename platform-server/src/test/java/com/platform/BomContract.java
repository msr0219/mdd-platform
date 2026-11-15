package com.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.meta.MetaSnapshot;
import com.platform.rpc.RpcException;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** BOM and issuance contracts shared by isolated H2 and the dedicated MySQL acceptance database. */
abstract class BomContract extends TraceabilityContract {
    long draftBom() {
        return invoke("service.createBom",Map.of("requestId",key(),"code",key(),"product","BOM验收整机","revision","V1")).path("id").asLong();
    }
    void item(long bom,String slot,String name,int qty) {
        invoke("service.addBomItem",Map.of("requestId",key(),"bomId",bom,"slot",slot,"partName",name,"qty",qty));
    }
    long publishedBom(boolean withRam) {
        long bom=draftBom();item(bom,"PRIMARY","BOM主板",1);
        if(withRam)item(bom,"DIMM_A","BOM内存",2);
        invoke("service.publishBom",Map.of("requestId",key(),"bomId",bom));return bom;
    }
    long bomOrder(long bom,int qty) {
        return invoke("service.createBomWorkOrder",Map.of("requestId",key(),"orderNo",key(),"bomId",bom,"plannedQty",qty)).path("id").asLong();
    }
    void advance(long order,String to) {
        invoke("service.advanceWorkOrder",Map.of("requestId",key(),"workOrderId",order,"to",to));
    }
    void issue(long order,long batch,int qty) {
        invoke("service.issueMaterial",Map.of("requestId",key(),"workOrderId",order,"batchId",batch,"qty",qty));
    }
    JsonNode consumed(long order) {
        return invoke("material_consumption.list",Map.of("filters",List.of(filter("workOrderId",order))));
    }
    long assembledCount(long order) {
        return invoke("assembly_record.list",Map.of("filters",List.of(filter("workOrderId",order)))).path("total").asLong();
    }
    JsonNode balance(long work,long batch) {
        return invoke("service.materialBalance",Map.of("workOrderId",work,"batchId",batch));
    }
    JsonNode returnUnused(long work,long batch,int qty) {
        return invoke("service.returnUnusedMaterial",returnParams(key(),work,batch,qty));
    }
    Map<String,Object> returnParams(String request,long work,long batch,int qty) {
        return Map.of("requestId",request,"workOrderId",work,"batchId",batch,"qty",qty,"reason","工单余料退回原批次");
    }
    int returned(long work,long batch) {
        return balance(work,batch).path("returnedQty").asInt();
    }

    @Test void unusedReturnsRestoreStockOnceAndLeaveAnImmutableReceiptWithReason() {
        long work=bomOrder(publishedBom(false),1),batch=part("BOM主板","GOOD");
        assertEquals(0,balance(work,batch).path("availableQty").asInt());
        issue(work,batch,5);
        var params=returnParams(key(),work,batch,2);
        JsonNode receipt=invoke("service.returnUnusedMaterial",params);
        assertEquals(receipt.toString(),invoke("service.returnUnusedMaterial",params).toString());
        assertEquals(17,readRecord("part_batch",batch).path("quantity").asInt());
        assertEquals(3,receipt.path("availableQty").asInt());
        JsonNode movement=receipt.path("movement");
        assertEquals("RETURN_UNUSED",movement.path("type").asText());
        assertEquals("工单余料退回原批次",movement.path("remark").asText());
        assertEquals(work,movement.path("workOrderId").asLong());
        assertEquals(2,returned(work,batch));
        assertThrows(RpcException.class,()->invoke("stock_movement.delete",Map.of("id",movement.path("id").asLong(),"version",movement.path("version").asLong())));
        var changed=new HashMap<>(params);changed.put("qty",1);
        assertThrows(RpcException.class,()->invoke("service.returnUnusedMaterial",changed));
        assertEquals(17,readRecord("part_batch",batch).path("quantity").asInt());
    }

    @Test void onlyUnconsumedBudgetCanBeReturnedEvenAfterWorkOrderCompletion() {
        long work=bomOrder(publishedBom(false),1),batch=part("BOM主板","GOOD");
        issue(work,batch,3);advance(work,"ASSEMBLING");pc(batch,work);
        advance(work,"TESTING");advance(work,"COMPLETED");
        assertEquals(1,balance(work,batch).path("consumedQty").asInt());
        assertThrows(RpcException.class,()->returnUnused(work,batch,3));
        returnUnused(work,batch,2);
        assertEquals(19,readRecord("part_batch",batch).path("quantity").asInt());
        assertEquals(0,balance(work,batch).path("availableQty").asInt());
        assertThrows(RpcException.class,()->returnUnused(work,batch,1));
        assertEquals("COMPLETED",readRecord("work_order",work).path("status").asText());
    }

    @Test void returnedMaterialsCannotBeInstalledUntilTheyAreIssuedAgain() {
        long work=bomOrder(publishedBom(false),1),batch=part("BOM主板","GOOD");
        issue(work,batch,1);returnUnused(work,batch,1);advance(work,"ASSEMBLING");
        assertThrows(RpcException.class,()->pc(batch,work));
        assertEquals(0,assembledCount(work));assertEquals(0,consumed(work).path("total").asInt());
        issue(work,batch,1);pc(batch,work);
        assertEquals(2,balance(work,batch).path("issuedQty").asInt());
        assertEquals(1,returned(work,batch));assertEquals(0,balance(work,batch).path("availableQty").asInt());
        assertTrue(invoke("service.checkBomKit",Map.of("workOrderId",work)).path("ready").asBoolean());
    }

    @Test void returnedBudgetAlsoBlocksSecondaryComponentInstallation() {
        long work=bomOrder(publishedBom(true),1),main=part("BOM主板","GOOD"),ram=part("BOM内存","GOOD");
        issue(work,main,1);issue(work,ram,2);advance(work,"ASSEMBLING");long pc=pc(main,work);
        returnUnused(work,ram,1);
        assertThrows(RpcException.class,()->attach(pc,ram,"DIMM_A",2));
        assertEquals(1,consumed(work).path("total").asInt());
        issue(work,ram,1);attach(pc,ram,"DIMM_A",2);
        assertEquals(0,balance(work,ram).path("availableQty").asInt());
    }

    @Test void returnsAreScopedToTheExactWorkOrderAndBatchAndValidateInput() {
        long bom=publishedBom(false),work=bomOrder(bom,1),other=bomOrder(bom,1);
        long batch=part("BOM主板","GOOD"),otherBatch=part("BOM主板","GOOD");issue(work,batch,2);
        assertThrows(RpcException.class,()->returnUnused(other,batch,1));
        assertThrows(RpcException.class,()->returnUnused(work,otherBatch,1));
        assertThrows(RpcException.class,()->returnUnused(work,batch,0));
        assertThrows(RpcException.class,()->returnUnused(work,batch,-1));
        var missingReason=new HashMap<>(returnParams(key(),work,batch,1));missingReason.put("reason"," ");
        assertThrows(RpcException.class,()->invoke("service.returnUnusedMaterial",missingReason));
        assertEquals(2,balance(work,batch).path("availableQty").asInt());
        assertEquals(18,readRecord("part_batch",batch).path("quantity").asInt());
    }

    @Test void legacyFrozenAndQualityProblemMaterialsCannotBeReturnedAsGoodStock() {
        long legacy=order(),batch=part("BOM主板","GOOD");issue(legacy,batch,2);
        assertThrows(RpcException.class,()->returnUnused(legacy,batch,1));
        assertThrows(RpcException.class,()->balance(legacy,batch));
        long work=bomOrder(publishedBom(false),1),bad=part("BOM主板","BAD");issue(work,batch,2);
        assertThrows(RpcException.class,()->returnUnused(work,bad,1));
        fault(batch,1);
        assertThrows(RpcException.class,()->returnUnused(work,batch,1));
        long good=part("BOM主板","GOOD");issue(work,good,1);
        JsonNode order=readRecord("work_order",work);
        invoke("work_order.transition",Map.of("id",work,"version",order.path("version").asLong(),"to","FROZEN"));
        assertThrows(RpcException.class,()->returnUnused(work,good,1));
        assertEquals(0,returned(work,good));
    }

    @Test void failureAfterReturnRollsBackStockOrderLedgerAndReceipt() {
        long work=bomOrder(publishedBom(false),1),batch=part("BOM主板","GOOD");issue(work,batch,2);
        JsonNode before=readRecord("work_order",work);
        String request=key();var params=returnParams(request,work,batch,1);
        MetaSnapshot current=engine.snapshot();ObjectNode failing=current.services().get("returnUnusedMaterial").deepCopy();
        ((ArrayNode)failing.get("steps")).addObject().put("call","work_order.read").putObject("map").putObject("id").put("const",Long.MAX_VALUE);
        var services=new HashMap<>(current.services());services.put("returnUnusedMaterial",failing);
        MetaSnapshot injected=new MetaSnapshot(current.version(),current.models(),current.views(),services,current.menus());
        assertThrows(RpcException.class,()->dispatcher.dispatch(injected,"service.returnUnusedMaterial",mapper.valueToTree(params)));
        assertEquals(18,readRecord("part_batch",batch).path("quantity").asInt());
        assertEquals(before,readRecord("work_order",work));assertEquals(0,returned(work,batch));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM t_service_idempotency WHERE request_key=?",Integer.class,request));
        invoke("service.returnUnusedMaterial",params);assertEquals(1,returned(work,batch));
    }

    @Test void competingReturnsAndInstallationsCannotSpendTheSameBudget() throws Exception {
        for(boolean install:List.of(false,true)) {
            long work=bomOrder(publishedBom(true),1),main=part("BOM主板","GOOD"),ram=part("BOM内存","GOOD");
            issue(work,main,1);issue(work,ram,2);advance(work,"ASSEMBLING");long pc=pc(main,work);
            ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
            try {
                List<Future<Boolean>> attempts=new ArrayList<>();
                for(int i=0;i<2;i++) {final boolean attach=install&&i==1;
                    attempts.add(pool.submit(()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("race timeout");
                        try {if(attach)attach(pc,ram,"DIMM_A",2);else returnUnused(work,ram,2);return true;}catch(RpcException ex){return false;}}));
                }
                assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();int successes=0;
                for(var attempt:attempts)if(attempt.get(20,TimeUnit.SECONDS))successes++;
                assertEquals(1,successes);
                JsonNode budget=balance(work,ram);
                assertEquals(2,budget.path("consumedQty").asInt()+budget.path("returnedQty").asInt());
                assertEquals(0,budget.path("availableQty").asInt());
                assertEquals(18+budget.path("returnedQty").asInt(),readRecord("part_batch",ram).path("quantity").asInt());
            } finally {pool.shutdownNow();}
        }
    }

    @Test void publishingRequiresPrimaryAndPublishedVersionsRejectFurtherItemChanges() {
        long bom=draftBom();item(bom,"DIMM_A","BOM内存",2);
        assertThrows(RpcException.class,()->item(bom,"primary","BOM主板",1));
        assertThrows(RpcException.class,()->invoke("service.publishBom",Map.of("requestId",key(),"bomId",bom)));
        assertEquals("DRAFT",readRecord("product_bom",bom).path("status").asText());
        item(bom,"PRIMARY","BOM主板",1);
        assertThrows(RpcException.class,()->item(bom,"PRIMARY","BOM主板",1));
        invoke("service.publishBom",Map.of("requestId",key(),"bomId",bom));
        assertEquals("PUBLISHED",readRecord("product_bom",bom).path("status").asText());
        assertThrows(RpcException.class,()->item(bom,"DISK","硬盘",1));
        long existing=invoke("bom_item.list",Map.of("filters",List.of(filter("bomId",bom)))).path("records").get(0).path("id").asLong();
        assertThrows(RpcException.class,()->invoke("service.removeBomItem",Map.of("requestId",key(),"itemId",existing)));
        assertEquals(2,invoke("bom_item.list",Map.of("filters",List.of(filter("bomId",bom)))).path("total").asInt());
        long invalid=draftBom();item(invalid,"PRIMARY","BOM主板",2);
        assertThrows(RpcException.class,()->invoke("service.publishBom",Map.of("requestId",key(),"bomId",invalid)));
    }

    @Test void bomWorkOrdersRequirePublishedVersionAndKeepTheChosenVersion() {
        long draft=draftBom();
        assertThrows(RpcException.class,()->bomOrder(draft,1));
        long first=publishedBom(false),second=publishedBom(false),order=bomOrder(first,1);
        assertThrows(RpcException.class,()->bomOrder(first,201));
        assertEquals(first,readRecord("work_order",order).path("bomId").asLong());
        assertNotEquals(second,readRecord("work_order",order).path("bomId").asLong());
        assertEquals("BOM验收整机",readRecord("work_order",order).path("product").asText());
    }

    @Test void unissuedAndOtherWorkOrderMaterialsCannotBeInstalled() {
        long bom=publishedBom(false),work=bomOrder(bom,1),other=bomOrder(bom,1),batch=part("BOM主板","GOOD");
        advance(work,"ASSEMBLING");
        assertThrows(RpcException.class,()->pc(batch,work));
        issue(other,batch,1);
        assertThrows(RpcException.class,()->pc(batch,work));
        assertEquals(0,assembledCount(work));assertEquals(0,consumed(work).path("total").asInt());
        issue(work,batch,1);pc(batch,work);
        assertEquals(1,consumed(work).path("total").asInt());
    }

    @Test void wrongPartSlotAndQuantityDoNotLeaveComponentsOrConsumption() {
        long bom=publishedBom(true),work=bomOrder(bom,1),main=part("BOM主板","GOOD"),ram=part("BOM内存","GOOD"),wrong=part("其他内存","GOOD");
        issue(work,main,1);issue(work,ram,2);issue(work,wrong,2);advance(work,"ASSEMBLING");long pc=pc(main,work);
        assertThrows(RpcException.class,()->attach(pc,wrong,"DIMM_A",2));
        assertThrows(RpcException.class,()->attach(pc,ram,"DIMM_A",1));
        assertThrows(RpcException.class,()->attach(pc,ram,"UNDECLARED",2));
        assertEquals(1,consumed(work).path("total").asInt());
        assertEquals(1,invoke("assembly_component.list",Map.of("filters",List.of(filter("assemblyId",pc),filter("active",true)))).path("total").asInt());
    }

    @Test void assemblyAndAttachmentsConsumeIssuedBudgetExactlyOnceAndLedgerIsAppendOnly() {
        long bom=publishedBom(true),work=bomOrder(bom,1),main=part("BOM主板","GOOD"),ram=part("BOM内存","GOOD");
        issue(work,main,1);issue(work,ram,2);advance(work,"ASSEMBLING");
        var registration=Map.of("requestId",key(),"sn",key(),"workOrderId",work,"batchId",main);
        JsonNode pc=invoke("service.registerAssembly",registration);
        assertEquals(pc.toString(),invoke("service.registerAssembly",registration).toString());
        var attachment=Map.of("requestId",key(),"assemblyId",pc.path("id").asLong(),"batchId",ram,"slot","DIMM_A","qty",2);
        JsonNode component=invoke("service.attachComponent",attachment);
        assertEquals(component.toString(),invoke("service.attachComponent",attachment).toString());
        JsonNode ledger=consumed(work);assertEquals(2,ledger.path("total").asInt());
        int quantity=0;for(JsonNode row:ledger.path("records"))quantity+=row.path("qty").asInt();assertEquals(3,quantity);
        assertEquals(19,readRecord("part_batch",main).path("quantity").asInt());assertEquals(18,readRecord("part_batch",ram).path("quantity").asInt());
        JsonNode receipt=ledger.path("records").get(0);
        assertThrows(RpcException.class,()->invoke("material_consumption.delete",Map.of("id",receipt.path("id").asLong(),"version",receipt.path("version").asLong())));
        assertThrows(RpcException.class,()->pc(main,work));assertEquals(1,assembledCount(work));
    }

    @Test void workOrderCannotEnterTestingUntilEveryPlannedUnitHasEveryBomPosition() {
        long bom=publishedBom(true),work=bomOrder(bom,2),main=part("BOM主板","GOOD"),ram=part("BOM内存","GOOD");
        issue(work,main,2);issue(work,ram,4);advance(work,"ASSEMBLING");long first=pc(main,work);
        attach(first,ram,"DIMM_A",2);
        assertThrows(RpcException.class,()->advance(work,"TESTING"));
        long second=pc(main,work);
        assertThrows(RpcException.class,()->advance(work,"TESTING"));
        assertEquals("ASSEMBLING",readRecord("work_order",work).path("status").asText());
        attach(second,ram,"DIMM_A",2);advance(work,"TESTING");advance(work,"COMPLETED");
        assertEquals("COMPLETED",readRecord("work_order",work).path("status").asText());
    }

    @Test void concurrentInstallationsCannotConsumeOneIssuedBatchBudgetTwice() throws Exception {
        long bom=publishedBom(true),work=bomOrder(bom,2),main=part("BOM主板","GOOD"),ram=part("BOM内存","GOOD");
        issue(work,main,2);issue(work,ram,2);advance(work,"ASSEMBLING");long first=pc(main,work),second=pc(main,work);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for(long pc:List.of(first,second))results.add(pool.submit(()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("race timeout");try{attach(pc,ram,"DIMM_A",2);return true;}catch(RpcException ex){return false;}}));
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();int successes=0;
            for(Future<Boolean> result:results)if(result.get(20,TimeUnit.SECONDS))successes++;
            assertEquals(1,successes);assertEquals(3,consumed(work).path("total").asInt());
            JsonNode used=invoke("material_consumption.aggregate",Map.of("filters",List.of(filter("workOrderId",work),filter("batchId",ram)),"metrics",List.of(Map.of("op","sum","field","qty","as","qty"))));
            assertEquals(2,used.path("records").get(0).path("qty").asInt());
        } finally {pool.shutdownNow();}
    }

    @Test void failureAfterConsumptionRollsBackAssemblyLedgerAndIdempotencyReceipt() {
        long bom=publishedBom(false),work=bomOrder(bom,1),main=part("BOM主板","GOOD");issue(work,main,1);advance(work,"ASSEMBLING");
        String request=key();var params=Map.of("requestId",request,"sn",key(),"workOrderId",work,"batchId",main);
        MetaSnapshot current=engine.snapshot();ObjectNode failing=current.services().get("registerAssembly").deepCopy();
        ((ArrayNode)failing.get("steps")).addObject().put("call","work_order.read").putObject("map").putObject("id").put("const",Long.MAX_VALUE);
        var services=new HashMap<>(current.services());services.put("registerAssembly",failing);
        MetaSnapshot injected=new MetaSnapshot(current.version(),current.models(),current.views(),services,current.menus());
        assertThrows(RpcException.class,()->dispatcher.dispatch(injected,"service.registerAssembly",mapper.valueToTree(params)));
        assertEquals(0,assembledCount(work));assertEquals(0,consumed(work).path("total").asInt());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM t_service_idempotency WHERE request_key=?",Integer.class,request));
        invoke("service.registerAssembly",params);assertEquals(1,consumed(work).path("total").asInt());
    }

    @Test void replacingADefectiveBomComponentKeepsOriginalConsumptionAndRestoresKitReadiness() {
        long bom=publishedBom(true),work=bomOrder(bom,1),main=part("BOM主板","GOOD"),ram=part("BOM内存","GOOD");
        long replacement=part("BOM主板","GOOD"),bad=part("BOM主板","BAD");
        issue(work,main,1);issue(work,ram,2);advance(work,"ASSEMBLING");long pc=pc(main,work);attach(pc,ram,"DIMM_A",2);
        String initialLedger=consumed(work).toString();
        assertTrue(invoke("service.checkBomKit",Map.of("workOrderId",work)).path("ready").asBoolean());
        long defect=fault(main,1);
        JsonNode old=invoke("assembly_component.list",Map.of("filters",List.of(filter("assemblyId",pc),filter("slot","PRIMARY"),filter("active",true)))).path("records").get(0);
        invoke("service.replaceComponent",Map.of("requestId",key(),"componentId",old.path("id").asLong(),"defectId",defect,"replacementBatchId",replacement,"returnBatchId",bad,"reason","BOM齐套后的主板返工更换"));
        assertFalse(readRecord("assembly_component",old.path("id").asLong()).path("active").asBoolean());
        assertEquals(replacement,readRecord("assembly_record",pc).path("batchId").asLong());
        assertEquals(19,readRecord("part_batch",replacement).path("quantity").asInt());
        assertEquals(initialLedger,consumed(work).toString());
        invoke("service.submitForRecheck",Map.of("requestId",key(),"defectId",defect));
        invoke("service.submitRecheck",Map.of("requestId",key(),"defectId",defect,"result","PASS","remark","替换后复检合格"));
        assertEquals("RELEASED",readRecord("assembly_record",pc).path("status").asText());
        assertTrue(invoke("service.checkBomKit",Map.of("workOrderId",work)).path("ready").asBoolean());
        assertEquals(initialLedger,consumed(work).toString());
        advance(work,"TESTING");assertEquals("TESTING",readRecord("work_order",work).path("status").asText());
        assertEquals(1,balance(work,main).path("consumedQty").asInt());
        assertThrows(RpcException.class,()->returnUnused(work,main,1));
        assertEquals(0,balance(work,replacement).path("issuedQty").asInt());
        assertThrows(RpcException.class,()->returnUnused(work,replacement,1));
    }

    @Test void legacyWorkOrdersKeepTheirExistingAssemblyBehaviorWithoutFabricatedConsumption() {
        long work=order(),main=part("旧主板","GOOD"),ram=part("旧内存","GOOD"),pc=pc(main,work);attach(pc,ram,"DIMM_A",1);
        assertTrue(readRecord("work_order",work).path("bomId").isNull());
        assertEquals(0,consumed(work).path("total").asInt());
    }
}
