package com.bydhooker.autostart.hook.systemui;

import android.content.ContentProvider;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;

import androidx.annotation.NonNull;

import com.bydhooker.autostart.island.IslandController;
import com.bydhooker.autostart.island.IslandEvent;

import io.github.libxposed.api.XposedInterface;

/**
 * SystemUI 侧事件总线（docs/37 §3）：Hook
 * {@code com.android.systemui.statusbar.notification.NotificationProviderPublic.call}
 * 的自定义 method {@code bydhk_island}，前置短路处理并直接返回。
 *
 * 【为什么能绕开 uid 校验】该 Provider 的 call 在 method 分发之前会读
 * extras["package"] 并比对 Binder.getCallingUid()，不匹配抛 SecurityException
 * （smali4:74-107,158-177）。本 Hook 在自定义 method 上直接 return，
 * 原校验根本不会执行；其余 method 一律 chain.proceed() 原样放行。
 *
 * 【调用方白名单】只放行 system_server(1000)、root(0)、官方 App、tsmclient，
 * 避免任意应用伪造岛事件。用 getPackagesForUid 而不是 getApplicationInfo，
 * 不受模块 Manifest <queries> 包可见性限制。
 */
public final class IslandBusHook implements XposedInterface.Hooker {

    public static final IslandBusHook INSTANCE = new IslandBusHook();

    private static final String[] ALLOWED_PKGS = {
            "com.byd.aeri.caranywhere",
            "com.miui.tsmclient",
    };

    private IslandBusHook() {
    }

    @Override
    public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
        Object method = null;
        try {
            method = chain.getArg(0);
        } catch (Throwable ignored) {
        }
        if (!IslandEvent.METHOD.equals(method)) {
            return chain.proceed();               // 非自定义 method：原样，且不重复 proceed
        }
        try {
            int uid = Binder.getCallingUid();
            Object thiz = chain.getThisObject();
            if (!(thiz instanceof ContentProvider)) {
                return empty(false);
            }
            Context ctx = ((ContentProvider) thiz).getContext();
            if (!allowed(ctx, uid)) {
                android.util.Log.w("BYDHK", "ISLAND_DENY|uid=" + uid);
                return empty(false);
            }
            Bundle extras = (Bundle) chain.getArg(2);
            if (extras == null) {
                return empty(false);
            }
            IslandController.onEvent(ctx, extras);
            android.util.Log.i("BYDHK", "ISLAND_RECV|type=" + extras.getString(IslandEvent.K_TYPE)
                    + "|seq=" + extras.getLong(IslandEvent.K_SEQ, -1L)
                    + "|accepted=" + IslandController.lastAccepted);
            return empty(true);
        } catch (Throwable t) {
            // 展示链异常一律吞掉，绝不把异常抛回 SystemUI 的 Provider
            return empty(false);
        }
    }

    private static Bundle empty(boolean ok) {
        Bundle out = new Bundle();
        out.putBoolean("ok", ok);
        return out;
    }

    private static boolean allowed(Context ctx, int uid) {
        if (uid == 1000 || uid == 0) {
            return true;                          // system_server / root
        }
        try {
            PackageManager pm = ctx.getPackageManager();
            String[] pkgs = pm.getPackagesForUid(uid);
            if (pkgs == null) {
                return false;
            }
            for (String p : pkgs) {
                for (String allow : ALLOWED_PKGS) {
                    if (allow.equals(p)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
