package com.platform.auth;

import com.fasterxml.jackson.databind.*;
import com.platform.meta.MetaEngine;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {
    private static final Set<String> BUILTIN_ROLES=Set.of("admin","modeler","operator","viewer");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final MetaEngine engine;
    private final ApplicationAccess applications;
    private final String initialPassword;
    private final Path credentialFile;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private record Attempts(int failures, Instant until) { }
    public AuthService(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager manager, MetaEngine engine, ApplicationAccess applications,
                       @Value("${platform.auth.bootstrap-password:}") String initialPassword,
                       @Value("${platform.auth.credentials-file:../.logs/admin-initial-password.txt}") String credentialFile) {
        this.jdbc=jdbc; this.mapper=mapper; this.tx=new TransactionTemplate(manager);
        this.engine=engine;this.applications=applications;
        this.initialPassword=initialPassword; this.credentialFile=Path.of(credentialFile).toAbsolutePath();
    }
    @PostConstruct public void initialize() throws Exception {
        jdbc.execute("CREATE TABLE IF NOT EXISTS t_sys_role (code VARCHAR(50) PRIMARY KEY, name VARCHAR(80) NOT NULL, permissions_json TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS t_sys_user (id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(50) NOT NULL UNIQUE, display_name VARCHAR(80) NOT NULL, password_hash VARCHAR(400) NOT NULL, roles_json TEXT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, must_change BOOLEAN NOT NULL DEFAULT TRUE, version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        migrateUserDeletionColumns();
        jdbc.execute("CREATE TABLE IF NOT EXISTS t_sys_session (token_hash VARCHAR(64) PRIMARY KEY, user_id BIGINT NOT NULL, user_version BIGINT NOT NULL, csrf VARCHAR(80) NOT NULL, expires_at TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS t_sys_audit (id VARCHAR(36) PRIMARY KEY, actor VARCHAR(64) NOT NULL, method VARCHAR(160) NOT NULL, succeeded BOOLEAN NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS t_sys_role_permission_backup (id VARCHAR(36) PRIMARY KEY, role_code VARCHAR(50) NOT NULL, role_name VARCHAR(80) NOT NULL, permissions_json TEXT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        for (var role : List.of(Map.of("code","admin","name","管理员","permissions",List.of("*")),
                Map.of("code","modeler","name","建模人员","permissions",List.of("data:read","meta:inspect","meta:reload")),
                Map.of("code","operator","name","业务人员","permissions",List.of("data:read","data:write","service:invoke")),
                Map.of("code","viewer","name","只读人员","permissions",List.of("data:read")))) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM t_sys_role WHERE code=?",Integer.class,role.get("code"))==0)
                jdbc.update("INSERT INTO t_sys_role(code,name,permissions_json) VALUES(?,?,?)",role.get("code"),role.get("name"),json(role.get("code").equals("admin")?role.get("permissions"):applications.migrate(engine.snapshot(),(List<String>)role.get("permissions"))));
        }
        migrateLegacyRoles();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM t_sys_user",Integer.class)==0) {
            String password=initialPassword.isBlank()?"Mdd!"+token().substring(0,20):initialPassword;
            checkPassword(password);
            if (initialPassword.isBlank()) {
                Files.createDirectories(credentialFile.getParent());
                Files.writeString(credentialFile,"username=admin\npassword="+password+"\n首次登录后必须修改密码。\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
            }
            jdbc.update("INSERT INTO t_sys_user(username,display_name,password_hash,roles_json) VALUES(?,?,?,?)","admin","平台管理员",hashPassword(password),json(List.of("admin")));
        }
        jdbc.update("DELETE FROM t_sys_session WHERE expires_at < CURRENT_TIMESTAMP");
    }
    private void migrateUserDeletionColumns() {
        Set<String> columns=jdbc.execute((ConnectionCallback<Set<String>>) connection->{
            Set<String> found=new HashSet<>();
            try(var result=connection.getMetaData().getColumns(connection.getCatalog(),connection.getSchema(),"%",null)) {
                while(result.next())if("t_sys_user".equalsIgnoreCase(result.getString("TABLE_NAME")))found.add(result.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
            return found;
        });
        // Metadata checks keep this additive migration compatible with both H2 and MySQL.
        for(var column:List.of(Map.entry("deleted","BOOLEAN NOT NULL DEFAULT FALSE"),Map.entry("deleted_at","TIMESTAMP NULL"),Map.entry("deleted_by","VARCHAR(64) NULL")))
            if(!columns.contains(column.getKey()))jdbc.execute("ALTER TABLE t_sys_user ADD COLUMN "+column.getKey()+" "+column.getValue());
    }
    public record Login(String token, Map<String,Object> user) { }
    public Login login(String username,String password,String address) {
        String normalized=username==null?"":username.trim().toLowerCase(Locale.ROOT);
        if (normalized.length()>50 || password==null || password.length()>128) throw fail(401,"账号或密码错误");
        String key=address+":"+normalized;
        Attempts state=attempts.get(key);
        if(state!=null&&state.failures>=8&&state.until.isAfter(Instant.now()))throw fail(429,"尝试次数过多，请在15分钟后重试");
        var rows=jdbc.queryForList("SELECT * FROM t_sys_user WHERE username=? AND deleted=FALSE",normalized);
        Map<String,Object> row=rows.isEmpty()?null:rows.get(0);
        // Run a password derivation even for unknown accounts to reduce account enumeration signals.
        boolean valid=verify(password,row==null?dummyHash():row.get("password_hash").toString());
        if(row==null||!Boolean.TRUE.equals(row.get("enabled"))||!valid) {
            if(attempts.size()>10000)attempts.entrySet().removeIf(e->e.getValue().until.isBefore(Instant.now()));
            attempts.compute(key,(k,old)->new Attempts(old==null||old.until.isBefore(Instant.now())?1:old.failures+1,Instant.now().plusSeconds(900)));
            throw fail(401,"账号或密码错误");
        }
        attempts.remove(key);
        return tx.execute(status->{
            // Recheck after password verification and serialize session creation with account deletion.
            var current=jdbc.queryForList("SELECT * FROM t_sys_user WHERE id=? AND version=? AND enabled=TRUE AND deleted=FALSE FOR UPDATE",row.get("id"),row.get("version"));
            if(current.isEmpty())throw fail(401,"账号或密码错误");
            String token=token(),csrf=token();
            jdbc.update("INSERT INTO t_sys_session(token_hash,user_id,user_version,csrf,expires_at) VALUES(?,?,?,?,?)",digest(token),row.get("id"),row.get("version"),csrf,Timestamp.from(Instant.now().plusSeconds(8*3600)));
            return new Login(token,describe(identity(current.get(0),csrf,digest(token))));
        });
    }
    public AuthContext.Identity authenticate(String token) {
        if(token==null||token.length()>128)return null;
        var rows=jdbc.queryForList("SELECT u.*,s.csrf,s.token_hash FROM t_sys_session s JOIN t_sys_user u ON u.id=s.user_id AND u.version=s.user_version WHERE s.token_hash=? AND s.expires_at>CURRENT_TIMESTAMP AND u.enabled=TRUE AND u.deleted=FALSE",digest(token));
        return rows.isEmpty()?null:identity(rows.get(0),rows.get(0).get("csrf").toString(),digest(token));
    }
    private AuthContext.Identity identity(Map<String,Object> row,String csrf,String tokenHash) {
        List<String> roles=strings(row.get("roles_json").toString());Set<String> permissions=new HashSet<>();
        for(String role:roles)jdbc.queryForList("SELECT permissions_json FROM t_sys_role WHERE code=?",role).forEach(r->permissions.addAll(role.equals("admin")?Set.of("*"):applications.configuredPermissions(strings(r.get("permissions_json").toString()))));
        return new AuthContext.Identity(((Number)row.get("id")).longValue(),row.get("username").toString(),row.get("display_name").toString(),roles,Set.copyOf(permissions),Boolean.TRUE.equals(row.get("must_change")),csrf,tokenHash);
    }
    public Map<String,Object> describe(AuthContext.Identity user) {return Map.of("id",user.id(),"username",user.username(),"name",user.name(),"roles",user.roles(),"permissions",user.permissions(),"effectivePermissions",applications.effectivePermissions(engine.snapshot(),user.permissions()),"mustChangePassword",user.mustChangePassword(),"csrfToken",user.csrf());}
    public AuthContext.Identity require() {if(AuthContext.current()==null)throw fail(401,"请先登录");return AuthContext.current();}
    public void permission(String value) {if(!require().allows(value))throw fail(403,"没有执行此操作的权限");}
    public void logout() {jdbc.update("DELETE FROM t_sys_session WHERE token_hash=?",require().tokenHash());}
    public void audit(String method,boolean succeeded) {try{jdbc.update("INSERT INTO t_sys_audit(id,actor,method,succeeded) VALUES(?,?,?,?)",UUID.randomUUID().toString(),AuthContext.actor(),method.substring(0,Math.min(160,method.length())),succeeded);}catch(Exception ex){org.slf4j.LoggerFactory.getLogger(AuthService.class).warn("操作审计写入失败：{}",method);}}
    public List<Map<String,Object>> audits(){permission("users:manage");return jdbc.queryForList("SELECT actor,method,succeeded,created_at FROM t_sys_audit ORDER BY created_at DESC LIMIT 200");}
    public void changePassword(String oldPassword,String newPassword) {
        var user=require();checkPassword(newPassword);
        String stored=jdbc.queryForObject("SELECT password_hash FROM t_sys_user WHERE id=?",String.class,user.id());
        if(!verify(oldPassword,stored))throw fail(400,"原密码不正确");
        if(verify(newPassword,stored))throw fail(400,"新密码不能与原密码相同");
        tx.executeWithoutResult(status->{if(jdbc.update("UPDATE t_sys_user SET password_hash=?,must_change=FALSE,version=version+1 WHERE id=? AND enabled=TRUE AND deleted=FALSE",hashPassword(newPassword),user.id())!=1)throw fail(401,"账号已失效，请重新登录");jdbc.update("DELETE FROM t_sys_session WHERE user_id=?",user.id());});
    }
    public List<Map<String,Object>> users() {permission("users:manage");return jdbc.queryForList("SELECT id,username,display_name,roles_json,enabled,must_change,version,created_at FROM t_sys_user WHERE deleted=FALSE ORDER BY id");}
    public List<Map<String,Object>> roles() {permission("users:manage");return jdbc.queryForList("SELECT code,name,permissions_json FROM t_sys_role ORDER BY code");}
    public Object applicationCatalog(){permission("users:manage");var snapshot=engine.snapshot();return Map.of("version",snapshot.version(),"menus",snapshot.menus(),"applications",applications.applications(snapshot).values());}
    public synchronized Object saveUser(JsonNode p) {
        permission("users:manage");
        return tx.execute(status->saveUserInTransaction(p));
    }
    private Object saveUserInTransaction(JsonNode p) {
        String name=p.path("name").asText().trim();
        if(name.isBlank()||name.length()>80)throw fail(400,"显示名称必填且不超过80字符");
        List<String> roles=parseRoles(p.path("roles")); boolean enabled=p.path("enabled").asBoolean(true);
        if(roles.contains("admin")&&!enabled)throw fail(400,"管理员账号必须保持启用");
        if(!p.has("id")) {
            String username=p.path("username").asText().trim().toLowerCase(Locale.ROOT);
            if(!username.matches("[a-z][a-z0-9_]{2,49}"))throw fail(400,"用户名须为3~50位小写字母、数字、下划线，并以字母开头");
            String password=p.path("password").asText();checkPassword(password);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM t_sys_user WHERE username=?",Integer.class,username)>0)throw fail(400,"用户名已存在");
            jdbc.update("INSERT INTO t_sys_user(username,display_name,password_hash,roles_json,enabled) VALUES(?,?,?,?,?)",username,name,hashPassword(password),json(roles),enabled);
        }else{
            long id=p.path("id").asLong(-1),version=p.path("version").asLong(-1);
            var rows=jdbc.queryForList("SELECT * FROM t_sys_user WHERE id=? AND deleted=FALSE FOR UPDATE",id);if(rows.isEmpty())throw fail(404,"用户不存在或已删除");
            protectAdministrator(rows.get(0));
            if(id==require().id()&&(!enabled||!roles.contains("admin")))throw fail(400,"不能禁用自己或移除自己的管理员角色");
            tx.executeWithoutResult(status->{
                if(jdbc.update("UPDATE t_sys_user SET display_name=?,roles_json=?,enabled=?,version=version+1 WHERE id=? AND version=? AND deleted=FALSE",name,json(roles),enabled,id,version)!=1)throw fail(409,"账号已变化，请刷新后重试");
                jdbc.update("DELETE FROM t_sys_session WHERE user_id=?",id);
            });
        }audit("users.save",true);return Map.of("saved",true);
    }
    public synchronized void resetPassword(long id,String password) {
        permission("users:manage");checkPassword(password);
        tx.executeWithoutResult(status->{
            var rows=jdbc.queryForList("SELECT username,roles_json FROM t_sys_user WHERE id=? AND deleted=FALSE FOR UPDATE",id);if(rows.isEmpty())throw fail(404,"用户不存在或已删除");protectAdministrator(rows.get(0));
            if(id==require().id())throw fail(400,"请使用修改密码功能修改自己的密码");
            if(jdbc.update("UPDATE t_sys_user SET password_hash=?,must_change=TRUE,version=version+1 WHERE id=? AND deleted=FALSE",hashPassword(password),id)!=1)throw fail(404,"用户不存在或已删除");
            jdbc.update("DELETE FROM t_sys_session WHERE user_id=?",id);
        });
        audit("users.resetPassword",true);
    }
    public synchronized void deleteUser(long id,long version) {
        permission("users:manage");
        if(id<=0||version<0)throw fail(400,"用户编号或版本无效");
        if(id==require().id())throw fail(400,"不能删除当前登录账号");
        tx.executeWithoutResult(status->{
            var rows=jdbc.queryForList("SELECT username,roles_json,version FROM t_sys_user WHERE id=? AND deleted=FALSE FOR UPDATE",id);
            if(rows.isEmpty())throw fail(404,"用户不存在或已删除");
            protectAdministrator(rows.get(0));
            if(((Number)rows.get(0).get("version")).longValue()!=version)throw fail(409,"账号已变化，请刷新后重试");
            if(jdbc.update("UPDATE t_sys_user SET deleted=TRUE,enabled=FALSE,deleted_at=CURRENT_TIMESTAMP,deleted_by=?,version=version+1 WHERE id=? AND version=? AND deleted=FALSE",require().username(),id,version)!=1)throw fail(409,"账号已变化，请刷新后重试");
            jdbc.update("DELETE FROM t_sys_session WHERE user_id=?",id);
            // Audit failure rolls back the deletion, so the account change is always traceable.
            jdbc.update("INSERT INTO t_sys_audit(id,actor,method,succeeded) VALUES(?,?,?,?)",UUID.randomUUID().toString(),require().username(),"users.delete."+id,true);
        });
    }
    public synchronized void saveRole(JsonNode p) {
        permission("users:manage");String code=p.path("code").asText(),name=p.path("name").asText().trim();
        if(!code.matches("[a-z][a-z0-9_]{1,49}")||name.isBlank()||name.length()>80)throw fail(400,"角色编码或名称无效");
        if(code.equals("admin"))throw fail(400,"内置管理员角色不可修改");
        if(!p.path("permissions").isArray()||p.get("permissions").size()>200)throw fail(400,"权限必须为数组且不超过200项");
        List<String> permissions=new ArrayList<>();for(JsonNode n:p.get("permissions")){String v=n.asText();if(!n.isTextual()||!v.matches("[a-zA-Z][a-zA-Z0-9_]*:[a-zA-Z*][a-zA-Z0-9_*]*"))throw fail(400,"权限格式为资源:动作");permissions.add(v);}
        applications.validate(engine.snapshot(),permissions);
        if(jdbc.update("UPDATE t_sys_role SET name=?,permissions_json=? WHERE code=?",name,json(permissions),code)==0)jdbc.update("INSERT INTO t_sys_role(code,name,permissions_json) VALUES(?,?,?)",code,name,json(permissions));
        audit("roles.save",true);
    }
    public synchronized void deleteRole(String code){
        permission("users:manage");
        if(code==null||!code.matches("[a-z][a-z0-9_]{1,49}"))throw fail(400,"角色编码无效");
        if(BUILTIN_ROLES.contains(code))throw fail(403,"内置角色不可删除");
        tx.executeWithoutResult(status->{
            var rows=jdbc.queryForList("SELECT code,name,permissions_json FROM t_sys_role WHERE code=? FOR UPDATE",code);
            if(rows.isEmpty())throw fail(404,"角色不存在或已删除");
            long assigned=jdbc.queryForList("SELECT roles_json FROM t_sys_user WHERE deleted=FALSE").stream().filter(user->strings(user.get("roles_json").toString()).contains(code)).count();
            if(assigned>0)throw fail(409,"该角色已分配给"+assigned+"个用户，请先解除分配");
            var role=rows.get(0);
            jdbc.update("INSERT INTO t_sys_role_permission_backup(id,role_code,role_name,permissions_json) VALUES(?,?,?,?)",UUID.randomUUID().toString(),code,role.get("name"),role.get("permissions_json"));
            if(jdbc.update("DELETE FROM t_sys_role WHERE code=?",code)!=1)throw fail(409,"角色已变化，请刷新后重试");
            audit("roles.delete."+code,true);
        });
    }
    private void protectAdministrator(Map<String,Object> row){if("admin".equals(row.get("username"))||strings(row.get("roles_json").toString()).contains("admin"))throw fail(403,"管理员账号不允许编辑、重置密码或删除；本人可使用修改密码功能");}
    public synchronized void migrateLegacyRoles(){
        tx.executeWithoutResult(status->{
            for(var role:jdbc.queryForList("SELECT code,name,permissions_json FROM t_sys_role WHERE code<>'admin'")){
                List<String> old=strings(role.get("permissions_json").toString()),next=applications.migrate(engine.snapshot(),old);
                if(new HashSet<>(old).equals(new HashSet<>(next)))continue;
                jdbc.update("INSERT INTO t_sys_role_permission_backup(id,role_code,role_name,permissions_json) VALUES(?,?,?,?)",UUID.randomUUID().toString(),role.get("code"),role.get("name"),role.get("permissions_json"));
                jdbc.update("UPDATE t_sys_role SET permissions_json=? WHERE code=?",json(next),role.get("code"));
            }
        });
    }
    private List<String> parseRoles(JsonNode value) {
        if(!value.isArray()||value.isEmpty()||value.size()>20)throw fail(400,"请选择1~20个角色");
        Set<String> roles=new LinkedHashSet<>();for(JsonNode n:value){if(!n.isTextual())throw fail(400,"角色不存在");roles.add(n.asText());}
        // Lock in stable order until the user write commits; deletion locks the same rows before checking usage.
        for(String code:new TreeSet<>(roles))if(jdbc.queryForList("SELECT code FROM t_sys_role WHERE code=? FOR UPDATE",code).isEmpty())throw fail(400,"角色不存在");
        return List.copyOf(roles);
    }
    private List<String> strings(String json) {try{return mapper.readerForListOf(String.class).readValue(json);}catch(Exception ex){throw new IllegalStateException(ex);}}
    private String json(Object value) {try{return mapper.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
    private String token() {byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static final String DUMMY="pbkdf2$210000$AAAAAAAAAAAAAAAAAAAAAA==$"+Base64.getEncoder().encodeToString(new byte[32]);
    private String dummyHash(){return DUMMY;}
    private void checkPassword(String value) {if(value==null||value.length()<6||value.length()>128||!value.matches("(?s).*[A-Za-z].*")||!value.matches("(?s).*[^A-Za-z].*"))throw fail(400,"密码须为6~128位，且包含字母和数字或符号");}
    private String hashPassword(String password) {byte[] salt=new byte[16];random.nextBytes(salt);return "pbkdf2$210000$"+Base64.getEncoder().encodeToString(salt)+"$"+Base64.getEncoder().encodeToString(derive(password,salt,210000));}
    private boolean verify(String password,String encoded) {try{String[] parts=encoded.split("\\$");return password!=null&&password.length()<=128&&MessageDigest.isEqual(Base64.getDecoder().decode(parts[3]),derive(password,Base64.getDecoder().decode(parts[2]),Integer.parseInt(parts[1])));}catch(Exception ex){return false;}}
    private byte[] derive(String password,byte[] salt,int rounds) {PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt,rounds,256);try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}catch(Exception ex){throw new IllegalStateException(ex);}finally{spec.clearPassword();}}
    private String digest(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
    static ResponseStatusException fail(int status,String message) {return new ResponseStatusException(HttpStatus.valueOf(status),message);}
}
