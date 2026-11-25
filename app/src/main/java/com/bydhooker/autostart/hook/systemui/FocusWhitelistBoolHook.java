package com.bydhooker.autostart.hook.systemui;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedInterface;

/**
 * 焦点通知白名单放行 · **OS3 旧形态兼容**（v3.3.2）。
 *
 * 【为什么需要两个 Hooker】HyperOS4 与 HyperOS3 的判定方法不是同一个：
 *
 * | ROM | 方法 | 形态 | 返回 |
 * |---|---|---|---|
 * | HyperOS4（SDK 37） | `NotificationSettingsManager.canShowFocusState(Context,String)` | 实例、final | `int`（1 放行 / -1 禁用） |
 * | HyperOS3（SDK 36） | `NotificationSettingsManager.canShowFocus(Context,String)` | **static** | `boolean` |
 *
 * 2026-11-25 用 OS3.0.314 的 `MiuiSystemUI.apk` 反编译实证：整套 dex 里
 * **`canShowFocusState` 一次都不存在**，只有 `canShowFocus`。
 * ⇒ 原来只按 `canShowFocusState` 名字找的注册逻辑在 OS3 上必然挂不上。
 *
 * 【是否阻断】OS3 的 `canShowFocus` 读 Prefs，**键不存在时默认 return true** ——
 * 所以它本来不会挡岛。这里补齐只为覆盖「用户在设置里关过焦点通知」的情况，
 * 并让两代 ROM 的行为一致（不依赖默认值）。
 *
 * 【安全性】只对 `com.android.systemui` 放行；其它包名原样返回，普通通知不受影响。
 */
public final class FocusWhitelistBoolHook implements XposedInterface.Hooker {

    public static final FocusWhitelistBoolHook INSTANCE = new FocusWhitelistBoolHook();

    private static final String OWNER_PKG = "com.android.systemui";

    private FocusWhitelistBoolHook() {
    }

    @Override
    public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();          // 先跑原逻辑（等价 after）
        try {
            if (OWNER_PKG.equals(chain.getArg(1))) {
                return Boolean.TRUE;
            }
        } catch (Throwable ignored) {
            // 参数形态变化时不改行为
        }
        return result;
    }
}
