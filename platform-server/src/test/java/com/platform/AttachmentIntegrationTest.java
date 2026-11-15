package com.platform;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import java.nio.file.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:attachment-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "platform.demo.enabled=false", "platform.auth.bootstrap-password=InitialTest!12345"})
@AutoConfigureMockMvc
class AttachmentIntegrationTest extends AttachmentContract {
    static final Path STORAGE=temporaryStorage();
    @Override Path storage(){return STORAGE;}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){
        registry.add("platform.meta-dir",()->(Files.isDirectory(Path.of("../meta"))?Path.of("../meta"):Path.of("meta")).toAbsolutePath().toString());
        registry.add("platform.attachments.directory",()->STORAGE.toString());
    }
}
