package com.platform.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.platform.meta.MetaEngine;
import com.platform.auth.RpcAuthorization;
import com.platform.auth.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class RpcController {
    private static final Logger log=LoggerFactory.getLogger(RpcController.class);
    private final ObjectMapper mapper;
    private final MetaEngine engine;
    private final RpcDispatcher dispatcher;
    private final RpcAuthorization authorization;
    private final AuthService auth;
    public RpcController(ObjectMapper mapper,MetaEngine engine,RpcDispatcher dispatcher,RpcAuthorization authorization,AuthService auth) {this.mapper=mapper;this.engine=engine;this.dispatcher=dispatcher;this.authorization=authorization;this.auth=auth;}
    @PostMapping(value="/rpc",produces="application/json")
    public ResponseEntity<?> call(@RequestBody(required=false) String body) {
        JsonNode request;
        try {request=mapper.readTree(body==null?"":body);}catch(Exception ex){return ResponseEntity.ok(error(NullNode.instance,-32700,"JSON解析错误",null));}
        if(request==null)return ResponseEntity.ok(error(NullNode.instance,-32700,"JSON解析错误",null));
        if(request.isArray()) {
            if(request.isEmpty())return ResponseEntity.ok(error(NullNode.instance,-32600,"批量请求不能为空",null));
            if(request.size()>200)return ResponseEntity.ok(error(NullNode.instance,-32600,"批量请求不能超过200项",null));
            List<Object> results=new ArrayList<>();for(JsonNode item:request){Object result=one(item);if(result!=null)results.add(result);}
            return results.isEmpty()?ResponseEntity.noContent().build():ResponseEntity.ok(results);
        }
        Object result=one(request);return result==null?ResponseEntity.noContent().build():ResponseEntity.ok(result);
    }
    @GetMapping("/health") public Map<String,Object> health() {return Map.of("status","UP","version",engine.snapshot().version());}
    private Object one(JsonNode request) {
        JsonNode id=request.path("id");boolean idValid=id.isMissingNode()||id.isNull()||id.isTextual()||id.isNumber();
        if(!request.isObject()||!request.path("jsonrpc").isTextual()||!request.path("jsonrpc").asText().equals("2.0")||!request.path("method").isTextual()||!idValid)
            return error(idValid&&!id.isMissingNode()?id:NullNode.instance,-32600,"无效的JSONRPC 2.0请求",null);
        boolean notification=!request.has("id");
        boolean succeeded=false;
        try {
            var snapshot=engine.snapshot();String method=request.get("method").asText();
            authorization.require(snapshot,method,request.get("params"));
            Object result=authorization.filter(snapshot,method,dispatcher.dispatch(snapshot,method,request.get("params")));
            succeeded=true;
            if(notification)return null;
            Map<String,Object> response=new LinkedHashMap<>();response.put("jsonrpc","2.0");response.put("id",id);response.put("result",result);return response;
        }catch(RpcException ex){return notification?null:error(id,ex.getCode(),ex.getMessage(),ex.getData());}
        catch(Exception ex){log.error("RPC method {} failed",request.path("method").asText(),ex);return notification?null:error(id,-32603,"服务器内部错误，请查看服务端日志",null);}
        finally{String method=request.path("method").asText();if(method.startsWith("service.")||method.equals("meta.reload")||method.matches(".+\\.(create|update|delete|batchCreate|batchDelete|adjust|transition)"))auth.audit(method,succeeded);}
    }
    private Map<String,Object> error(JsonNode id,int code,String message,Object data) {
        Map<String,Object> detail=new LinkedHashMap<>();detail.put("code",code);detail.put("message",message);if(data!=null)detail.put("data",data);
        return Map.of("jsonrpc","2.0","id",id.isMissingNode()?NullNode.instance:id,"error",detail);
    }
}
