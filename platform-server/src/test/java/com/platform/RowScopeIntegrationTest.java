package com.platform;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import java.nio.file.Path;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:row-scope-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","platform.demo.enabled=false","platform.auth.bootstrap-password=InitialTest!12345"})
@AutoConfigureMockMvc
class RowScopeIntegrationTest extends RowScopeContract {
    static final Path META=metadata(), STORAGE=storage();
    @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("platform.meta-dir",()->META.toString());r.add("platform.attachments.directory",()->STORAGE.toString());}
}
