package com.platform;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.platform.auth.*;
import com.platform.meta.*;
import com.platform.rpc.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** One HTTP/SQL isolation contract on H2 and the explicitly configured MySQL test database. */
abstract class RowScopeContract {
    @Autowired MockMvc http; @Autowired AuthService auth; @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper; @Autowired MetaEngine engine; @Autowired RpcDispatcher dispatcher;
    record Session(String token,String csrf,String username,String role) { }
    static final String PASSWORD="RowScopeUser!123";
    static Path metadata() {
        try {
            Path source=Files.isDirectory(Path.of("../meta"))?Path.of("../meta"):Path.of("meta");
            Path target=Files.createDirectories(Path.of("target/row-scope-meta-"+UUID.randomUUID())).toAbsolutePath();
            try(var paths=Files.walk(source)){for(Path path:paths.toList()) {Path dest=target.resolve(source.relativize(path));if(Files.isDirectory(path))Files.createDirectories(dest);else Files.copy(path,dest);}}
            ObjectMapper mapper=new ObjectMapper();Path student=target.resolve("models/student.json");ObjectNode def=(ObjectNode)mapper.readTree(student.toFile());
            ((ObjectNode)def.get("model")).put("attachments",true);mapper.writeValue(student.toFile(),def);
            Files.writeString(target.resolve("models/private_note.json"),"""
                {"model":{"code":"private_note","name":"私有备注","rowScope":{"via":"studentId"}},"fields":[
                  {"code":"studentId","name":"学生","type":"ref","ref":"student","required":true,"editable":true,"searchable":true},
                  {"code":"courseId","name":"共享课程","type":"ref","ref":"course","required":true,"editable":true,"searchable":true}
                ]}
                """);
            Files.writeString(target.resolve("views/private_note.json"),"""
                {"views":[{"code":"private_note_list","name":"私有备注","type":"list","model":"private_note",
                 "table":{"columns":[{"field":"studentId","title":"学生"}],"buttons":[{"code":"create","name":"新增","action":"service:private_note.create"}]}}]}
                """);
            Files.writeString(target.resolve("menus/private_note.json"),"""
                {"menus":[{"code":"private_note","name":"私有备注","view":"private_note_list"}]}
                """);
            return target;
        }catch(Exception ex){throw new IllegalStateException(ex);}
    }
    static Path storage(){try{return Files.createTempDirectory("row-scope-files-");}catch(Exception ex){throw new IllegalStateException(ex);}}
    String key(){return UUID.randomUUID().toString().replace("-","").substring(0,20);}
    @BeforeEach void setup(){AuthContext.clear();jdbc.update("UPDATE t_sys_user SET must_change=FALSE WHERE username='admin'");}
    @AfterEach void clear(){AuthContext.clear();}
    Session login(String name,String password,String role){var l=auth.login(name,password,"row-scope-tests");return new Session(l.token(),l.user().get("csrfToken").toString(),name,role);}
    Session admin(){return login("admin","InitialTest!12345","admin");}
    void role(String code,boolean all){
        Session admin=admin();AuthContext.set(auth.authenticate(admin.token));
        try {List<String> permissions=new ArrayList<>();
            for(var app:new ApplicationAccess().applications(engine.snapshot()).values()){
                permissions.add(ApplicationAccess.grant(app.code(),"view"));
                if(app.canEdit())permissions.add(ApplicationAccess.grant(app.code(),"edit"));
                if(app.canOperate())permissions.add(ApplicationAccess.grant(app.code(),"operate"));
            }
            if(all)permissions.add("data:scopeAll");
            auth.saveRole(mapper.valueToTree(Map.of("code",code,"name","行权限测试","permissions",permissions)));
        } finally {AuthContext.clear();}
    }
    Session user(boolean all){
        String code="row_"+key(),name="row_"+key();role(code,all);Session admin=admin();AuthContext.set(auth.authenticate(admin.token));
        try {auth.saveUser(mapper.valueToTree(Map.of("username",name,"name","行权限用户","password",PASSWORD,"roles",List.of(code),"enabled",true)));}
        finally {AuthContext.clear();}
        jdbc.update("UPDATE t_sys_user SET must_change=FALSE WHERE username=?",name);return login(name,PASSWORD,code);
    }
    MockHttpServletRequestBuilder as(Session s,MockHttpServletRequestBuilder r){return r.cookie(new Cookie("MDD_SESSION",s.token)).header("X-CSRF-Token",s.csrf);}
    JsonNode call(Session s,String method,Object params)throws Exception {
        return mapper.readTree(http.perform(as(s,post("/rpc").contentType("application/json").content(mapper.writeValueAsString(Map.of("jsonrpc","2.0","id",1,"method",method,"params",params))))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    JsonNode ok(Session s,String method,Object params)throws Exception {JsonNode r=call(s,method,params);assertTrue(r.has("result"),r.toString());return r.get("result");}
    void denied(Session s,String method,Object params)throws Exception {assertTrue(call(s,method,params).has("error"),method);}
    long student(Session s,int age)throws Exception {return ok(s,"student.create",Map.of("studentNo",key(),"name","归属测试","age",age)).path("id").asLong();}
    long batch(Session s,String quality)throws Exception {return ok(s,"part_batch.create",Map.of("batchNo",key(),"partName","共享主板","quantity",20,"qualityStatus",quality)).path("id").asLong();}
    long order(Session s)throws Exception {long id=ok(s,"service.createWorkOrder",Map.of("requestId",key(),"orderNo",key(),"product","个人工单","plannedQty",10)).path("id").asLong();ok(s,"service.advanceWorkOrder",Map.of("requestId",key(),"workOrderId",id,"to","ASSEMBLING"));return id;}
    long pc(Session s,long work,long batch)throws Exception {return ok(s,"service.registerAssembly",Map.of("requestId",key(),"workOrderId",work,"batchId",batch,"sn",key())).path("id").asLong();}
    Map<String,Object> filter(String field,Object value){return Map.of("field",field,"op","eq","value",value);}

    @Test void listsTotalsAggregatesAndDirectReadsOnlyContainTheCreator()throws Exception {
        Session a=user(false),b=user(false);long own=student(a,20),other=student(b,30);
        JsonNode list=ok(a,"student.list",Map.of("pageSize",1));assertEquals(1,list.path("total").asInt());assertEquals(own,list.path("records").get(0).path("id").asLong());
        assertEquals(0,ok(a,"student.list",Map.of("filters",List.of(filter("studentNo",ok(b,"student.read",Map.of("id",other)).path("studentNo").asText())))).path("total").asInt());
        JsonNode sum=ok(a,"student.aggregate",Map.of("metrics",List.of(Map.of("op","sum","field","age","as","ages"))));assertEquals(20,sum.path("records").get(0).path("ages").asInt());
        denied(a,"student.read",Map.of("id",other));assertEquals(30,ok(b,"student.read",Map.of("id",other)).path("age").asInt());
        denied(a,"student.list",Map.of("scopeAll",true));
    }
    @Test void writesRefsAndMixedBatchDeletesCannotCrossOwnership()throws Exception {
        Session a=user(false),b=user(false);long own=student(a,20),other=student(b,30);
        denied(a,"student.update",Map.of("id",other,"version",0,"name","越权"));
        denied(a,"student.adjust",Map.of("id",other,"version",0,"field","age","delta",1));
        denied(a,"student.batchDelete",Map.of("records",List.of(Map.of("id",own,"version",0),Map.of("id",other,"version",0))));
        assertEquals(20,ok(a,"student.read",Map.of("id",own)).path("age").asInt());
        assertEquals(21,ok(a,"student.adjust",Map.of("id",own,"version",0,"field","age","delta",1)).path("age").asInt());
        denied(a,"student.create",Map.of("studentNo",key(),"name","伪造创建人","created_by",b.username));
        long course=ok(a,"course.create",Map.of("courseNo",key(),"name","共享课")).path("id").asLong();
        denied(a,"private_note.create",Map.of("studentId",other,"courseId",course));
        ok(a,"private_note.create",Map.of("studentId",own,"courseId",course));
    }
    @Test void productionChildrenAndServiceParametersInheritWorkOrderOwnership()throws Exception {
        Session a=user(false),b=user(false),admin=admin();long shared=batch(admin,"GOOD"),wa=order(a),wb=order(b),pa=pc(a,wa,shared),pb=pc(b,wb,shared);
        JsonNode component=ok(admin,"assembly_component.create",Map.of("assemblyId",pa,"batchId",shared,"slot","EXTRA","qty",1,"active",true));
        assertEquals(admin.username,component.path("created_by").asText());
        ok(a,"assembly_component.read",Map.of("id",component.path("id").asLong()));denied(b,"assembly_component.read",Map.of("id",component.path("id").asLong()));
        denied(b,"assembly_record.read",Map.of("id",pa));denied(a,"assembly_record.read",Map.of("id",pb));
        denied(b,"service.issueMaterial",Map.of("requestId",key(),"workOrderId",wa,"batchId",shared,"qty",1));
        denied(b,"service.advanceWorkOrder",Map.of("requestId",key(),"workOrderId",wa,"to","TESTING"));
        denied(b,"service.materialBalance",Map.of("workOrderId",wa,"batchId",shared));
        denied(b,"service.returnUnusedMaterial",Map.of("requestId",key(),"workOrderId",wa,"batchId",shared,"qty",1,"reason","越权退库"));
        ok(a,"service.issueMaterial",Map.of("requestId",key(),"workOrderId",wa,"batchId",shared,"qty",1));
        assertEquals(0,ok(b,"stock_movement.list",Map.of("filters",List.of(filter("workOrderId",wa)))).path("total").asInt());
        assertEquals(1,ok(a,"stock_movement.list",Map.of("filters",List.of(filter("workOrderId",wa)))).path("total").asInt());
        JsonNode orphan=ok(admin,"stock_movement.create",Map.of("batchId",shared,"type","ISOLATE","qty",1));
        ok(b,"stock_movement.read",Map.of("id",orphan.path("id").asLong()));
        JsonNode consumption=ok(admin,"material_consumption.create",Map.of("workOrderId",wa,"batchId",shared,"assemblyId",pa,"slot","PRIMARY","qty",1));
        ok(a,"material_consumption.read",Map.of("id",consumption.path("id").asLong()));denied(b,"material_consumption.read",Map.of("id",consumption.path("id").asLong()));
    }
    @Test void sharedDestinationsCannotRevealPathsThroughAnotherUsersPrivateRecords()throws Exception {
        Session a=user(false),b=user(false);long student=student(b,20),course=ok(a,"course.create",Map.of("courseNo",key(),"name","共享课")).path("id").asLong();
        ok(b,"private_note.create",Map.of("studentId",student,"courseId",course));
        Object paths=List.of(List.of(Map.of("model","private_note","field","courseId","direction","in"),Map.of("model","course","field","courseId","direction","out")));
        var params=Map.of("id",course,"model","course","paths",paths,"pageSize",1);
        assertEquals(0,ok(a,"course.related",params).path("total").asInt());assertEquals(1,ok(b,"course.related",params).path("total").asInt());
        assertEquals(0,ok(a,"course.related",Map.of("id",course,"model","private_note","field","courseId")).path("total").asInt());
    }
    @Test void scopeGrantsAreLiveAndRevokedReceiptReplaysRecheckReferences()throws Exception {
        Session a=user(false),manager=user(true);long work=order(a),shared=batch(admin(),"GOOD");
        var params=Map.of("requestId",key(),"workOrderId",work,"batchId",shared,"qty",1);
        JsonNode receipt=ok(manager,"service.issueMaterial",params);ok(manager,"work_order.read",Map.of("id",work));
        role(manager.role,false);
        denied(manager,"work_order.read",Map.of("id",work));denied(manager,"service.issueMaterial",params);
        assertEquals(19,ok(admin(),"part_batch.read",Map.of("id",shared)).path("quantity").asInt());
        role(manager.role,true);assertEquals(receipt.toString(),ok(manager,"service.issueMaterial",params).toString());
        // Even a generic service with constant lookup IDs and no typed ref params must not replay old privileges.
        MetaSnapshot current=engine.snapshot();ObjectNode replay=mapper.createObjectNode().put("code","scopeReplayNoRef").put("name","回执权限验证").put("idempotencyKey","requestId");
        replay.putArray("params").addObject().put("name","requestId").put("type","string").put("required",true);
        replay.putArray("steps").addObject().put("call","work_order.read").putObject("map").putObject("id").put("const",work);
        var services=new HashMap<>(current.services());services.put("scopeReplayNoRef",replay);MetaSnapshot injected=new MetaSnapshot(current.version(),current.models(),current.views(),services,current.menus());
        JsonNode replayParams=mapper.valueToTree(Map.of("requestId",key()));AuthContext.set(auth.authenticate(manager.token));
        try {dispatcher.dispatch(injected,"service.scopeReplayNoRef",replayParams);} finally {AuthContext.clear();}
        role(manager.role,false);AuthContext.set(auth.authenticate(manager.token));
        try {assertEquals(-32012,assertThrows(RpcException.class,()->dispatcher.dispatch(injected,"service.scopeReplayNoRef",replayParams)).getCode());}
        finally {AuthContext.clear();}
    }
    @Test void stateHistoryChecksTheSourceRecordScope()throws Exception {
        Session a=user(false),b=user(false);long work=order(a);
        assertTrue(ok(a,"meta.getEvents",Map.of("model","work_order","id",work)).path("records").size()>0);
        denied(b,"meta.getEvents",Map.of("model","work_order","id",work));
        ok(admin(),"meta.getEvents",Map.of("model","work_order","id",work));
    }
    @Test void attachmentsFollowParentScopeForAllOperationsAndAfterRevocation()throws Exception {
        Session a=user(false),b=user(false),manager=user(true);long record=student(a,20);
        var file=new MockMultipartFile("file","proof.txt","text/plain",new byte[]{1,2,3});
        JsonNode attachment=mapper.readTree(http.perform(as(a,multipart("/api/attachments").file(file).param("model","student").param("recordId",Long.toString(record)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String path="/api/attachments/"+attachment.path("id").asText();
        http.perform(as(b,get("/api/attachments").param("model","student").param("recordId",Long.toString(record)))).andExpect(status().isNotFound());
        http.perform(as(b,get(path+"/download"))).andExpect(status().isNotFound());
        http.perform(as(b,delete(path))).andExpect(status().isNotFound());
        http.perform(as(b,multipart("/api/attachments").file(file).param("model","student").param("recordId",Long.toString(record)))).andExpect(status().isNotFound());
        http.perform(as(manager,get(path+"/download"))).andExpect(status().isOk());role(manager.role,false);
        http.perform(as(manager,get(path+"/download"))).andExpect(status().isNotFound());
        http.perform(as(a,get(path+"/download"))).andExpect(status().isOk());http.perform(as(a,delete(path))).andExpect(status().isOk());
    }
    @Test void sharedQualityEventsFreezeAndReleaseEveryOwnersAffectedUnitsWithoutReturningTheirRows()throws Exception {
        Session a=user(false),b=user(false),admin=admin();long shared=batch(admin,"GOOD"),wa=order(a),wb=order(b);pc(a,wa,shared);pc(b,wb,shared);
        long defect=ok(a,"service.registerDefect",Map.of("requestId",key(),"batchId",shared,"qty",1,"reason","共享批次质量问题")).path("id").asLong();
        assertEquals("FROZEN",ok(admin,"work_order.read",Map.of("id",wb)).path("status").asText());
        assertEquals("FROZEN",ok(a,"work_order.read",Map.of("id",wa)).path("status").asText());
        JsonNode released=ok(a,"service.disposeDefect",Map.of("requestId",key(),"defectId",defect,"decision","CONCESSION","reason","质量审批通过"));
        assertTrue(released.path("processed").asBoolean());assertEquals(1,released.size());
        assertEquals("ASSEMBLING",ok(b,"work_order.read",Map.of("id",wb)).path("status").asText());
        denied(a,"work_order.read",Map.of("id",wb));
        for(String internal:List.of("freezeAffected","releaseAffected","countActiveBatchAssemblies","countHistoricalBatchAssemblies"))denied(a,"service."+internal,Map.of("batchId",shared));
    }
    @Test void globalSafetyGuardsDoNotMistakeInvisibleInstalledUnitsForAnEmptyBatch()throws Exception {
        Session a=user(false),b=user(false),admin=admin();long shared=batch(admin,"GOOD"),work=order(b);pc(b,work,shared);
        long replacement=batch(admin,"GOOD"),bad=batch(admin,"BAD");
        long defect=ok(a,"service.registerDefect",Map.of("requestId",key(),"batchId",shared,"qty",1,"reason","跨用户装机批次" )).path("id").asLong();
        JsonNode reworkError=call(a,"service.rework",Map.of("requestId",key(),"defectId",defect,"replacementBatchId",replacement,"returnBatchId",bad)).path("error");
        assertEquals(-32011,reworkError.path("code").asInt());assertTrue(reworkError.path("message").asText().contains("在用装机零件"));
        JsonNode disposalError=call(a,"service.disposeDefect",Map.of("requestId",key(),"defectId",defect,"decision","SCRAPPED","reason","不能绕过装机历史")).path("error");
        assertEquals(-32011,disposalError.path("code").asInt());assertTrue(disposalError.path("message").asText().contains("装机履历"));
        assertEquals(20,ok(admin,"part_batch.read",Map.of("id",replacement)).path("quantity").asInt());
        assertEquals("FROZEN",ok(b,"work_order.read",Map.of("id",work)).path("status").asText());
    }
    @Test void anInternalFailureRestoresTheCallerScopeAndRollsBackCrossOwnerChanges()throws Exception {
        Session a=user(false),b=user(false),admin=admin();long shared=batch(admin,"GOOD"),wa=order(a),wb=order(b);pc(a,wa,shared);pc(b,wb,shared);
        MetaSnapshot current=engine.snapshot();ObjectNode failing=current.services().get("freezeAffected").deepCopy();
        ((ArrayNode)failing.get("steps")).addObject().put("call","work_order.read").putObject("map").putObject("id").put("const",Long.MAX_VALUE);
        var services=new HashMap<>(current.services());services.put("freezeAffected",failing);MetaSnapshot injected=new MetaSnapshot(current.version(),current.models(),current.views(),services,current.menus());
        AuthContext.set(auth.authenticate(a.token));
        try {
            assertThrows(RpcException.class,()->dispatcher.dispatch(injected,"service.registerDefect",mapper.valueToTree(Map.of("requestId",key(),"batchId",shared,"qty",1,"reason","回滚测试"))));
            assertThrows(RpcException.class,()->dispatcher.dispatch(current,"work_order.read",mapper.valueToTree(Map.of("id",wb))));
            assertThrows(RpcException.class,()->dispatcher.dispatch(current,"service.releaseAffected",mapper.valueToTree(Map.of("batchId",shared))));
        } finally {AuthContext.clear();}
        assertEquals("ASSEMBLING",ok(b,"work_order.read",Map.of("id",wb)).path("status").asText());
        assertEquals(20,ok(admin,"part_batch.read",Map.of("id",shared)).path("quantity").asInt());
    }
}
