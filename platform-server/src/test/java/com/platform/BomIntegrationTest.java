package com.platform;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import java.nio.file.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:bom-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","platform.demo.enabled=false","platform.auth.bootstrap-password=InitialTest!12345"})
class BomIntegrationTest extends BomContract {
    @DynamicPropertySource static void meta(DynamicPropertyRegistry r){r.add("platform.meta-dir",()->(Files.isDirectory(Path.of("../meta"))?Path.of("../meta"):Path.of("meta")).toAbsolutePath().toString());}
}
