package com.platform.attachments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.auth.AuthContext;
import com.platform.auth.AuthService;
import com.platform.auth.RpcAuthorization;
import com.platform.meta.MetaEngine;
import com.platform.meta.MetaSnapshot;
import com.platform.rpc.RpcException;
import com.platform.service.CrudExecutor;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Files belong to existing business records; public URLs never reveal storage paths. */
@Service
public class AttachmentService {
    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    public static final int MAX_ATTACHMENTS = 20;
    private static final Set<String> EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "pdf", "txt", "csv", "docx", "xlsx");
    private final MetaEngine engine;
    private final CrudExecutor crud;
    private final RpcAuthorization authorization;
    private final AuthService auth;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private Path directory;

    public AttachmentService(MetaEngine engine, CrudExecutor crud, RpcAuthorization authorization, AuthService auth,
                             ObjectMapper mapper, JdbcTemplate jdbc, PlatformTransactionManager manager,
                             @Value("${platform.attachments.directory:../uploads}") String directory) {
        this.engine = engine; this.crud = crud; this.authorization = authorization; this.auth = auth;
        this.mapper = mapper; this.jdbc = jdbc; this.tx = new TransactionTemplate(manager);
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    @PostConstruct public void initialize() throws IOException {
        Files.createDirectories(directory);
        directory = directory.toRealPath();
        jdbc.execute("CREATE TABLE IF NOT EXISTS t_attachment_file (id VARCHAR(36) PRIMARY KEY, model_code VARCHAR(100) NOT NULL, record_id VARCHAR(100) NOT NULL, original_name VARCHAR(255) NOT NULL, content_type VARCHAR(100) NOT NULL, byte_size BIGINT NOT NULL, sha256 VARCHAR(64) NOT NULL, created_by VARCHAR(100) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, deleted BOOLEAN NOT NULL DEFAULT FALSE, deleted_by VARCHAR(100), deleted_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS t_attachment_event (id VARCHAR(36) PRIMARY KEY, attachment_id VARCHAR(36) NOT NULL, action VARCHAR(20) NOT NULL, actor VARCHAR(100) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
    }

    private record Target(String model, String recordId) { }
    public record Download(Path path, String fileName, long size) { }

    public List<Map<String,Object>> list(String model, String recordId) {
        Target target = target(engine.snapshot(), model, recordId, false);
        return jdbc.queryForList("SELECT * FROM t_attachment_file WHERE model_code=? AND record_id=? AND deleted=FALSE ORDER BY created_at DESC,id", target.model, target.recordId)
                .stream().map(this::metadata).toList();
    }

    /** A single-instance write lock keeps the record limit and commit indivisible for uploads/deletes. */
    public synchronized Map<String,Object> upload(String model, String recordId, MultipartFile file) throws IOException {
        MetaSnapshot snapshot = engine.snapshot();
        Target target = target(snapshot, model, recordId, true);
        if (file == null || file.isEmpty()) throw fail(400, "请选择非空文件");
        if (file.getSize() > MAX_FILE_BYTES) throw fail(413, "单个附件不能超过10 MiB");
        String name = fileName(file.getOriginalFilename());
        String id = UUID.randomUUID().toString();
        Path temporary = Files.createTempFile(directory, "upload-", ".tmp");
        Path destination = storagePath(id);
        try {
            MessageDigest digest = sha256();
            long size = 0;
            try (InputStream input = file.getInputStream(); OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) != -1;) {
                    size += count;
                    if (size > MAX_FILE_BYTES) throw fail(413, "单个附件不能超过10 MiB");
                    output.write(buffer, 0, count); digest.update(buffer, 0, count);
                }
            }
            if (size == 0) throw fail(400, "请选择非空文件");
            final long bytes = size;
            final String checksum = HexFormat.of().formatHex(digest.digest());
            final String actor = auth.require().username();
            return tx.execute(status -> {
                // Recheck the source inside the metadata transaction before publishing this file.
                target(snapshot, target.model, target.recordId, true);
                int count = jdbc.queryForObject("SELECT COUNT(*) FROM t_attachment_file WHERE model_code=? AND record_id=? AND deleted=FALSE", Integer.class, target.model, target.recordId);
                if (count >= MAX_ATTACHMENTS) throw fail(400, "每条记录最多保留20个附件，请先移除不需要的附件");
                try {
                    try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE); }
                    catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, destination); }
                } catch (IOException ex) { throw new UncheckedIOException(ex); }
                jdbc.update("INSERT INTO t_attachment_file(id,model_code,record_id,original_name,content_type,byte_size,sha256,created_by) VALUES(?,?,?,?,?,?,?,?)", id, target.model, target.recordId, name, "application/octet-stream", bytes, checksum, actor);
                event(id, "UPLOAD", actor);
                return metadata(row(id));
            });
        } catch (UncheckedIOException ex) {
            deleteFailedUpload(destination); throw ex.getCause();
        } catch (RuntimeException ex) {
            deleteFailedUpload(destination); throw ex;
        } finally { Files.deleteIfExists(temporary); }
    }

    public Download download(String id) throws IOException {
        auth.require();
        Map<String,Object> row = row(id);
        target(engine.snapshot(), row.get("model_code").toString(), row.get("record_id").toString(), false);
        Path file = storagePath(id);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != ((Number) row.get("byte_size")).longValue())
            throw fail(404, "附件文件不可用，请联系管理员恢复备份");
        return new Download(file, row.get("original_name").toString(), ((Number) row.get("byte_size")).longValue());
    }

    /** Removal retains bytes and audit history so a user action cannot erase evidence permanently. */
    public synchronized void delete(String id) {
        var user = auth.require();
        tx.executeWithoutResult(status -> {
            Map<String,Object> row = row(id);
            target(engine.snapshot(), row.get("model_code").toString(), row.get("record_id").toString(), true);
            if (!user.username().equals(row.get("created_by")) && !user.allows("data:maintain"))
                throw fail(403, "只能移除自己上传的附件");
            int changed = jdbc.update("UPDATE t_attachment_file SET deleted=TRUE,deleted_by=?,deleted_at=CURRENT_TIMESTAMP WHERE id=? AND deleted=FALSE", user.username(), id);
            if (changed != 1) throw fail(404, "附件不存在或已移除");
            event(id, "DELETE", user.username());
        });
    }

    private Target target(MetaSnapshot snapshot, String model, String recordId, boolean write) {
        var user = auth.require();
        JsonNode definition = snapshot.models().get(model);
        if (definition == null || !definition.path("model").path("attachments").isBoolean() || !definition.path("model").path("attachments").booleanValue())
            throw fail(404, "该模型未启用附件");
        if (!authorization.modelRead(model)) throw fail(403, "没有读取此记录附件的权限");
        if (write && !authorization.manageAttachments(snapshot, model))
            throw fail(403, "没有管理此记录附件的权限");
        if (recordId == null || recordId.isBlank() || recordId.length() > 100) throw fail(400, "请提供有效的记录编号");
        var params = mapper.createObjectNode();
        try {
            if (definition.path("model").path("idType").asText("auto").equals("uuid")) params.put("id", recordId);
            else params.put("id", Long.parseLong(recordId));
            JsonNode record = mapper.valueToTree(crud.execute(snapshot, model, "read", params));
            return new Target(model, record.path("id").asText());
        } catch (NumberFormatException ex) { throw fail(400, "记录编号格式错误"); }
        catch (RpcException ex) { throw fail(ex.getCode() == -32002 ? 404 : 400, ex.getCode() == -32002 ? "记录不存在或已删除" : "记录编号格式错误"); }
    }

    private Map<String,Object> row(String id) {
        validateId(id);
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT * FROM t_attachment_file WHERE id=? AND deleted=FALSE", id);
        if (rows.isEmpty()) throw fail(404, "附件不存在或已移除");
        return rows.get(0);
    }
    private Map<String,Object> metadata(Map<String,Object> row) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id")); result.put("model", row.get("model_code")); result.put("recordId", row.get("record_id"));
        result.put("fileName", row.get("original_name")); result.put("size", row.get("byte_size"));
        result.put("createdBy", row.get("created_by")); result.put("createdAt", row.get("created_at").toString());
        result.put("downloadUrl", "/api/attachments/" + row.get("id") + "/download");
        return result;
    }
    private void event(String id, String action, String actor) {
        jdbc.update("INSERT INTO t_attachment_event(id,attachment_id,action,actor) VALUES(?,?,?,?)", UUID.randomUUID().toString(), id, action, actor);
    }
    private Path storagePath(String id) {
        validateId(id);
        Path path = directory.resolve(id + ".blob").normalize();
        if (!path.getParent().equals(directory)) throw fail(404, "附件不存在");
        return path;
    }
    private void validateId(String id) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw fail(404, "附件不存在");
    }
    private String fileName(String original) {
        if (original == null || original.contains("\r") || original.contains("\n")) throw fail(400, "文件名称无效");
        String normalized = original.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\\p{Cf}]", "_").trim();
        if (name.isEmpty() || name.length() > 255) throw fail(400, "文件名称须为1至255个字符");
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || !EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT)))
            throw fail(400, "支持PNG、JPEG、WebP、PDF、TXT、CSV、DOCX和XLSX附件");
        return name;
    }
    private MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private void deleteFailedUpload(Path file) {
        try { Files.deleteIfExists(file); }
        catch (IOException ex) { org.slf4j.LoggerFactory.getLogger(AttachmentService.class).warn("Failed upload left an unreferenced attachment file"); }
    }
    private static ResponseStatusException fail(int status, String message) {
        return new ResponseStatusException(HttpStatus.valueOf(status), message);
    }
}
