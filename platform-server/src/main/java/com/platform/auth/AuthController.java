package com.platform.auth;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.*;

@RestController @RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth){this.auth=auth;}
    @PostMapping("/login") public Object login(@RequestBody JsonNode p,HttpServletRequest req,HttpServletResponse res){var result=auth.login(p.path("username").asText(),p.path("password").asText(),req.getRemoteAddr());cookie(res,result.token(),req.isSecure(),8*3600);return result.user();}
    @GetMapping("/me") public Object me(){return auth.describe(auth.require());}
    @PostMapping("/logout") public Object logout(HttpServletRequest req,HttpServletResponse res){auth.logout();cookie(res,"",req.isSecure(),0);return Map.of("success",true);}
    @PostMapping("/password") public Object password(@RequestBody JsonNode p,HttpServletRequest req,HttpServletResponse res){auth.changePassword(p.path("oldPassword").asText(),p.path("newPassword").asText());cookie(res,"",req.isSecure(),0);return Map.of("success",true);}
    @GetMapping("/users") public Object users(){return auth.users();}
    @PostMapping("/users") public Object saveUser(@RequestBody JsonNode p){return auth.saveUser(p);}
    @DeleteMapping("/users/{id}") public Object deleteUser(@PathVariable long id,@RequestParam long version){auth.deleteUser(id,version);return Map.of("success",true);}
    @PostMapping("/users/{id}/password") public Object reset(@PathVariable long id,@RequestBody JsonNode p){auth.resetPassword(id,p.path("password").asText());return Map.of("success",true);}
    @GetMapping("/roles") public Object roles(){return auth.roles();}
    @GetMapping("/applications") public Object applications(){return auth.applicationCatalog();}
    @GetMapping("/audits") public Object audits(){return auth.audits();}
    @PostMapping("/roles") public Object saveRole(@RequestBody JsonNode p){auth.saveRole(p);return Map.of("success",true);}
    @DeleteMapping("/roles/{code}") public Object deleteRole(@PathVariable String code){auth.deleteRole(code);return Map.of("success",true);}
    private void cookie(HttpServletResponse res,String token,boolean secure,int seconds){res.addHeader("Set-Cookie",ResponseCookie.from("MDD_SESSION",token).httpOnly(true).secure(secure).sameSite("Strict").path("/").maxAge(Duration.ofSeconds(seconds)).build().toString());}
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> error(ResponseStatusException ex){return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message",Objects.toString(ex.getReason(),"操作失败")));}
}
