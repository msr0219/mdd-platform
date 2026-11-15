package com.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.auth.AuthContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** One row per service invocation; nested calls share a root and a logical transaction identifier. */
@Component
public class ServiceCallLog {
    private static final Logger LOG = LoggerFactory.getLogger(ServiceCallLog.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public ServiceCallLog(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        initialize();
    }

    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS t_service_call_log (trace_id VARCHAR(36) PRIMARY KEY, "
                + "service_code VARCHAR(128) NOT NULL, params_hash VARCHAR(64) NOT NULL, cost_ms BIGINT NOT NULL, "
                + "outcome VARCHAR(32) NOT NULL, error_code INTEGER, created_at TIMESTAMP NOT NULL)");
        Set<String> columns = jdbc.execute((ConnectionCallback<Set<String>>) connection -> {
            Set<String> found = new HashSet<>();
            try (var result = connection.getMetaData().getColumns(connection.getCatalog(), connection.getSchema(), "%", null)) {
                while (result.next()) if ("t_service_call_log".equalsIgnoreCase(result.getString("TABLE_NAME")))
                    found.add(result.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
            return found;
        });
        Map<String,String> additions = new LinkedHashMap<>();
        additions.put("root_trace_id", "VARCHAR(36) NULL"); additions.put("parent_trace_id", "VARCHAR(36) NULL");
        additions.put("transaction_id", "VARCHAR(36) NULL"); additions.put("actor", "VARCHAR(64) NULL");
        additions.put("request_key", "VARCHAR(160) NULL"); additions.put("param_names", "VARCHAR(2000) NULL");
        additions.put("replayed", "BOOLEAN NOT NULL DEFAULT FALSE");
        additions.forEach((name, type) -> { if (!columns.contains(name)) jdbc.execute("ALTER TABLE t_service_call_log ADD COLUMN " + name + " " + type); });
    }

    public static final class Entry {
        final String id = UUID.randomUUID().toString();
        final String rootId, parentId, transactionId, actor, code, hash, names;
        final long started = System.nanoTime();
        String requestKey, outcome = "SUCCESS";
        Integer errorCode;
        boolean replayed;
        long elapsed;
        Entry(Entry parent, String code, JsonNode params, String hash, boolean transactional) {
            rootId = parent == null ? id : parent.rootId; parentId = parent == null ? null : parent.id;
            transactionId = parent != null && parent.transactionId != null ? parent.transactionId : transactional ? UUID.randomUUID().toString() : null;
            actor = AuthContext.actor(); this.code = code; this.hash = hash;
            List<String> fields = new ArrayList<>(); if (params != null && params.isObject()) params.fieldNames().forEachRemaining(fields::add);
            Collections.sort(fields); String joined = String.join(",", fields); names = joined.substring(0, Math.min(2000, joined.length()));
        }
        void finish(Integer error) { errorCode = error; outcome = error == null ? replayed ? "REPLAYED" : "SUCCESS" : "FAILED"; elapsed = (System.nanoTime() - started) / 1_000_000; }
    }

    public void save(List<Entry> entries, Entry root) {
        try {
            transaction.executeWithoutResult(status -> {
                Set<String> failedTransactions = new HashSet<>();
                for (Entry entry : entries) if (entry.errorCode != null && entry.transactionId != null) failedTransactions.add(entry.transactionId);
                for (Entry entry : entries) {
                    String outcome = entry.outcome;
                    if (failedTransactions.contains(entry.transactionId) && outcome.equals("SUCCESS")) outcome = "ROLLED_BACK";
                    jdbc.update("INSERT INTO t_service_call_log(trace_id,root_trace_id,parent_trace_id,transaction_id,actor,service_code,params_hash,param_names,request_key,replayed,cost_ms,outcome,error_code,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                            entry.id, entry.rootId, entry.parentId, entry.transactionId, entry.actor, entry.code, entry.hash,
                            entry.names, entry.requestKey, entry.replayed, entry.elapsed, outcome, entry.errorCode);
                }
            });
        } catch (RuntimeException error) {
            LOG.error("Unable to save service call log; trace {}", root.id, error);
        }
    }

    public Map<String,Object> list(int page, int pageSize, String service, String actor, String outcome, String root, String requestKey) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("分页参数无效");
        StringBuilder where = new StringBuilder(" WHERE 1=1"); List<Object> args = new ArrayList<>();
        String[] fields = {"service_code", "actor", "outcome", "COALESCE(root_trace_id,trace_id)", "request_key"};
        String[] values = {service, actor, outcome, root, requestKey};
        for (int i = 0; i < fields.length; i++) if (values[i] != null && !values[i].isBlank()) {
            if (values[i].length() > 160) throw new IllegalArgumentException("筛选条件过长");
            where.append(" AND ").append(fields[i]).append("=?"); args.add(values[i]);
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM t_service_call_log" + where, Long.class, args.toArray());
        args.add(pageSize); args.add(((long) page - 1) * pageSize);
        List<Map<String,Object>> records = jdbc.queryForList("SELECT * FROM t_service_call_log" + where + " ORDER BY created_at DESC, trace_id LIMIT ? OFFSET ?", args.toArray());
        return Map.of("records", records, "total", total, "page", page, "pageSize", pageSize);
    }
}
