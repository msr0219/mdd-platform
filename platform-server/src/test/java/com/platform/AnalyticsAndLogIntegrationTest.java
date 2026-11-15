package com.platform;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import java.nio.file.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:analytics-log-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","platform.demo.enabled=false","platform.auth.bootstrap-password=InitialTest!12345"})
class AnalyticsAndLogIntegrationTest extends AnalyticsAndLogContract {
    @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("platform.meta-dir",()->(Files.isDirectory(Path.of("../meta"))?Path.of("../meta"):Path.of("meta")).toAbsolutePath().toString());}
    @org.junit.jupiter.api.Test void upgradesTheLegacyLogTableWithoutRecreatingIt() {
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:legacy-log-"+key()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        var old=new org.springframework.jdbc.core.JdbcTemplate(source);
        old.execute("CREATE TABLE t_service_call_log(trace_id VARCHAR(36) PRIMARY KEY,service_code VARCHAR(128) NOT NULL,params_hash VARCHAR(64) NOT NULL,cost_ms BIGINT NOT NULL,outcome VARCHAR(32) NOT NULL,error_code INTEGER,created_at TIMESTAMP NOT NULL)");
        old.update("INSERT INTO t_service_call_log VALUES('legacy','service','hash',1,'SUCCESS',NULL,CURRENT_TIMESTAMP)");
        var upgraded=new com.platform.service.ServiceCallLog(old,new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));upgraded.initialize();
        org.junit.jupiter.api.Assertions.assertEquals(1,old.queryForObject("SELECT COUNT(*) FROM t_service_call_log WHERE trace_id='legacy' AND root_trace_id IS NULL AND replayed=FALSE",Integer.class));
    }
}
