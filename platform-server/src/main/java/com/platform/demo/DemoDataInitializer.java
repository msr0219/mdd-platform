package com.platform.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.meta.MetaEngine;
import com.platform.meta.MetaSnapshot;
import com.platform.service.CrudExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Files;
import java.nio.file.Path;

/** Seeds only an entirely empty database, never overwrites existing business records. */
@Component
@Order(100)
public class DemoDataInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);
    private final MetaEngine engine;
    private final CrudExecutor crud;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Path metaDir;
    private final boolean enabled;

    public DemoDataInitializer(MetaEngine engine, CrudExecutor crud, ObjectMapper mapper,
                               JdbcTemplate jdbc, PlatformTransactionManager manager,
                               @Value("${platform.meta-dir:../meta}") String dir,
                               @Value("${platform.demo.enabled:true}") boolean enabled) {
        this.engine = engine; this.crud = crud; this.mapper = mapper;
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager);
        this.metaDir = Path.of(dir); this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path seedFile = metaDir.resolve("demo/seed.json");
        if (!enabled || !Files.isRegularFile(seedFile)) return;
        jdbc.execute("CREATE TABLE IF NOT EXISTS t_demo_seed (seed_code VARCHAR(80) PRIMARY KEY, applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM t_demo_seed WHERE seed_code = ?", Integer.class, "initial-v1") > 0) return;
        MetaSnapshot snapshot = engine.snapshot();
        JsonNode seed = mapper.readTree(seedFile.toFile());
        for (var names = seed.fieldNames(); names.hasNext();) {
            String model = names.next();
            JsonNode listed = mapper.valueToTree(crud.execute(snapshot, model, "list", mapper.createObjectNode().put("pageSize", 1)));
            if (listed.path("total").asLong() != 0) {
                log.info("已有业务数据，跳过演示初始化；如需空库可设置 DEMO_ENABLED=false。");
                return;
            }
        }
        tx.executeWithoutResult(status -> {
            seed.fields().forEachRemaining(entry -> {
                for (JsonNode source : entry.getValue()) {
                    ObjectNode data = source.deepCopy();
                    JsonNode transitions = data.remove("_transitions");
                    JsonNode record = mapper.valueToTree(crud.execute(snapshot, entry.getKey(), "create", data));
                    if (transitions != null) for (JsonNode next : transitions) {
                        ObjectNode request = mapper.createObjectNode();
                        request.set("id", record.get("id")); request.set("version", record.get("version"));
                        request.put("to", next.asText()).put("reason", "演示数据初始化");
                        record = mapper.valueToTree(crud.execute(snapshot, entry.getKey(), "transition", request));
                    }
                }
            });
            jdbc.update("INSERT INTO t_demo_seed(seed_code) VALUES (?)", "initial-v1");
        });
        log.info("演示数据初始化完成：{} 个模型", seed.size());
    }
}
