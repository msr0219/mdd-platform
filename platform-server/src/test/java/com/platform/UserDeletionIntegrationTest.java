package com.platform;

import com.platform.auth.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:user-deletion-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","platform.demo.enabled=false","platform.auth.bootstrap-password=InitialTest!12345"})
@AutoConfigureMockMvc
class UserDeletionIntegrationTest extends UserDeletionContract {
    static final Path STORAGE=AttachmentContract.temporaryStorage();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("platform.meta-dir",()->(Files.isDirectory(Path.of("../meta"))?Path.of("../meta"):Path.of("meta")).toAbsolutePath().toString());r.add("platform.attachments.directory",()->STORAGE.toString());}
    @Test void upgradingAnOldUserTablePreservesAccountsAndCanRunRepeatedly()throws Exception {
        var source=new DriverManagerDataSource("jdbc:h2:mem:user-deletion-upgrade-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        var old=new JdbcTemplate(source);
        old.execute("CREATE TABLE t_sys_user (id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(50) NOT NULL UNIQUE, display_name VARCHAR(80) NOT NULL, password_hash VARCHAR(400) NOT NULL, roles_json TEXT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, must_change BOOLEAN NOT NULL DEFAULT TRUE, version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        old.update("INSERT INTO t_sys_user(username,display_name,password_hash,roles_json,enabled,version) VALUES(?,?,?,?,?,?)","legacy_user","旧用户","unchanged-hash","[\"viewer\"]",false,7);
        var before=old.queryForMap("SELECT * FROM t_sys_user WHERE username='legacy_user'");
        var upgrade=new AuthService(old,mapper,new DataSourceTransactionManager(source),engine,applications,"","target/unused-upgrade-credentials.txt");
        upgrade.initialize();upgrade.initialize();var after=old.queryForMap("SELECT * FROM t_sys_user WHERE username='legacy_user'");
        before.forEach((field,value)->assertEquals(value,after.get(field),field));
        assertEquals(false,after.get("deleted"));assertNull(after.get("deleted_at"));assertNull(after.get("deleted_by"));
        assertEquals(1,old.queryForObject("SELECT COUNT(*) FROM t_sys_user",Integer.class));
    }
}
