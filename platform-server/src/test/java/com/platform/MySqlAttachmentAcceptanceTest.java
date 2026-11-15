package com.platform;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import java.nio.file.*;

@EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL",matches="jdbc:mysql:.*")
@SpringBootTest(properties={"spring.datasource.url=${MYSQL_TEST_URL}","spring.datasource.username=${MYSQL_TEST_USERNAME}","spring.datasource.password=${MYSQL_TEST_PASSWORD}","platform.demo.enabled=false","platform.auth.bootstrap-password=InitialTest!12345"})
@AutoConfigureMockMvc
class MySqlAttachmentAcceptanceTest extends AttachmentContract {
    static final Path STORAGE=temporaryStorage();
    @Override Path storage(){return STORAGE;}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){
        registry.add("platform.meta-dir",()->(Files.isDirectory(Path.of("../meta"))?Path.of("../meta"):Path.of("meta")).toAbsolutePath().toString());
        registry.add("platform.attachments.directory",()->STORAGE.toString());
    }
}
