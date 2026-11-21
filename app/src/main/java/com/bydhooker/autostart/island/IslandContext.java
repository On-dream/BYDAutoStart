package com.bydhooker.autostart.island;

import android.app.Application;
import android.content.Context;

import java.lang.reflect.Method;

/**
 * 岛事件发射端的 Context 持有者（每个进程一份静态）。
 *
 * 【为什么需要它】{@code CommandSender} / {@code WindowManager} 是纯 JVM 逻辑，
 * 跑在三个不同进程（官方 App / tsmclient / system_server），但 ContentResolver.call
 * 需要 Context。装配期 {@code ActivityThread.currentApplication()} 可能为 null
 * （项目既有坑），所以采用「显式 attach 优先 + 事件发生时懒加载兜底」：
 *
 * · system_server：由 A′ 的 {@code InjectableExec.attachContext(Context)} 显式注入
 *   （system_server 里 currentApplication() 可能为 null）。
 * · 官方 App / tsmclient：事件发生时懒加载反射拿 Application，无需改装配链。
 *
 * ⚠️ {@code ActivityThread.currentApplication()} / {@code AppGlobals.getInitialApplication()}
 * 是 @hide API，在 LSPosed 注入进程里通常可用，但**未在本机实测**；失败时岛事件
 * 静默丢弃（只丢展示，不影响上电），可用 docs/37 §8.11 的验证表核对。
 */
public final class IslandContext {

    private static volatile Context sCtx;

    private IslandContext() {
    }

    /** 显式注入（system_server 的 A′ 路径）。幂等。 */
    public static void attach(Context c) {
        if (c == null) {
            return;
        }
        try {
            Context app = c.getApplicationContext();
            sCtx = app != null ? app : c;
        } catch (Throwable t) {
            sCtx = c;
        }
    }

    /** 取当前进程 Context；取不到返回 null（调用方静默丢弃）。 */
    public static Context get() {
        Context c = sCtx;
        if (c != null) {
            return c;
        }
        c = reflectApp("android.app.ActivityThread", "currentApplication");
        if (c == null) {
            c = reflectApp("android.app.AppGlobals", "getInitialApplication");
        }
        if (c != null) {
            sCtx = c;
        }
        return c;
    }

    private static Context reflectApp(String cls, String method) {
        try {
            Class<?> c = Class.forName(cls);
            Method m = c.getDeclaredMethod(method);
            m.setAccessible(true);
            Object o = m.invoke(null);
            if (o instanceof Application) {
                Context app = ((Application) o).getApplicationContext();
                return app != null ? app : (Context) o;
            }
        } catch (Throwable ignored) {
            // hidden API 被拦 / 尚未就绪：调用方静默降级
        }
        return null;
    }
}
