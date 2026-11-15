package com.platform.attachments;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/attachments")
public class AttachmentController {
    private final AttachmentService attachments;
    public AttachmentController(AttachmentService attachments) { this.attachments = attachments; }

    @GetMapping public Object list(@RequestParam String model, @RequestParam String recordId) {
        return Map.of("records", attachments.list(model, recordId));
    }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object upload(@RequestParam String model, @RequestParam String recordId, @RequestPart("file") MultipartFile file) throws IOException {
        return attachments.upload(model, recordId, file);
    }
    @GetMapping("/{id}/download") public ResponseEntity<Resource> download(@PathVariable String id) throws IOException {
        AttachmentService.Download file = attachments.download(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(file.path()));
    }
    @DeleteMapping("/{id}") public Object delete(@PathVariable String id) {
        attachments.delete(id); return Map.of("success", true);
    }
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> error(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", Objects.toString(ex.getReason(), "附件操作失败")));
    }
    @ExceptionHandler(IOException.class) public ResponseEntity<?> storage(IOException ex) {
        return ResponseEntity.internalServerError().body(Map.of("message", "附件文件处理失败，请稍后重试"));
    }
}
