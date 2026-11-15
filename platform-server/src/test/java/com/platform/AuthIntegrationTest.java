package com.platform;

import com.fasterxml.jackson.databind.*;
import com.platform.auth.*;
import com.platform.meta.MetaEngine;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:auth-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "platform.demo.enabled=false","platform.auth.bootstrap-password=InitialTest!12345"})
@AutoConfigureMockMvc
class AuthIntegrationTest {
    @Autowired MockMvc http; @Autowired AuthService auth; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper mapper;
    @Autowired ApplicationAccess applications; @Autowired MetaEngine engine;
    @Autowired PlatformTransactionManager transactions;
    static final Path STORAGE=AttachmentContract.temporaryStorage();
    @DynamicPropertySource static void meta(DynamicPropertyRegistry r){r.add("platform.meta-dir",()->(Files.isDirectory(Path.of("../meta"))?Path.of("../meta"):Path.of("meta")).toAbsolutePath().toString());r.add("platform.attachments.directory",()->STORAGE.toString());}
    static final String PASS="UserPassword!123", NEW="New456";
    record Session(String token,String csrf,String username) { }
    @BeforeEach void setup(){AuthContext.clear();jdbc.update("UPDATE t_sys_user SET must_change=FALSE WHERE username='admin'");}
    @AfterEach void clear(){AuthContext.clear();}
    Session admin(){var l=auth.login("admin","InitialTest!12345","admin-tests");return new Session(l.token(),l.user().get("csrfToken").toString(),"admin");}
    String user(String role,boolean changed) throws Exception {
        Session admin=admin();AuthContext.set(auth.authenticate(admin.token));String name="test_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try{auth.saveUser(mapper.valueToTree(Map.of("username",name,"name","测试用户","password",PASS,"roles",List.of(role),"enabled",true)));}finally{AuthContext.clear();}
        if(changed)jdbc.update("UPDATE t_sys_user SET must_change=FALSE WHERE username=?",name);
        return name;
    }
    Session login(String username,String password) throws Exception {
        var response=http.perform(post("/api/auth/login").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",username,"password",password)))).andExpect(status().isOk()).andReturn().getResponse();
        String cookie=response.getHeader("Set-Cookie");assertTrue(cookie.contains("HttpOnly"));assertTrue(cookie.contains("SameSite=Strict"));
        return new Session(cookie.substring(cookie.indexOf('=')+1,cookie.indexOf(';')),mapper.readTree(response.getContentAsString()).path("csrfToken").asText(),username);
    }
    MockHttpServletRequestBuilder as(Session session,MockHttpServletRequestBuilder request){return request.cookie(new Cookie("MDD_SESSION",session.token)).header("X-CSRF-Token",session.csrf);}
    JsonNode rpc(Session s,String method,Object params) throws Exception {return mapper.readTree(http.perform(as(s,post("/rpc").contentType("application/json").content(mapper.writeValueAsString(Map.of("jsonrpc","2.0","id",1,"method",method,"params",params))))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    @Test void requiresLoginForBothRpcAndAccountData() throws Exception {
        http.perform(post("/rpc").contentType("application/json").content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"meta.status\"}")).andExpect(status().isUnauthorized());
        http.perform(get("/api/auth/users")).andExpect(status().isUnauthorized());
    }
    @Test void creatingAUserRequiresAnInitialPasswordAndHttpResetReplacesIt() throws Exception {
        String initialPassword="Ab1234";Session admin=admin();String username="password_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        var payload=new HashMap<String,Object>(Map.of("username",username,"name","初始密码验收用户","roles",List.of("operator"),"enabled",true));
        for(String invalid:List.of("","a1234","abcdef","123456","a".repeat(129))) {
            payload.put("password",invalid);
            http.perform(as(admin,post("/api/auth/users").contentType("application/json").content(mapper.writeValueAsString(payload)))).andExpect(status().isBadRequest());
        }
        payload.remove("password");
        http.perform(as(admin,post("/api/auth/users").contentType("application/json").content(mapper.writeValueAsString(payload)))).andExpect(status().isBadRequest());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM t_sys_user WHERE username=?",Integer.class,username));
        payload.put("password",initialPassword);
        http.perform(as(admin,post("/api/auth/users").contentType("application/json").content(mapper.writeValueAsString(payload)))).andExpect(status().isOk());
        var stored=jdbc.queryForMap("SELECT id,password_hash FROM t_sys_user WHERE username=?",username);
        assertNotEquals(initialPassword,stored.get("password_hash"));assertTrue(stored.get("password_hash").toString().startsWith("pbkdf2$"));
        Session initial=login(username,initialPassword);
        JsonNode identity=mapper.readTree(http.perform(as(initial,get("/api/auth/me"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(identity.path("mustChangePassword").asBoolean());
        JsonNode users=mapper.readTree(http.perform(as(admin,get("/api/auth/users"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for(JsonNode row:users){assertFalse(row.has("password"));assertFalse(row.has("password_hash"));}
        http.perform(post("/api/auth/login").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",username,"password","WrongPassword!123")))).andExpect(status().isUnauthorized());
        http.perform(as(admin,post("/api/auth/users/"+stored.get("id")+"/password").contentType("application/json").content(mapper.writeValueAsString(Map.of("password","Ab123"))))).andExpect(status().isBadRequest());
        http.perform(as(admin,post("/api/auth/users/"+stored.get("id")+"/password").contentType("application/json").content(mapper.writeValueAsString(Map.of("password",NEW))))).andExpect(status().isOk());
        http.perform(as(initial,get("/api/auth/me"))).andExpect(status().isUnauthorized());
        http.perform(post("/api/auth/login").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",username,"password",initialPassword)))).andExpect(status().isUnauthorized());
        Session reset=login(username,NEW);
        assertTrue(mapper.readTree(http.perform(as(reset,get("/api/auth/me"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("mustChangePassword").asBoolean());
    }
    @Test void initialPasswordMustBeChangedAndAllOldSessionsAreRevoked() throws Exception {
        String user=user("operator",false);Session first=login(user,PASS);Session second=login(user,PASS);
        http.perform(as(first,post("/rpc").contentType("application/json").content("{}"))).andExpect(status().isForbidden());
        http.perform(as(first,post("/api/auth/password").contentType("application/json").content(mapper.writeValueAsString(Map.of("oldPassword",PASS,"newPassword","Ab123"))))).andExpect(status().isBadRequest());
        http.perform(as(first,post("/api/auth/password").contentType("application/json").content(mapper.writeValueAsString(Map.of("oldPassword",PASS,"newPassword",NEW))))).andExpect(status().isOk());
        http.perform(as(second,get("/api/auth/me"))).andExpect(status().isUnauthorized());
        Session fresh=login(user,NEW);assertTrue(rpc(fresh,"student.list",Map.of()).has("result"));
        http.perform(as(fresh,post("/api/auth/logout"))).andExpect(status().isOk());
        http.perform(as(fresh,get("/api/auth/me"))).andExpect(status().isUnauthorized());
    }
    @Test void viewerCannotWriteInvokeServicesReloadOrManageUsers() throws Exception {
        Session s=login(user("viewer",true),PASS);
        assertTrue(rpc(s,"student.list",Map.of()).has("result"));
        for(String method:List.of("student.create","service.issueMaterial","meta.reload"))assertEquals(-32021,rpc(s,method,Map.of()).path("error").path("code").asInt());
        http.perform(as(s,get("/api/auth/users"))).andExpect(status().isForbidden());
        JsonNode views=rpc(s,"meta.getViews",Map.of("code","student_list"));assertEquals(0,views.path("result").path("views").get(0).path("table").path("buttons").size());
    }
    @Test void modelerCanReloadButCannotModifyBusinessData() throws Exception {
        Session s=login(user("modeler",true),PASS);assertTrue(rpc(s,"meta.reload",Map.of()).has("result"));assertEquals(-32021,rpc(s,"student.create",Map.of()).path("error").path("code").asInt());
    }
    @Test void csrfAndCrossOriginWritesAreRejected() throws Exception {
        Session s=login(user("operator",true),PASS);
        http.perform(post("/rpc").cookie(new Cookie("MDD_SESSION",s.token)).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        http.perform(as(s,post("/rpc").header("Origin","https://evil.example").contentType("application/json").content("{}"))).andExpect(status().isForbidden());
    }
    @Test void auditUsesAuthenticatedActorAndDisablingRevokesSessions() throws Exception {
        String name=user("operator",true);Session s=login(name,PASS);
        JsonNode created=rpc(s,"student.create",Map.of("studentNo",UUID.randomUUID().toString().substring(0,20),"name","审计测试"));
        assertEquals(name,created.path("result").path("created_by").asText());
        assertTrue(rpc(s,"student.update",Map.of("id",created.path("result").path("id").asLong(),"version",0,"created_by","forged")).has("error"));
        Session admin=admin();var row=jdbc.queryForMap("SELECT id,version FROM t_sys_user WHERE username=?",name);
        http.perform(as(admin,post("/api/auth/users").contentType("application/json").content(mapper.writeValueAsString(Map.of("id",row.get("id"),"version",row.get("version"),"name","已停用","roles",List.of("operator"),"enabled",false))))).andExpect(status().isOk());
        http.perform(as(s,get("/api/auth/me"))).andExpect(status().isUnauthorized());
    }
    @Test void modelSpecificRoleFiltersMenusAndBlocksCrossModelReads() throws Exception {
        Session admin=admin();String code="restricted_"+UUID.randomUUID().toString().substring(0,8);
        http.perform(as(admin,post("/api/auth/roles").contentType("application/json").content(mapper.writeValueAsString(Map.of("code",code,"name","只读学生","permissions",List.of("app_student:view")))))).andExpect(status().isOk());
        Session s=login(user(code,true),PASS);assertTrue(rpc(s,"student.list",Map.of()).has("result"));assertEquals(-32021,rpc(s,"part_batch.list",Map.of()).path("error").path("code").asInt());
        String menus=rpc(s,"meta.getMenus",Map.of()).toString();assertTrue(menus.contains("student_list"));assertFalse(menus.contains("part_batch_list"));
    }
    @Test void businessUserCannotBypassWorkflowThroughDirectCrudOrInternalHelpers() throws Exception {
        Session s=login(user("operator",true),PASS);
        for(String method:List.of("part_batch.adjust","defect_record.update","quality_inspection.create","work_order.transition","service.freezeAffected","service.releaseAffected"))
            assertEquals(-32021,rpc(s,method,Map.of()).path("error").path("code").asInt(),method);
    }
    @Test void unusedReturnsUseOperatorIdentityWhileViewersCanOnlyQueryTheBalance() throws Exception {
        Session admin=admin(),operator=login(user("operator",true),PASS);
        String readerRole="reader_"+UUID.randomUUID().toString().substring(0,8);
        List<String> readPermissions=new ArrayList<>();applications.applications(engine.snapshot()).keySet().forEach(code->readPermissions.add(ApplicationAccess.grant(code,"view")));readPermissions.add("data:scopeAll");
        http.perform(as(admin,post("/api/auth/roles").contentType("application/json").content(mapper.writeValueAsString(Map.of("code",readerRole,"name","全部数据只读","permissions",readPermissions))))).andExpect(status().isOk());
        Session viewer=login(user(readerRole,true),PASS);
        long bom=rpc(admin,"service.createBom",Map.of("requestId",UUID.randomUUID().toString(),"code",UUID.randomUUID().toString(),"product","退库权限整机","revision","V1")).path("result").path("id").asLong();
        rpc(admin,"service.addBomItem",Map.of("requestId",UUID.randomUUID().toString(),"bomId",bom,"slot","PRIMARY","partName","退库权限主板","qty",1));
        rpc(admin,"service.publishBom",Map.of("requestId",UUID.randomUUID().toString(),"bomId",bom));
        long work=rpc(operator,"service.createBomWorkOrder",Map.of("requestId",UUID.randomUUID().toString(),"orderNo",UUID.randomUUID().toString(),"bomId",bom,"plannedQty",1)).path("result").path("id").asLong();
        long batch=rpc(admin,"part_batch.create",Map.of("batchNo",UUID.randomUUID().toString(),"partName","退库权限主板","quantity",5,"qualityStatus","GOOD")).path("result").path("id").asLong();
        assertTrue(rpc(operator,"service.issueMaterial",Map.of("requestId",UUID.randomUUID().toString(),"workOrderId",work,"batchId",batch,"qty",2)).has("result"));
        var params=Map.of("requestId",UUID.randomUUID().toString(),"workOrderId",work,"batchId",batch,"qty",1,"reason","权限与审计验收");
        assertEquals(-32021,rpc(viewer,"service.returnUnusedMaterial",params).path("error").path("code").asInt());
        assertEquals(-32021,rpc(operator,"stock_movement.create",Map.of("workOrderId",work,"batchId",batch,"type","RETURN_UNUSED","qty",1)).path("error").path("code").asInt());
        JsonNode receipt=rpc(operator,"service.returnUnusedMaterial",params);
        assertEquals(operator.username,receipt.path("result").path("movement").path("created_by").asText());
        assertEquals(4,receipt.path("result").path("batch").path("quantity").asInt());
        JsonNode queried=rpc(viewer,"service.materialBalance",Map.of("workOrderId",work,"batchId",batch));
        assertEquals(1,queried.path("result").path("availableQty").asInt());
        String buttons=rpc(viewer,"meta.getViews",Map.of("code","work_order_list")).path("result").toString();
        assertTrue(buttons.contains("service:materialBalance"));assertFalse(buttons.contains("service:returnUnusedMaterial"));
    }
    @Test void relationQueriesRequirePermissionForEveryIntermediateModel() throws Exception {
        Session admin=admin();String code="trace_"+UUID.randomUUID().toString().substring(0,8);
        http.perform(as(admin,post("/api/auth/roles").contentType("application/json").content(mapper.writeValueAsString(Map.of("code",code,"name","受限追溯","permissions",List.of("app_assembly_record:view","app_part_batch:view")))))).andExpect(status().isOk());
        JsonNode batch=rpc(admin,"part_batch.create",Map.of("batchNo",UUID.randomUUID().toString(),"partName","权限验证件","quantity",2,"qualityStatus","GOOD")).path("result");
        JsonNode work=rpc(admin,"work_order.create",Map.of("orderNo",UUID.randomUUID().toString(),"product","权限验收","plannedQty",2)).path("result");
        JsonNode pc=rpc(admin,"assembly_record.create",Map.of("sn",UUID.randomUUID().toString(),"workOrderId",work.path("id").asLong(),"batchId",batch.path("id").asLong())).path("result");
        rpc(admin,"assembly_component.create",Map.of("assemblyId",pc.path("id").asLong(),"batchId",batch.path("id").asLong(),"slot","PRIMARY","qty",1,"active",true));
        Object paths=List.of(List.of(Map.of("model","assembly_component","field","assemblyId","direction","in"),Map.of("model","part_batch","field","batchId","direction","out")));
        var query=Map.of("id",pc.path("id").asLong(),"model","part_batch","paths",paths);
        Session limited=login(user(code,true),PASS);assertEquals(-32021,rpc(limited,"assembly_record.related",query).path("error").path("code").asInt());
        http.perform(as(admin,post("/api/auth/roles").contentType("application/json").content(mapper.writeValueAsString(Map.of("code",code,"name","允许追溯","permissions",List.of("app_assembly_record:view","app_part_batch:view","app_assembly_component:view","data:scopeAll")))))).andExpect(status().isOk());
        assertEquals(1,rpc(limited,"assembly_record.related",query).path("result").path("total").asInt());
    }
    String applicationRole(String... permissions) throws Exception {
        String code="app_test_"+UUID.randomUUID().toString().substring(0,8);
        saveApplicationRole(code,permissions);return code;
    }
    void saveApplicationRole(String code,String... permissions) throws Exception {
        List<String> grants=new ArrayList<>(List.of(permissions));
        http.perform(as(admin(),post("/api/auth/roles").contentType("application/json").content(mapper.writeValueAsString(Map.of("code",code,"name","应用权限验收","permissions",grants))))).andExpect(status().isOk());
    }
    @Test void applicationCatalogAndInvalidGrantsAreProtected() throws Exception {
        Session admin=admin(),viewer=login(user("viewer",true),PASS);
        http.perform(as(viewer,get("/api/auth/applications"))).andExpect(status().isForbidden());
        JsonNode catalog=mapper.readTree(http.perform(as(admin,get("/api/auth/applications"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(16,catalog.path("applications").size());
        assertTrue(catalog.path("applications").findValuesAsText("code").contains("machine_inspection"));
        JsonNode work=null;for(JsonNode app:catalog.path("applications"))if(app.path("code").asText().equals("work_order"))work=app;
        assertNotNull(work);assertFalse(work.path("canEdit").asBoolean());assertTrue(work.path("services").has("materialBalance"));
        for(List<String> invalid:List.of(List.of("app_missing:view"),List.of("app_education:view"),
                List.of("app_student:edit"),List.of("app_student:view","app_student:operate"),
                List.of("app_work_order:view","app_work_order:edit"),List.of("apps:configured"),
                List.of("apps:unknown"),List.of("app_student:*"))) {
            String code="invalid_"+UUID.randomUUID().toString().substring(0,8);
            http.perform(as(admin,post("/api/auth/roles").contentType("application/json").content(mapper.writeValueAsString(Map.of("code",code,"name","无效应用权限","permissions",invalid))))).andExpect(status().isBadRequest());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM t_sys_role WHERE code=?",Integer.class,code));
        }
    }
    @Test void applicationReadOnlyAndEditingAreEnforcedAtViewsAndRpc() throws Exception {
        String code=applicationRole("app_student:view","data:scopeAll");Session s=login(user(code,true),PASS);
        String menus=rpc(s,"meta.getMenus",Map.of()).path("result").toString();assertTrue(menus.contains("student_list"));assertFalse(menus.contains("course_list"));assertFalse(menus.contains("equipment_list"));
        assertTrue(rpc(s,"student.list",Map.of()).has("result"));
        for(String method:List.of("course.list","equipment.list","student.create","service.createWorkOrder","meta.reload"))assertEquals(-32021,rpc(s,method,Map.of()).path("error").path("code").asInt(),method);
        assertEquals(-32021,rpc(s,"meta.getViews",Map.of("code","student_form")).path("error").path("code").asInt());
        assertEquals(-32021,rpc(s,"meta.getViews",Map.of("codes",List.of("student_list","course_list"))).path("error").path("code").asInt());
        assertEquals(0,rpc(s,"meta.getViews",Map.of("code","student_list")).path("result").path("views").get(0).path("table").path("buttons").size());
        saveApplicationRole(code,"app_student:view","app_student:edit");
        assertTrue(rpc(s,"meta.getViews",Map.of("code","student_form")).has("result"));
        assertEquals(3,rpc(s,"meta.getViews",Map.of("code","student_list")).path("result").path("views").get(0).path("table").path("buttons").size());
        JsonNode row=rpc(s,"student.create",Map.of("studentNo",UUID.randomUUID().toString().substring(0,20),"name","应用授权创建"));assertTrue(row.has("result"),row.toString());
        assertEquals(s.username,row.path("result").path("created_by").asText());
        assertTrue(rpc(s,"student.update",Map.of("id",row.path("result").path("id").asLong(),"version",0,"name","应用授权修改")).has("result"));
        assertEquals(-32021,rpc(s,"course.create",Map.of()).path("error").path("code").asInt());
        saveApplicationRole(code);
        assertTrue(rpc(s,"meta.getMenus",Map.of()).path("result").path("menus").isEmpty());
        assertEquals(-32021,rpc(s,"student.list",Map.of()).path("error").path("code").asInt());
    }
    @Test void workOrderOperationsGrantReferenceDataWithoutGrantingItsMenusOrWrites() throws Exception {
        String code=applicationRole("app_work_order:view","app_work_order:operate");Session s=login(user(code,true),PASS);
        String menus=rpc(s,"meta.getMenus",Map.of()).path("result").toString();assertTrue(menus.contains("work_order_list"));assertFalse(menus.contains("product_bom_list"));assertFalse(menus.contains("part_batch_list"));
        for(String model:List.of("work_order","product_bom","part_batch"))assertTrue(rpc(s,model+".list",Map.of()).has("result"),model);
        assertEquals(-32021,rpc(s,"meta.getViews",Map.of("code","product_bom_list")).path("error").path("code").asInt());
        assertFalse(rpc(s,"meta.status",Map.of()).path("result").path("views").toString().contains("part_batch_list"));
        for(String method:List.of("part_batch.adjust","work_order.create","service.issueMaterial","service.createBom","service.freezeAffected"))assertEquals(-32021,rpc(s,method,Map.of()).path("error").path("code").asInt(),method);
        for(String service:List.of("createWorkOrder","materialBalance"))assertTrue(rpc(s,"meta.getService",Map.of("code",service)).has("result"),service);
        Map<String,Object> params=Map.of("requestId",UUID.randomUUID().toString(),"orderNo",UUID.randomUUID().toString(),"product","应用授权整机","plannedQty",1);
        JsonNode created=rpc(s,"service.createWorkOrder",params);assertTrue(created.has("result"),created.toString());assertTrue(created.toString().contains(s.username));
        saveApplicationRole(code,"app_work_order:view");
        assertEquals(-32021,rpc(s,"service.createWorkOrder",params).path("error").path("code").asInt());
        assertTrue(rpc(s,"meta.getService",Map.of("code","materialBalance")).has("result"));
        assertTrue(rpc(s,"part_batch.list",Map.of()).has("result"));
    }
    @Test void applicationRolesCombineWithEachOtherAndLegacyRoles() throws Exception {
        String student=applicationRole("app_student:view"),course=applicationRole("app_course:view");
        String username=user(student,true);jdbc.update("UPDATE t_sys_user SET roles_json=? WHERE username=?",mapper.writeValueAsString(List.of(student,course)),username);Session s=login(username,PASS);
        assertTrue(rpc(s,"student.list",Map.of()).has("result"));assertTrue(rpc(s,"course.list",Map.of()).has("result"));assertEquals(-32021,rpc(s,"equipment.list",Map.of()).path("error").path("code").asInt());
        jdbc.update("UPDATE t_sys_user SET roles_json=? WHERE username=?",mapper.writeValueAsString(List.of(student,"viewer")),username);
        assertTrue(rpc(s,"equipment.list",Map.of()).has("result"));assertTrue(rpc(s,"meta.getMenus",Map.of()).toString().contains("equipment_list"));
        assertEquals(-32021,rpc(s,"equipment.create",Map.of()).path("error").path("code").asInt());
    }
    @Test void directAndGlobalBusinessPermissionsCannotExpandSelectedApplications() throws Exception {
        String code=applicationRole("app_student:view");Session s=login(user(code,true),PASS);
        for(String direct:List.of("work_order:read","data:read","data:write","data:maintain","service:invoke","createWorkOrder:invoke","data:*","work_order:*","*","apps:configured")) {
            http.perform(as(admin(),post("/api/auth/roles").contentType("application/json").content(mapper.writeValueAsString(Map.of("code",code,"name","拒绝额外业务权限","permissions",List.of("app_student:view",direct)))))).andExpect(status().isBadRequest());
            assertTrue(rpc(s,"student.list",Map.of()).has("result"));
            assertEquals(-32021,rpc(s,"work_order.list",Map.of()).path("error").path("code").asInt());
        }
        // Even obsolete data inserted outside the role API cannot become an additional grant at runtime.
        jdbc.update("UPDATE t_sys_role SET permissions_json=? WHERE code=?",mapper.writeValueAsString(List.of("app_student:view","work_order:read","data:read","data:write","service:invoke","*")),code);
        for(String method:List.of("work_order.list","student.create","service.createWorkOrder","meta.reload"))assertEquals(-32021,rpc(s,method,Map.of()).path("error").path("code").asInt(),method);
        assertEquals(-32021,rpc(s,"meta.getViews",Map.of("code","work_order_list")).path("error").path("code").asInt());
        assertFalse(rpc(s,"meta.getMenus",Map.of()).toString().contains("work_order_list"));
        saveApplicationRole(code,"app_student:view","app_student:edit");
        assertTrue(rpc(s,"student.create",Map.of("studentNo",UUID.randomUUID().toString().substring(0,20),"name","仅应用写入")).has("result"));
    }
    @Test void legacyMigrationKeepsSelectedApplicationsAndBacksUpOldRolesOnce() throws Exception {
        String selected="selected_"+UUID.randomUUID().toString().substring(0,8),legacy="legacy_"+UUID.randomUUID().toString().substring(0,8),partial="partial_"+UUID.randomUUID().toString().substring(0,8);
        List<String> oldSelected=List.of("app_student:view","data:read","work_order:read","createWorkOrder:invoke","*","meta:inspect");
        jdbc.update("INSERT INTO t_sys_role(code,name,permissions_json) VALUES(?,?,?)",selected,"显式应用优先",mapper.writeValueAsString(oldSelected));
        jdbc.update("INSERT INTO t_sys_role(code,name,permissions_json) VALUES(?,?,?)",legacy,"旧版学生维护",mapper.writeValueAsString(List.of("student:read","student:write","meta:reload")));
        jdbc.update("INSERT INTO t_sys_role(code,name,permissions_json) VALUES(?,?,?)",partial,"旧版部分服务",mapper.writeValueAsString(List.of("product_bom:read","createBom:invoke")));
        auth.migrateLegacyRoles();
        assertEquals(Set.of("app_student:view","meta:inspect"),new HashSet<>(mapper.readerForListOf(String.class).readValue(jdbc.queryForObject("SELECT permissions_json FROM t_sys_role WHERE code=?",String.class,selected))));
        assertEquals(Set.of("app_student:view","app_student:edit","meta:reload"),new HashSet<>(mapper.readerForListOf(String.class).readValue(jdbc.queryForObject("SELECT permissions_json FROM t_sys_role WHERE code=?",String.class,legacy))));
        assertEquals(List.of("app_product_bom:view"),mapper.readerForListOf(String.class).readValue(jdbc.queryForObject("SELECT permissions_json FROM t_sys_role WHERE code=?",String.class,partial)));
        for(String code:List.of(selected,legacy,partial))assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM t_sys_role_permission_backup WHERE role_code=?",Integer.class,code));
        assertEquals(oldSelected,mapper.readerForListOf(String.class).readValue(jdbc.queryForObject("SELECT permissions_json FROM t_sys_role_permission_backup WHERE role_code=?",String.class,selected)));
        auth.migrateLegacyRoles();
        for(String code:List.of(selected,legacy,partial))assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM t_sys_role_permission_backup WHERE role_code=?",Integer.class,code));
        Session migrated=login(user(selected,true),PASS);assertTrue(rpc(migrated,"student.list",Map.of()).has("result"));assertEquals(-32021,rpc(migrated,"work_order.list",Map.of()).path("error").path("code").asInt());
        assertTrue(applications.migrate(engine.snapshot(),List.of("data:read","data:write","service:invoke")).containsAll(List.of("app_work_order:view","app_work_order:operate","app_student:edit")));
    }
    @Test void administratorAccountsAreImmutableButMayChangeTheirOwnPassword() throws Exception {
        Session admin=admin();String otherName=user("admin",true);Session other=login(otherName,PASS);
        for(String name:List.of("admin",otherName)) {
            var row=jdbc.queryForMap("SELECT id,version,display_name,roles_json,password_hash FROM t_sys_user WHERE username=?",name);
            http.perform(as(admin,post("/api/auth/users").contentType("application/json").content(mapper.writeValueAsString(Map.of("id",row.get("id"),"version",row.get("version"),"name","尝试修改管理员","roles",List.of("viewer"),"enabled",true))))).andExpect(status().isForbidden());
            http.perform(as(other,post("/api/auth/users/"+row.get("id")+"/password").contentType("application/json").content(mapper.writeValueAsString(Map.of("password",NEW))))).andExpect(status().isForbidden());
            assertEquals(row,jdbc.queryForMap("SELECT id,version,display_name,roles_json,password_hash FROM t_sys_user WHERE username=?",name));
        }
        http.perform(as(other,post("/api/auth/password").contentType("application/json").content(mapper.writeValueAsString(Map.of("oldPassword",PASS,"newPassword",NEW))))).andExpect(status().isOk());
        http.perform(as(other,get("/api/auth/me"))).andExpect(status().isUnauthorized());assertFalse(auth.authenticate(login(otherName,NEW).token).mustChangePassword());
        String disabled="disabled_admin_"+UUID.randomUUID().toString().substring(0,8);
        http.perform(as(admin,post("/api/auth/users").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",disabled,"name","禁用管理员","password",PASS,"roles",List.of("admin"),"enabled",false))))).andExpect(status().isBadRequest());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM t_sys_user WHERE username=?",Integer.class,disabled));
    }
    @Test void applicationBusinessPermissionManagesAttachmentsWithoutGrantingCrud() throws Exception {
        Session admin=admin();JsonNode batch=rpc(admin,"part_batch.create",Map.of("batchNo",UUID.randomUUID().toString(),"partName","应用附件验收","quantity",3,"qualityStatus","GOOD")).path("result");
        long defect=rpc(admin,"defect_record.create",Map.of("batchId",batch.path("id").asLong(),"qty",1,"reason","应用附件验收")).path("result").path("id").asLong();assertTrue(defect>0);
        String role=applicationRole("app_defect_record:view","app_defect_record:operate");Session s=login(user(role,true),PASS);
        assertEquals(-32021,rpc(s,"defect_record.update",Map.of("id",defect,"version",0,"reason","绕过服务")).path("error").path("code").asInt());
        MockMultipartFile file=new MockMultipartFile("file","proof.txt","text/plain","application-proof".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var uploaded=http.perform(as(s,multipart("/api/attachments").file(file).param("model","defect_record").param("recordId",Long.toString(defect)))).andExpect(status().isOk()).andReturn().getResponse();
        String id=mapper.readTree(uploaded.getContentAsString()).path("id").asText();assertFalse(id.isBlank());
        saveApplicationRole(role,"app_defect_record:view");
        http.perform(as(s,get("/api/attachments/"+id+"/download"))).andExpect(status().isOk());
        http.perform(as(s,multipart("/api/attachments").file(file).param("model","defect_record").param("recordId",Long.toString(defect)))).andExpect(status().isForbidden());
        http.perform(as(s,delete("/api/attachments/"+id))).andExpect(status().isForbidden());
        saveApplicationRole(role);
        http.perform(as(s,get("/api/attachments/"+id+"/download"))).andExpect(status().isForbidden());
        long inspection=rpc(admin,"quality_inspection.create",Map.of("batchId",batch.path("id").asLong(),"phase","IQC","testedQty",1,"passedQty",1)).path("result").path("id").asLong();assertTrue(inspection>0);
        saveApplicationRole(role,"app_quality_inspection:view","app_quality_inspection:operate");
        http.perform(as(s,multipart("/api/attachments").file(file).param("model","quality_inspection").param("recordId",Long.toString(inspection)))).andExpect(status().isOk());
        assertEquals(-32021,rpc(s,"quality_inspection.create",Map.of()).path("error").path("code").asInt());
    }
    @Test void deletingAnUnusedCustomRoleRequiresPermissionAndCsrfAndPreservesAnAuditBackup() throws Exception {
        Session admin=admin(),viewer=login(user("viewer",true),PASS);String code=applicationRole("app_student:view"),path="/api/auth/roles/"+code;
        http.perform(delete(path)).andExpect(status().isUnauthorized());
        http.perform(as(viewer,delete(path))).andExpect(status().isForbidden());
        http.perform(delete(path).cookie(new Cookie("MDD_SESSION",admin.token))).andExpect(status().isForbidden());
        for(String builtin:List.of("admin","modeler","operator","viewer"))http.perform(as(admin,delete("/api/auth/roles/"+builtin))).andExpect(status().isForbidden());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM t_sys_role WHERE code=?",Integer.class,code));
        http.perform(as(admin,delete(path))).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM t_sys_role WHERE code=?",Integer.class,code));
        assertEquals(List.of("app_student:view"),mapper.readerForListOf(String.class).readValue(jdbc.queryForObject("SELECT permissions_json FROM t_sys_role_permission_backup WHERE role_code=?",String.class,code)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM t_sys_audit WHERE method=? AND actor='admin' AND succeeded=TRUE",Integer.class,"roles.delete."+code));
        JsonNode listed=mapper.readTree(http.perform(as(admin,get("/api/auth/roles"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for(JsonNode row:listed)assertNotEquals(code,row.path("code").asText());
        http.perform(as(admin,delete(path))).andExpect(status().isNotFound());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM t_sys_role_permission_backup WHERE role_code=?",Integer.class,code));
    }
    @Test void assignedRolesIncludingDisabledAccountsMustBeUnassignedBeforeDeletion() throws Exception {
        Session admin=admin();String code=applicationRole("app_student:view"),similar=code+"_extra",name=user(code,true);
        saveApplicationRole(similar,"app_course:view");user(similar,true);
        var row=jdbc.queryForMap("SELECT id,version FROM t_sys_user WHERE username=?",name);
        http.perform(as(admin,delete("/api/auth/roles/"+code))).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("解除分配")));
        http.perform(as(admin,post("/api/auth/users").contentType("application/json").content(mapper.writeValueAsString(Map.of("id",row.get("id"),"version",row.get("version"),"name","停用分配测试","roles",List.of(code),"enabled",false))))).andExpect(status().isOk());
        http.perform(as(admin,delete("/api/auth/roles/"+code))).andExpect(status().isConflict());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM t_sys_role_permission_backup WHERE role_code=?",Integer.class,code));
        http.perform(as(admin,post("/api/auth/users").contentType("application/json").content(mapper.writeValueAsString(Map.of("id",row.get("id"),"version",((Number)row.get("version")).longValue()+1,"name","已解除分配","roles",List.of("viewer"),"enabled",false))))).andExpect(status().isOk());
        // A different assigned role containing the same prefix must not block deletion.
        http.perform(as(admin,delete("/api/auth/roles/"+code))).andExpect(status().isOk());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM t_sys_role WHERE code=?",Integer.class,similar));
        assertEquals(List.of("viewer"),mapper.readerForListOf(String.class).readValue(jdbc.queryForObject("SELECT roles_json FROM t_sys_user WHERE username=?",String.class,name)));
    }
    @Test void concurrentAssignmentAndDeletionCannotLeaveAnUnknownRoleOnAUser() throws Exception {
        AuthService secondInstance=new AuthService(jdbc,mapper,transactions,engine,applications,"","target/unused-race-credentials.txt");
        var pool=Executors.newFixedThreadPool(2);
        try {
            for(int attempt=0;attempt<3;attempt++) {
                String code=applicationRole("app_student:view"),name="race_user_"+UUID.randomUUID().toString().substring(0,8);Session admin=admin();var start=new CountDownLatch(1);
                var assign=pool.submit(()->{
                    start.await();AuthContext.set(auth.authenticate(admin.token));
                    try{secondInstance.saveUser(mapper.valueToTree(Map.of("username",name,"name","并发分配","password",PASS,"roles",List.of(code),"enabled",true)));return 200;}
                    catch(ResponseStatusException ex){return ex.getStatusCode().value();}finally{AuthContext.clear();}
                });
                var remove=pool.submit(()->{
                    start.await();AuthContext.set(auth.authenticate(admin.token));
                    try{auth.deleteRole(code);return 200;}catch(ResponseStatusException ex){return ex.getStatusCode().value();}finally{AuthContext.clear();}
                });
                start.countDown();int assigned=assign.get(10,TimeUnit.SECONDS),deleted=remove.get(10,TimeUnit.SECONDS);
                assertTrue((assigned==200&&deleted==409)||(assigned==400&&deleted==200),"assignment="+assigned+", deletion="+deleted);
                int roleCount=jdbc.queryForObject("SELECT COUNT(*) FROM t_sys_role WHERE code=?",Integer.class,code),userCount=jdbc.queryForObject("SELECT COUNT(*) FROM t_sys_user WHERE username=?",Integer.class,name);
                assertEquals(roleCount,userCount);
            }
        }finally{pool.shutdownNow();}
    }
}
