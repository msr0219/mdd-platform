package com.platform.attachments;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import java.util.Map;

/** Multipart parsing can fail before Spring has selected a controller. */
@RestControllerAdvice
public class MultipartErrorAdvice {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> tooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(413).body(Map.of("message", "单个附件不能超过10 MiB，请同时检查请求总大小"));
    }
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<?> malformed(MultipartException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", "附件上传请求格式无效"));
    }
}
