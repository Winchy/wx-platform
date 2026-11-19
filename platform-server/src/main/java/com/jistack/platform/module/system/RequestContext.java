package com.jistack.platform.module.system;

/** 当前登录用户，由 AuthInterceptor 写入、请求结束时清理。 */
public final class RequestContext {

    public record Principal(Long id, String username, String role) {
    }

    private static final ThreadLocal<Principal> HOLDER = new ThreadLocal<>();

    private RequestContext() {
    }

    public static void set(Principal principal) {
        HOLDER.set(principal);
    }

    public static Principal get() {
        return HOLDER.get();
    }

    public static Principal require() {
        Principal principal = HOLDER.get();
        if (principal == null) {
            throw com.jistack.platform.common.web.BizException.unauthorized("未登录或登录已过期");
        }
        return principal;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
