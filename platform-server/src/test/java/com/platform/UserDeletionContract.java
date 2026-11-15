package com.platform;

import com.fasterxml.jackson.databind.*;
import com.platform.auth.*;
import com.platform.meta.MetaEngine;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The same account-deletion behavior is exercised on H2 and the dedicated MySQL test database. */
abstract class UserDeletionContract {
    @Autowired MockMvc http; @Autowired AuthService auth; @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper; @Autowired MetaEngine engine; @Autowired ApplicationAccess applications;
    @Autowired PlatformTransactionManager transactions;
    static final String PASSWORD="DeleteUser123";
    record Session(String token,String csrf,String username) { }
    @BeforeEach void setup(){AuthContext.clear();jdbc.update("UPDATE t_sys_user SET must_change=FALSE WHERE username='admin'");}
    @AfterEach void clear(){AuthContext.clear();}
    String key(){return UUID.randomUUID().toString().replace("-","").substring(0,16);}
    Session session(String username){var login=auth.login(username,username.equals("admin")?"InitialTest!12345":PASSWORD,"user-delete-tests");return new Session(login.token(),login.user().get("csrfToken").toString(),username);}
    Session admin(){return session("admin");}
    String role(String... permissions)throws Exception {
        String code="delete_role_"+key();
        http.perform(as(admin(),post("/api/auth/roles").contentType("application/json").content(mapper.writeValueAsString(Map.of("code",code,"name","删除用户验收角色","permissions",List.of(permissions)))))).andExpect(status().isOk());
        return code;
    }
    String user(String role,boolean enabled)throws Exception {
        String name="delete_user_"+key();
        http.perform(as(admin(),post("/api/auth/users").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",name,"name","删除验收用户","password",PASSWORD,"roles",List.of(role),"enabled",enabled))))).andExpect(status().isOk());
        jdbc.update("UPDATE t_sys_user SET must_change=FALSE WHERE username=?",name);
        return name;
    }
    Map<String,Object> row(String username){return jdbc.queryForMap("SELECT * FROM t_sys_user WHERE username=?",username);}
    MockHttpServletRequestBuilder as(Session session,MockHttpServletRequestBuilder request){return request.cookie(new Cookie("MDD_SESSION",session.token)).header("X-CSRF-Token",session.csrf);}
    MockHttpServletRequestBuilder deletion(Map<String,Object> row){return delete("/api/auth/users/"+row.get("id")).param("version",row.get("version").toString());}
    JsonNode rpc(Session session,String method,Object params)throws Exception {
        JsonNode result=mapper.readTree(http.perform(as(session,post("/rpc").contentType("application/json").content(mapper.writeValueAsString(Map.of("jsonrpc","2.0","id",1,"method",method,"params",params))))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(result.has("result"),result.toString());return result.get("result");
    }

    @Test void deletionRetainsIdentityBusinessRecordsAndAttachmentsButRevokesEverySession()throws Exception {
        Session administrator=admin();String name=user("operator",true);Session first=session(name),second=session(name);var before=row(name);
        long student=rpc(first,"student.create",Map.of("studentNo",key(),"name","历史记录")).path("id").asLong();
        long order=rpc(first,"service.createWorkOrder",Map.of("requestId",key(),"orderNo",key(),"product","历史工单","plannedQty",1)).path("id").asLong();
        long batch=rpc(administrator,"part_batch.create",Map.of("batchNo",key(),"partName","附件批次","quantity",1,"qualityStatus","GOOD")).path("id").asLong();
        long defect=rpc(administrator,"defect_record.create",Map.of("batchId",batch,"qty",1,"reason","附件归属验收")).path("id").asLong();
        byte[] content="historical attachment".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode attachment=mapper.readTree(http.perform(as(first,multipart("/api/attachments").file(new MockMultipartFile("file","history.txt","text/plain",content)).param("model","defect_record").param("recordId",Long.toString(defect)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        http.perform(as(administrator,deletion(before))).andExpect(status().isOk());
        var after=row(name);assertEquals(true,after.get("deleted"));assertEquals(false,after.get("enabled"));
        assertEquals(((Number)before.get("version")).longValue()+1,((Number)after.get("version")).longValue());
        assertNotNull(after.get("deleted_at"));assertEquals("admin",after.get("deleted_by"));
        for(String field:List.of("id","username","display_name","roles_json","password_hash","created_at"))assertEquals(before.get(field),after.get(field),field);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM t_sys_session WHERE user_id=?",Integer.class,before.get("id")));
        for(Session old:List.of(first,second))http.perform(as(old,get("/api/auth/me"))).andExpect(status().isUnauthorized());
        http.perform(as(first,post("/rpc").contentType("application/json").content("{}"))).andExpect(status().isUnauthorized());
        http.perform(post("/api/auth/login").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",name,"password",PASSWORD)))).andExpect(status().isUnauthorized());
        JsonNode users=mapper.readTree(http.perform(as(administrator,get("/api/auth/users"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for(JsonNode visible:users){assertNotEquals(name,visible.path("username").asText());assertFalse(visible.has("password_hash"));}
        assertEquals(name,rpc(administrator,"student.read",Map.of("id",student)).path("created_by").asText());
        assertEquals(name,rpc(administrator,"work_order.read",Map.of("id",order)).path("created_by").asText());
        assertEquals(name,jdbc.queryForObject("SELECT created_by FROM t_attachment_file WHERE id=? AND deleted=FALSE",String.class,attachment.path("id").asText()));
        assertArrayEquals(content,http.perform(as(administrator,get(attachment.path("downloadUrl").asText()))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM t_sys_audit WHERE method=? AND actor='admin' AND succeeded=TRUE",Integer.class,"users.delete."+before.get("id")));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM t_sys_audit WHERE actor=?",Integer.class,name)>0);
    }

    @Test void deletionRequiresManagementPermissionCsrfAndProtectsAdministratorsAndTheCurrentUser()throws Exception {
        String target=user("viewer",true);var before=row(target);Session administrator=admin(),viewer=session(target);
        http.perform(deletion(before)).andExpect(status().isUnauthorized());
        http.perform(as(viewer,deletion(before))).andExpect(status().isForbidden());
        http.perform(deletion(before).cookie(new Cookie("MDD_SESSION",administrator.token))).andExpect(status().isForbidden());
        http.perform(as(administrator,deletion(before)).header("Origin","https://evil.example")).andExpect(status().isForbidden());
        String managerName=user(role("users:manage"),true);Session manager=session(managerName);
        http.perform(as(manager,deletion(row("admin")))).andExpect(status().isForbidden());
        String otherAdmin=user("admin",true);
        http.perform(as(administrator,deletion(row(otherAdmin)))).andExpect(status().isForbidden());
        http.perform(as(manager,deletion(row(managerName)))).andExpect(status().isBadRequest());
        assertEquals(before,row(target));assertEquals(false,row(otherAdmin).get("deleted"));
        http.perform(as(manager,deletion(before))).andExpect(status().isOk());
        assertEquals(managerName,row(target).get("deleted_by"));
    }

    @Test void staleRequestsAndDeletedUserEditsResetsAndUsernameReuseAreRejected()throws Exception {
        Session administrator=admin();String name=user("viewer",true);var before=row(name);
        var edit=Map.of("id",before.get("id"),"version",before.get("version"),"name","更新后的用户","roles",List.of("viewer"),"enabled",true);
        http.perform(as(administrator,post("/api/auth/users").contentType("application/json").content(mapper.writeValueAsString(edit)))).andExpect(status().isOk());
        http.perform(as(administrator,deletion(before))).andExpect(status().isConflict());assertEquals(false,row(name).get("deleted"));
        http.perform(as(administrator,delete("/api/auth/users/"+before.get("id")))).andExpect(status().isBadRequest());
        http.perform(as(administrator,delete("/api/auth/users/-1").param("version","0"))).andExpect(status().isBadRequest());
        http.perform(as(administrator,delete("/api/auth/users/"+before.get("id")).param("version","-1"))).andExpect(status().isBadRequest());
        http.perform(as(administrator,delete("/api/auth/users/9223372036854775807").param("version","0"))).andExpect(status().isNotFound());
        var current=row(name);http.perform(as(administrator,deletion(current))).andExpect(status().isOk());
        http.perform(as(administrator,deletion(current))).andExpect(status().isNotFound());
        http.perform(as(administrator,post("/api/auth/users").contentType("application/json").content(mapper.writeValueAsString(edit)))).andExpect(status().isNotFound());
        http.perform(as(administrator,post("/api/auth/users/"+before.get("id")+"/password").contentType("application/json").content("{\"password\":\"New123\"}"))).andExpect(status().isNotFound());
        http.perform(as(administrator,post("/api/auth/users").contentType("application/json").content(mapper.writeValueAsString(Map.of("username",name,"name","重用旧账号","password",PASSWORD,"roles",List.of("viewer"),"enabled",true))))).andExpect(status().isBadRequest());
        assertEquals(true,row(name).get("deleted"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM t_sys_audit WHERE method=?",Integer.class,"users.delete."+before.get("id")));
    }

    @Test void deletedUsersDoNotBlockRoleDeletionWhileDisabledUndeletedUsersStillDo()throws Exception {
        Session administrator=admin();String code=role("app_student:view"),first=user(code,true),second=user(code,false);
        http.perform(as(administrator,deletion(row(first)))).andExpect(status().isOk());
        http.perform(as(administrator,delete("/api/auth/roles/"+code))).andExpect(status().isConflict());
        http.perform(as(administrator,deletion(row(second)))).andExpect(status().isOk());
        http.perform(as(administrator,delete("/api/auth/roles/"+code))).andExpect(status().isOk());
        assertEquals(List.of(code),mapper.readerForListOf(String.class).readValue(row(first).get("roles_json").toString()));
        assertEquals(List.of(code),mapper.readerForListOf(String.class).readValue(row(second).get("roles_json").toString()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM t_sys_role_permission_backup WHERE role_code=?",Integer.class,code));
    }

    @Test void deletionFlagAloneRejectsLoginAndAnOtherwiseValidExistingSession()throws Exception {
        String name=user("viewer",true);Session old=session(name);
        // Defense in depth: even an old row retaining enabled/version/session cannot authenticate.
        jdbc.update("UPDATE t_sys_user SET deleted=TRUE WHERE username=?",name);
        assertNull(auth.authenticate(old.token));
        assertEquals(401,assertThrows(ResponseStatusException.class,()->session(name)).getStatusCode().value());
        http.perform(as(old,get("/api/auth/me"))).andExpect(status().isUnauthorized());
    }

    @Test void concurrentEditingOrResettingCannotReactivateADeletedUser()throws Exception {
        AuthService other=new AuthService(jdbc,mapper,transactions,engine,applications,"","target/unused-delete-credentials.txt");
        var pool=Executors.newFixedThreadPool(2);
        try {
            for(boolean reset:List.of(false,true)) {
                Session administrator=admin();String name=user("viewer",true);var before=row(name);long id=((Number)before.get("id")).longValue(),version=((Number)before.get("version")).longValue();var start=new CountDownLatch(1);
                var edit=pool.submit(()->{
                    start.await();AuthContext.set(auth.authenticate(administrator.token));
                    try{if(reset)other.resetPassword(id,"Changed123");else other.saveUser(mapper.valueToTree(Map.of("id",id,"version",version,"name","并发编辑","roles",List.of("viewer"),"enabled",true)));return 200;}
                    catch(ResponseStatusException ex){return ex.getStatusCode().value();}finally{AuthContext.clear();}
                });
                var remove=pool.submit(()->{
                    start.await();AuthContext.set(auth.authenticate(administrator.token));
                    try{auth.deleteUser(id,version);return 200;}catch(ResponseStatusException ex){return ex.getStatusCode().value();}finally{AuthContext.clear();}
                });
                start.countDown();int edited=edit.get(15,TimeUnit.SECONDS),deleted=remove.get(15,TimeUnit.SECONDS);
                assertTrue((edited==200&&deleted==409)||(edited==404&&deleted==200),"edit="+edited+", delete="+deleted);
                assertEquals(deleted==200,row(name).get("deleted"));
                if(deleted==200){assertEquals(false,row(name).get("enabled"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM t_sys_session WHERE user_id=?",Integer.class,id));}
            }
        }finally{pool.shutdownNow();}
    }

    @Test void concurrentLoginAndDeletionCannotLeaveAnAuthenticatableSession()throws Exception {
        AuthService other=new AuthService(jdbc,mapper,transactions,engine,applications,"","target/unused-login-credentials.txt");
        var pool=Executors.newFixedThreadPool(2);
        try {
            Session administrator=admin();String name=user("viewer",true);var before=row(name);var start=new CountDownLatch(1);
            var login=pool.submit(()->{start.await();try{return other.login(name,PASSWORD,"delete-login-race").token();}catch(ResponseStatusException ex){assertEquals(401,ex.getStatusCode().value());return null;}});
            var remove=pool.submit(()->{start.await();AuthContext.set(auth.authenticate(administrator.token));try{auth.deleteUser(((Number)before.get("id")).longValue(),((Number)before.get("version")).longValue());}finally{AuthContext.clear();}return true;});
            start.countDown();String token=login.get(15,TimeUnit.SECONDS);assertTrue(remove.get(15,TimeUnit.SECONDS));
            if(token!=null)assertNull(auth.authenticate(token));
            assertEquals(true,row(name).get("deleted"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM t_sys_session WHERE user_id=?",Integer.class,before.get("id")));
        }finally{pool.shutdownNow();}
    }
}
