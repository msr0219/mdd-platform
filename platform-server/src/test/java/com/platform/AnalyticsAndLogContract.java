package com.platform;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.platform.auth.*;
import com.platform.meta.*;
import com.platform.rpc.*;
import com.platform.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Shared SQL/permission contract for H2 and the configured MySQL acceptance database. */
abstract class AnalyticsAndLogContract {
    @Autowired RpcDispatcher rpc; @Autowired MetaEngine engine; @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc; @Autowired ServiceCallLog logs; @Autowired ServiceLogController controller;
    @AfterEach void identityCleanup() { AuthContext.clear(); }
    String key() { return UUID.randomUUID().toString(); }
    JsonNode call(String method,Object params) { return mapper.valueToTree(rpc.dispatch(engine.snapshot(),method,mapper.valueToTree(params))); }
    void actor(String name,Set<String> permissions) { AuthContext.set(new AuthContext.Identity(42,name,name,List.of(),permissions,false,"unused","unused")); }
    long machine(long work,long batch) { return call("assembly_record.create",Map.of("sn",key(),"workOrderId",work,"batchId",batch)).path("id").asLong(); }
    long[] fixture() {
        long batch=call("part_batch.create",Map.of("batchNo",key(),"partName","检验验收件","quantity",10,"qualityStatus","GOOD")).path("id").asLong();
        long work=call("work_order.create",Map.of("orderNo",key(),"product","直通率验收","plannedQty",10)).path("id").asLong();
        return new long[]{work,machine(work,batch),machine(work,batch),machine(work,batch)};
    }
    Map<String,Object> inspection(String request,long assembly,String result) { return Map.of("requestId",request,"assemblyId",assembly,"result",result,"workHours",1,"remark","检验验收"); }
    ObjectNode aggregate(long work) {
        ObjectNode p=mapper.createObjectNode();p.putArray("groupBy").add("workOrderId");p.put("firstBy","assemblyId");
        p.putArray("metrics").addObject().put("op","sum").put("field","testedQty").put("as","tested");
        ((ArrayNode)p.get("metrics")).addObject().put("op","sum").put("field","passedQty").put("as","passed");
        p.putArray("filters").addObject().put("field","workOrderId").put("op","eq").put("value",work);return p;
    }
    @Test void firstPassCountsMachinesOnceAndFiltersAfterSelectingFirstInspection() {
        long[] f=fixture();call("service.inspectMachine",inspection(key(),f[1],"FAIL"));call("service.inspectMachine",inspection(key(),f[1],"PASS"));
        call("service.inspectMachine",inspection(key(),f[2],"PASS"));call("service.inspectMachine",inspection(key(),f[3],"PASS"));
        ObjectNode params=aggregate(f[0]);JsonNode first=call("machine_inspection.aggregate",params).path("records").get(0);
        assertEquals(3,first.path("tested").asInt());assertEquals(2,first.path("passed").asInt());
        ((ArrayNode)params.get("filters")).addObject().put("field","result").put("op","eq").put("value","PASS");
        assertEquals(2,call("machine_inspection.aggregate",params).path("records").get(0).path("tested").asInt());
        params.remove("firstBy");assertEquals(3,call("machine_inspection.aggregate",params).path("records").get(0).path("tested").asInt());
    }
    @Test void inspectionReplayDoesNotCreateAnotherRecordAndLogsActorAndKey() {
        actor("qa_"+key().substring(0,8),Set.of());long[] f=fixture();String request=key();var p=inspection(request,f[1],"PASS");
        call("service.inspectMachine",p);call("service.inspectMachine",p);
        assertEquals(1,call("machine_inspection.aggregate",aggregate(f[0])).path("records").get(0).path("tested").asInt());
        var rows=jdbc.queryForList("SELECT * FROM t_service_call_log WHERE request_key=?",request);assertEquals(2,rows.size());
        assertTrue(rows.stream().anyMatch(row->row.get("outcome").equals("REPLAYED")));
        assertTrue(rows.stream().allMatch(row->row.get("actor").equals(AuthContext.actor())&&row.get("transaction_id")!=null));
        assertFalse(rows.get(0).get("param_names").toString().contains("检验验收"));
    }
    @Test void firstPassAndInspectionRefsRespectInheritedOwnership() {
        actor("owner_"+key().substring(0,8),Set.of());long[] f=fixture();call("service.inspectMachine",inspection(key(),f[1],"PASS"));
        actor("other_"+key().substring(0,8),Set.of());
        assertTrue(call("machine_inspection.aggregate",aggregate(f[0])).path("records").isEmpty());
        assertThrows(RpcException.class,()->call("service.inspectMachine",inspection(key(),f[1],"PASS")));
    }
    @Test void invalidFirstByAndMetricsAreRejectedWithoutSqlExecution() {
        ObjectNode p=aggregate(1);p.put("firstBy","assemblyId);DROP TABLE t_sys_user");
        assertEquals(-32602,assertThrows(RpcException.class,()->call("machine_inspection.aggregate",p)).getCode());
        p.put("firstBy","remark");assertThrows(RpcException.class,()->call("machine_inspection.aggregate",p));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM t_sys_user",Long.class)>0);
    }
    @Test void nestedFailureKeepsLogsAndMarksCompletedChildAsRolledBack() throws Exception {
        String code="audit_"+key().replace("-","");String request=key();String secret="private-"+key();
        Map<String,JsonNode> services=new HashMap<>(engine.snapshot().services());
        ObjectNode child=mapper.createObjectNode();child.put("code",code+"Child");child.putArray("steps").addObject().put("call","course.create").putObject("map").putObject("courseNo").put("const",code.substring(0,25));
        ObjectNode map=(ObjectNode)child.path("steps").get(0).path("map");map.putObject("name").put("const","日志事务验收");services.put(code+"Child",child);
        ObjectNode parent=mapper.createObjectNode();parent.put("code",code);parent.put("idempotencyKey","requestId");
        ArrayNode specs=parent.putArray("params");specs.addObject().put("name","requestId").put("type","string").put("required",true);specs.addObject().put("name","password").put("type","string");
        ArrayNode steps=parent.putArray("steps");steps.addObject().put("call","service."+code+"Child").putObject("map");steps.addObject().put("call","course.read").putObject("map").putObject("id").put("const",Long.MAX_VALUE);services.put(code,parent);
        var s=engine.snapshot();var injected=new MetaSnapshot(s.version(),s.models(),s.views(),services,s.menus());
        assertThrows(RpcException.class,()->rpc.dispatch(injected, "service."+code,mapper.valueToTree(Map.of("requestId",request,"password",secret))));
        var root=jdbc.queryForMap("SELECT * FROM t_service_call_log WHERE request_key=?",request);
        var children=jdbc.queryForList("SELECT * FROM t_service_call_log WHERE parent_trace_id=?",root.get("trace_id"));
        assertEquals(1,children.size());assertEquals("ROLLED_BACK",children.get(0).get("outcome"));
        assertEquals(root.get("transaction_id"),children.get(0).get("transaction_id"));assertEquals(root.get("trace_id"),children.get(0).get("root_trace_id"));
        assertFalse(root.toString().contains(secret));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM t_course WHERE courseNo=?",Integer.class,code.substring(0,25)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM t_service_idempotency WHERE request_key=?",Integer.class,request));
    }
    @Test void logQueryRequiresManagementPermissionAndValidPagination() {
        actor("reader",Set.of());assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.list(1,20,"","","","","")).getStatusCode().value());
        actor("manager",Set.of("users:manage"));assertNotNull(controller.list(1,20,"","","","",""));
        assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.list(0,20,"","","","","")).getStatusCode().value());
    }
    @Test void schemaMigrationIsRepeatableAndPreservesExistingLogRows() {
        String id=key();jdbc.update("INSERT INTO t_service_call_log(trace_id,service_code,params_hash,cost_ms,outcome,created_at) VALUES(?,?,?,0,'SUCCESS',CURRENT_TIMESTAMP)",id,"legacy","hash");
        logs.initialize();logs.initialize();assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM t_service_call_log WHERE trace_id=?",Integer.class,id));
        assertEquals(1L,logs.list(1,20,"legacy","","",id,"").get("total"));
    }
}
