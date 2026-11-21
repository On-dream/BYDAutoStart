package com.bydhooker.autostart.hook.systemui;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedInterface;

/**
 * 自实现的焦点通知白名单放行（docs/37 §8.11）。
 *
 * Hook 目标（本机 MiuiSystemUI.apk/classes3.dex 反编译实测）：
 *   com.miui.systemui.notification.NotificationSettingsManager
 *     public final canShowFocusState(Context,String)I      (smali3:1358)
 *     public final canShowFocusStateApp(Context,String)I   (smali3:1451)
 * args[1] = 通知归属包名；返回 1 = 允许、-1 = 禁用。
 *
 * 只对 {@code com.android.systemui}（乙-B 由 SystemUI 自己 notify 的归属包名）放行，
 * 其它包名原样返回。岛的渲染还要求 extras 含 miui.focus.param，
 * 因此 SystemUI 的其它普通通知不会因此变成岛。
 */
public final class FocusWhitelistHook implements XposedInterface.Hooker {

    public static final FocusWhitelistHook INSTANCE = new FocusWhitelistHook();

    private static final String OWNER_PKG = "com.android.systemui";

    private FocusWhitelistHook() {
    }

    @Override
    public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();          // 先跑原逻辑（等价 after）
        try {
            if (OWNER_PKG.equals(chain.getArg(1))) {
                return Integer.valueOf(1);
            }
        } catch (Throwable ignored) {
            // 参数形态变化时不改行为
        }
        return result;
    }
}
