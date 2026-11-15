package com.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.auth.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

/** Unlike MockMvc multipart, this exercises Tomcat's real size limit and pre-handler error path. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:attachment-http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "platform.demo.enabled=false","platform.auth.bootstrap-password=InitialTest!12345"
})
class AttachmentHttpIntegrationTest {
    static final Path STORAGE=AttachmentContract.temporaryStorage();
    @LocalServerPort int port;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){
        registry.add("platform.meta-dir",()->(Files.isDirectory(Path.of("../meta"))?Path.of("../meta"):Path.of("meta")).toAbsolutePath().toString());
        registry.add("platform.attachments.directory",()->STORAGE.toString());
    }
    @Test void realEncodedAndMatrixPathsStillRequireAuthentication() throws Exception {
        HttpClient client=HttpClient.newHttpClient();
        for(String path:new String[]{"/rpc;alias=1","/%72pc","/api;alias=1/auth/users","/api/%61uth/users"}) {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"student.list\",\"params\":{}}")).build();
            assertEquals(401,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode(),path);
        }
    }
    @Test void realOversizedMultipartReturnsJsonError() throws Exception {
        jdbc.update("UPDATE t_sys_user SET must_change=FALSE WHERE username='admin'");
        var login=auth.login("admin","InitialTest!12345","attachment-http-tests");
        try {
            String boundary="MddAttachmentHttpBoundary";
            byte[] prefix=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"large.txt\"\r\nContent-Type: text/plain\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            byte[] suffix=("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII);
            byte[] body=Arrays.copyOf(prefix,prefix.length+10*1024*1024+1+suffix.length);
            System.arraycopy(suffix,0,body,body.length-suffix.length,suffix.length);
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/attachments?model=defect_record&recordId=1"))
                .timeout(Duration.ofSeconds(20)).header("Cookie","MDD_SESSION="+login.token())
                .header("X-CSRF-Token",login.user().get("csrfToken").toString())
                .header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
            assertEquals(413,response.statusCode());
            assertTrue(mapper.readTree(response.body()).path("message").asText().contains("10 MiB"));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM t_attachment_file",Integer.class));
        } finally {
            com.platform.auth.AuthContext.set(auth.authenticate(login.token()));
            try {auth.logout();} finally {com.platform.auth.AuthContext.clear();}
        }
    }
}
