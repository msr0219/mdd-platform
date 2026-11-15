package com.platform.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component @Order(-100)
public class AuthFilter extends OncePerRequestFilter {
    private final AuthService auth;private final ObjectMapper mapper;
    public AuthFilter(AuthService auth,ObjectMapper mapper){this.auth=auth;this.mapper=mapper;}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        // Match the servlet's decoded route, including matrix parameters and encoded path variants.
        String path=req.getServletPath();
        if(path==null||path.isBlank())path=URLDecoder.decode(req.getRequestURI().substring(req.getContextPath().length()).replace("+","%2B"),StandardCharsets.UTF_8);
        path=path.replaceAll(";[^/]*", "").replaceAll("/{2,}","/");
        boolean protectedPath=path.equals("/rpc")||path.startsWith("/rpc/")||path.startsWith("/api/");
        if(!protectedPath){chain.doFilter(req,res);return;}
        res.setHeader("Cache-Control","no-store");res.setHeader("X-Content-Type-Options","nosniff");
        try {
            String token=null;if(req.getCookies()!=null)for(Cookie c:req.getCookies())if(c.getName().equals("MDD_SESSION"))token=c.getValue();
            AuthContext.set(auth.authenticate(token));
            boolean login=path.equals("/api/auth/login")&&req.getMethod().equals("POST");
            if(!login)auth.require();
            if(!Set.of("GET","HEAD","OPTIONS").contains(req.getMethod())) {
                String origin=req.getHeader("Origin");
                if(origin!=null){
                    String own=req.getScheme()+"://"+req.getServerName()+((req.getServerPort()==80||req.getServerPort()==443)?"":":"+req.getServerPort());
                    if(!Set.of(own,"http://127.0.0.1:5173","http://localhost:5173").contains(origin))throw AuthService.fail(403,"请求来源不允许");
                }
                if(!login){String csrf=req.getHeader("X-CSRF-Token");if(csrf==null||!MessageDigest.isEqual(csrf.getBytes(StandardCharsets.UTF_8),auth.require().csrf().getBytes(StandardCharsets.UTF_8)))throw AuthService.fail(403,"安全校验失败，请刷新页面重试");}
            }
            if(!login&&auth.require().mustChangePassword()&&!Set.of("/api/auth/me","/api/auth/password","/api/auth/logout").contains(path))throw AuthService.fail(403,"首次登录或密码重置后必须先修改密码");
            chain.doFilter(req,res);
        }catch(ResponseStatusException ex){res.setStatus(ex.getStatusCode().value());mapper.writeValue(res.getOutputStream(),Map.of("message",Objects.toString(ex.getReason(),"访问被拒绝")));}
        finally{AuthContext.clear();}
    }
}
