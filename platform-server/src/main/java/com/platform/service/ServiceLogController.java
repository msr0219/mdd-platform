package com.platform.service;

import com.platform.auth.AuthService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth/service-logs")
public class ServiceLogController {
    private final AuthService auth;
    private final ServiceCallLog logs;
    public ServiceLogController(AuthService auth, ServiceCallLog logs) { this.auth = auth; this.logs = logs; }
    @GetMapping public Object list(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int pageSize,
            @RequestParam(defaultValue="") String service, @RequestParam(defaultValue="") String actor,
            @RequestParam(defaultValue="") String outcome, @RequestParam(defaultValue="") String root,
            @RequestParam(defaultValue="") String requestKey) {
        auth.permission("users:manage");
        try { return logs.list(page, pageSize, service, actor, outcome, root, requestKey); }
        catch (IllegalArgumentException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage()); }
    }
}
