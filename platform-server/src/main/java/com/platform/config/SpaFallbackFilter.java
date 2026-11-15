package com.platform.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Deep links are handled by the same Vue runtime after metadata routes have been installed. */
@Component
public class SpaFallbackFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        String accept=request.getHeader("Accept");
        if(request.getMethod().equals("GET")&&accept!=null&&accept.contains("text/html")&&!path.startsWith("/api/")&&!path.startsWith("/rpc")&&!path.startsWith("/health")&&!path.startsWith("/assets/")&&!path.substring(path.lastIndexOf('/')+1).contains(".")&&new ClassPathResource("static/index.html").exists()) {
            request.getRequestDispatcher("/index.html").forward(request,response);return;
        }
        chain.doFilter(request,response);
    }
}
