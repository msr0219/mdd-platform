package com.platform.auth;

import java.util.*;

/** Request identity is set only by the session filter, never by a client-supplied actor field. */
public final class AuthContext {
    public record Identity(long id, String username, String name, List<String> roles, Set<String> permissions,
                           boolean mustChangePassword, String csrf, String tokenHash) {
        public boolean allows(String permission) {
            if (permission == null || permission.isBlank()) return true;
            if (permissions.contains("*") || permissions.contains(permission)) return true;
            int colon = permission.indexOf(':');
            return colon > 0 && permissions.contains(permission.substring(0, colon) + ":*");
        }
    }
    private static final ThreadLocal<Identity> CURRENT = new ThreadLocal<>();
    private AuthContext() { }
    public static Identity current() { return CURRENT.get(); }
    public static void set(Identity identity) { CURRENT.set(identity); }
    public static void clear() { CURRENT.remove(); }
    public static String actor() { return current() == null ? "system" : current().username(); }
}
