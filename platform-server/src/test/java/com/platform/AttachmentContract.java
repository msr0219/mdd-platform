package com.platform;

import com.fasterxml.jackson.databind.*;
import com.platform.auth.*;
import com.platform.meta.MetaEngine;
import com.platform.rpc.RpcDispatcher;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP authorization and storage behavior against an isolated database and directory. */
abstract class AttachmentContract {
    @Autowired MockMvc http; @Autowired AuthService auth; @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper; @Autowired RpcDispatcher rpc; @Autowired MetaEngine engine;
    static final String PASSWORD="AttachmentUser!123";
    abstract Path storage();
    static Path temporaryStorage(){try{return Files.createTempDirectory("mdd-attachments-tests-");}catch(Exception e){throw new IllegalStateException(e);}}
    record Session(String token,String csrf,String username) { }
    @BeforeEach void setup(){AuthContext.clear();jdbc.update("UPDATE t_sys_user SET must_change=FALSE WHERE username='admin'");}
    @AfterEach void clear(){AuthContext.clear();}
    Session admin(){return session("admin","InitialTest!12345");}
    Session session(String name,String password){var login=auth.login(name,password,"attachment-tests");return new Session(login.token(),login.user().get("csrfToken").toString(),name);}
    Session user(String role,boolean changed){
        Session admin=admin();String name="attachment_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        AuthContext.set(auth.authenticate(admin.token));
        try{auth.saveUser(mapper.valueToTree(Map.of("username",name,"name","附件测试用户","password",PASSWORD,"roles",List.of(role),"enabled",true)));}finally{AuthContext.clear();}
        if(changed)jdbc.update("UPDATE t_sys_user SET must_change=FALSE WHERE username=?",name);
        return session(name,PASSWORD);
    }
    MockHttpServletRequestBuilder as(Session session,MockHttpServletRequestBuilder request){return request.cookie(new Cookie("MDD_SESSION",session.token)).header("X-CSRF-Token",session.csrf);}
    JsonNode invoke(String method,Object params){return mapper.valueToTree(rpc.dispatch(engine.snapshot(),method,mapper.valueToTree(params)));}
    long record(){
        long batch=invoke("part_batch.create",Map.of("batchNo",UUID.randomUUID().toString(),"partName","附件验收件","quantity",20,"qualityStatus","GOOD")).path("id").asLong();
        return invoke("defect_record.create",Map.of("batchId",batch,"qty",1,"reason","附件验收")).path("id").asLong();
    }
    MockHttpServletRequestBuilder uploadRequest(long record,String name,byte[] bytes){return multipart("/api/attachments").file(new MockMultipartFile("file",name,"application/octet-stream",bytes)).param("model","defect_record").param("recordId",Long.toString(record));}
    JsonNode upload(Session session,long record,String name,byte[] bytes) throws Exception {
        return mapper.readTree(http.perform(as(session,uploadRequest(record,name,bytes))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    JsonNode upload(Session session,long record) throws Exception{return upload(session,record,"检查记录.txt","检查合格\n".getBytes(StandardCharsets.UTF_8));}
    String fileUrl(JsonNode attachment){return "/api/attachments/"+attachment.path("id").asText();}

    @Test void loginPasswordGateAndCsrfProtectAttachmentEndpoints() throws Exception {
        long record=record();
        http.perform(get("/api/attachments").param("model","defect_record").param("recordId",Long.toString(record))).andExpect(status().isUnauthorized());
        http.perform(uploadRequest(record,"proof.txt",new byte[]{1})).andExpect(status().isUnauthorized());
        Session initial=user("operator",false);
        http.perform(as(initial,get("/api/attachments").param("model","defect_record").param("recordId",Long.toString(record)))).andExpect(status().isForbidden());
        Session operator=user("operator",true);
        http.perform(uploadRequest(record,"proof.txt",new byte[]{1}).cookie(new Cookie("MDD_SESSION",operator.token))).andExpect(status().isForbidden());
        http.perform(as(operator,uploadRequest(record,"proof.txt",new byte[]{1})).header("Origin","https://evil.example")).andExpect(status().isForbidden());
    }

    @Test void uploadListAndBinaryDownloadUseRealActorAndSafeHeaders() throws Exception {
        Session owner=user("operator",true);long record=record();byte[] content="质量凭证\r\nSN-001".getBytes(StandardCharsets.UTF_8);
        JsonNode uploaded=upload(owner,record,"中文凭证.txt",content);
        assertFalse(uploaded.path("id").asText().isBlank());assertEquals("defect_record",uploaded.path("model").asText());
        assertEquals(Long.toString(record),uploaded.path("recordId").asText());assertEquals("中文凭证.txt",uploaded.path("fileName").asText());
        assertEquals(content.length,uploaded.path("size").asLong());assertEquals(owner.username,uploaded.path("createdBy").asText());
        String id=uploaded.path("id").asText();
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)),jdbc.queryForObject("SELECT sha256 FROM t_attachment_file WHERE id=?",String.class,id));
        assertEquals(owner.username,jdbc.queryForObject("SELECT actor FROM t_attachment_event WHERE attachment_id=? AND action='UPLOAD'",String.class,id));
        assertFalse(uploaded.toString().contains(storage().toString()));
        JsonNode listing=mapper.readTree(http.perform(as(owner,get("/api/attachments").param("model","defect_record").param("recordId",Long.toString(record)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(1,listing.path("records").size());assertEquals(uploaded.path("id"),listing.path("records").get(0).path("id"));
        var download=http.perform(as(owner,get(uploaded.path("downloadUrl").asText()))).andExpect(status().isOk()).andExpect(header().string("Content-Type","application/octet-stream")).andExpect(header().string("X-Content-Type-Options","nosniff")).andReturn().getResponse();
        assertArrayEquals(content,download.getContentAsByteArray());assertTrue(download.getHeader("Content-Disposition").startsWith("attachment;"));
        assertTrue(download.getHeader("Cache-Control").contains("no-store"));
    }

    @Test void viewersMayDownloadButCannotUploadAndOtherModelRolesCannotGuessAttachmentIds() throws Exception {
        Session owner=user("operator",true),viewer=user("viewer",true);long record=record();JsonNode attachment=upload(owner,record);
        http.perform(as(viewer,get(attachment.path("downloadUrl").asText()))).andExpect(status().isOk());
        http.perform(as(viewer,uploadRequest(record,"proof.txt",new byte[]{1}))).andExpect(status().isForbidden());
        http.perform(as(viewer,delete(fileUrl(attachment)))).andExpect(status().isForbidden());
        Session administrator=admin();AuthContext.set(auth.authenticate(administrator.token));String role="only_student_"+UUID.randomUUID().toString().substring(0,8);
        try{auth.saveRole(mapper.valueToTree(Map.of("code",role,"name","仅学生读取","permissions",List.of("app_student:view"))));}finally{AuthContext.clear();}
        Session restricted=user(role,true);
        http.perform(as(restricted,get(attachment.path("downloadUrl").asText()))).andExpect(status().isForbidden());
        http.perform(as(restricted,get("/api/attachments").param("model","defect_record").param("recordId",Long.toString(record)))).andExpect(status().isForbidden());
    }

    @Test void onlyUploaderOrMaintainerMayDeleteAndDeletionHidesTheFile() throws Exception {
        Session owner=user("operator",true),other=user("operator",true);long record=record();JsonNode attachment=upload(owner,record);
        http.perform(as(other,delete(fileUrl(attachment)))).andExpect(status().isForbidden());
        long physicalBefore;try(var paths=Files.list(storage())){physicalBefore=paths.filter(Files::isRegularFile).count();}
        http.perform(as(owner,delete(fileUrl(attachment)))).andExpect(status().isOk());
        assertEquals(owner.username,jdbc.queryForObject("SELECT actor FROM t_attachment_event WHERE attachment_id=? AND action='DELETE'",String.class,attachment.path("id").asText()));
        http.perform(as(owner,get(attachment.path("downloadUrl").asText()))).andExpect(status().isNotFound());
        JsonNode listing=mapper.readTree(http.perform(as(owner,get("/api/attachments").param("model","defect_record").param("recordId",Long.toString(record)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(0,listing.path("records").size());
        try(var paths=Files.list(storage())){assertEquals(physicalBefore,paths.filter(Files::isRegularFile).count());}
        JsonNode second=upload(owner,record);http.perform(as(admin(),delete(fileUrl(second)))).andExpect(status().isOk());
    }

    @Test void removedSourceRecordsAndUnknownFilesCannotBeAccessed() throws Exception {
        Session owner=user("operator",true);long record=record();JsonNode attachment=upload(owner,record);
        jdbc.update("UPDATE t_defect_record SET deleted=TRUE WHERE id=?",record);
        http.perform(as(owner,get(attachment.path("downloadUrl").asText()))).andExpect(status().isNotFound());
        http.perform(as(owner,get("/api/attachments").param("model","defect_record").param("recordId",Long.toString(record)))).andExpect(status().isNotFound());
        http.perform(as(owner,uploadRequest(record,"proof.txt",new byte[]{1}))).andExpect(status().isNotFound());
        http.perform(as(owner,get("/api/attachments/"+UUID.randomUUID()+"/download"))).andExpect(status().isNotFound());
        http.perform(as(owner,uploadRequest(Long.MAX_VALUE,"proof.txt",new byte[]{1}))).andExpect(status().isNotFound());
        http.perform(as(owner,get("/api/attachments").param("model","student").param("recordId","1"))).andExpect(status().isNotFound());
    }

    @Test void invalidFilesAndOversizedBodiesAreRejectedWithoutStoringAttachments() throws Exception {
        Session owner=user("operator",true);long record=record();
        http.perform(as(owner,uploadRequest(record,"empty.txt",new byte[0]))).andExpect(status().isBadRequest());
        http.perform(as(owner,uploadRequest(record,"active.html","<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)))).andExpect(status().isBadRequest());
        http.perform(as(owner,uploadRequest(record,"large.txt",new byte[10*1024*1024+1]))).andExpect(status().isPayloadTooLarge());
        long filesBefore;try(var files=Files.list(storage())){filesBefore=files.count();}
        MockMultipartFile dishonestSize=new MockMultipartFile("file","large.txt","text/plain",new byte[10*1024*1024+1]) {
            @Override public long getSize(){return 1;}
        };
        http.perform(as(owner,multipart("/api/attachments").file(dishonestSize).param("model","defect_record").param("recordId",Long.toString(record)))).andExpect(status().isPayloadTooLarge());
        try(var files=Files.list(storage())){assertEquals(filesBefore,files.count(),"超限流写入必须清理临时文件");}
        JsonNode listing=mapper.readTree(http.perform(as(owner,get("/api/attachments").param("model","defect_record").param("recordId",Long.toString(record)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(0,listing.path("records").size());
    }

    @Test void hostileFileNamesNeverBecomeStoragePaths() throws Exception {
        Session owner=user("operator",true);long record=record();JsonNode attachment=upload(owner,record,"../../outside.txt",new byte[]{10,20,30});
        assertFalse(attachment.path("fileName").asText().contains("/"));assertFalse(attachment.path("fileName").asText().contains("\\"));
        assertFalse(Files.exists(storage().resolve("outside.txt")));assertFalse(Files.exists(storage().getParent().resolve("outside.txt")));
        assertArrayEquals(new byte[]{10,20,30},http.perform(as(owner,get(attachment.path("downloadUrl").asText()))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    @Test void perRecordLimitCountsActiveFilesAndAllowsReplacementAfterSoftDeletion() throws Exception {
        Session owner=user("operator",true);long record=record();JsonNode first=null;
        for(int i=0;i<20;i++){JsonNode file=upload(owner,record,"proof-"+i+".txt",new byte[]{1});if(first==null)first=file;}
        http.perform(as(owner,uploadRequest(record,"one-too-many.txt",new byte[]{1}))).andExpect(status().isBadRequest());
        http.perform(as(owner,delete(fileUrl(first)))).andExpect(status().isOk());
        upload(owner,record,"replacement.txt",new byte[]{1});
        JsonNode listing=mapper.readTree(http.perform(as(owner,get("/api/attachments").param("model","defect_record").param("recordId",Long.toString(record)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(20,listing.path("records").size());
    }
}

