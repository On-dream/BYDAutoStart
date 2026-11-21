package com.bydhooker.autostart.hook;

import com.bydhooker.autostart.core.EventLog;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * `commandType` 取值与零触发 dump（关闭 A3 / Q20，`15` §16 #6）。
 *
 * 链路【推断·待本类首次命中实证】：宿主解锁指令走
 * `ControlImpl.sendRemoteControl(IControl)`，实参实际类型为
 * `ControlInputParams`（实现 IControl，探针 v4/v109【实测】），
 * 其 `commandType` 字段为 **String**（P1 零触发 dump【实测·字段名清单】）。
 *
 * 反射策略：沿对象类 + 父类链查 `commandType` 字段（缓存 Field）；
 * 首次成功时把「实参实际类 + 字段清单」落 EventLog（零触发 dump 闭合 Q20）。
 */
public final class CommandTypeDump {

    private static volatile Field cachedField = null;
    private static final AtomicBoolean dumped = new AtomicBoolean(false);

    private CommandTypeDump() {
    }

    /** 读实参的 commandType；读不到返回 null（不猜）。 */
    public static String readCommandType(Object control) {
        if (control == null) {
            return null;
        }
        Field f = resolveField(control.getClass());
        if (f == null) {
            noteTarget(control);
            maybeDumpClass(control.getClass());
            return null;
        }
        try {
            return (String) f.get(control);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field resolveField(Class<?> cls) {
        Field f = cachedField;
        if (f != null && f.getDeclaringClass().isAssignableFrom(cls)) {
            return f;
        }
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field cand = c.getDeclaredField("commandType");
                cand.setAccessible(true);
                cachedField = cand;
                return cand;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /** 零触发 dump：首次遇到「无 commandType 字段的类型」时落类/字段清单 + String 字段值。
     *  首装实证：实参实际类型 HomeAiCarFragment$S，命令字字段为 functionId:String——
     *  值需第二次命中实证（用户明码授权范围内，仅本地 EventLog）。 */
    private static void maybeDumpClass(Class<?> cls) {
        if (dumped.getAndSet(true)) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder("ControlInputParams dump: ")
                    .append(cls.getName()).append(" fields=[");
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field fl : c.getDeclaredFields()) {
                    if (fl.getType() == java.lang.Thread.class
                            || fl.getName().startsWith("this$")) {
                        continue; // 内类 back-reference 无信号价值
                    }
                    sb.append(fl.getName()).append(':')
                            .append(fl.getType().getSimpleName());
                    if (fl.getType() == String.class) {
                        try {
                            fl.setAccessible(true);
                            Object v = fl.get(null == getTarget() ? null : getTarget());
                            sb.append('=').append(v);
                        } catch (Throwable ve) {
                            sb.append("=<err>");
                        }
                    }
                    sb.append(' ');
                }
            }
            sb.append(']');
            EventLog.INSTANCE.debug("CMD_DUMP", sb.toString());
        } catch (Throwable ignored) {
        }
    }

    private static volatile Object targetRef = null;

    private static Object getTarget() {
        return targetRef;
    }

    /** readCommandType 调用侧把实参暂存，供 dump 读取字段值（仅 dump 用，不持久化）。 */
    public static void noteTarget(Object control) {
        targetRef = control;
    }

    /**
     * 字段值描述（回执探针，2026-11-09）：**只读**对象的 String/基本类型字段，
     * 用于确认 `sendRemoteControl` 是否同步返回 `ControlResultBean`（含 res/msg）。
     * 纯观测，不修改对象、不改变任何调用行为。
     */
    public static String describeFields(Object obj) {
        return describeFields(obj, 1);
    }

    /**
     * @param depth 还能往下钻的层数（1 = 只读本层 + 把 Object 型字段再读一层，
     *              用于 `CloudResult.respondData` 这类承载 res/msg 的嵌套对象）。
     */
    private static String describeFields(Object obj, int depth) {
        if (obj == null) {
            return "null";
        }
        try {
            StringBuilder sb = new StringBuilder("fields=[");
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field fl : c.getDeclaredFields()) {
                    String n = fl.getName();
                    if (n.startsWith("this$") || fl.getType() == java.lang.Thread.class) {
                        continue;
                    }
                    Class<?> t = fl.getType();
                    sb.append(n).append(':').append(t.getSimpleName());
                    try {
                        fl.setAccessible(true);
                        Object v = fl.get(obj);
                        if (t == String.class || t.isPrimitive()
                                || Number.class.isAssignableFrom(t)
                                || Boolean.class == t) {
                            sb.append('=').append(v);
                        } else if (v instanceof CharSequence || v instanceof Number
                                || v instanceof java.util.Map) {
                            String vv = String.valueOf(v);
                            sb.append('=').append(vv.length() > 160 ? vv.substring(0, 160) + "…" : vv);
                        } else if (depth > 0 && v != null && !(v instanceof java.util.Collection)) {
                            // 再钻一层：CloudResult.respondData（Object）→ 内部的 res / successLocaMsg
                            String inner = describeFields(v, depth - 1);
                            sb.append('=').append(inner.length() > 220
                                    ? inner.substring(0, 220) + "…" : inner);
                        }
                    } catch (Throwable ve) {
                        sb.append("=<err>");
                    }
                    sb.append(' ');
                }
            }
            return sb.append(']').toString();
        } catch (Throwable t) {
            return "<err:" + t.getClass().getSimpleName() + ">";
        }
    }

    /** 按字段名读一个 String 值（继承链逐层找）；读不到返回 null。 */
    public static String readStringField(Object obj, String field) {
        if (obj == null) {
            return null;
        }
        try {
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(field);
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    return v == null ? null : String.valueOf(v);
                } catch (NoSuchFieldException ignored) {
                    // 继续向上找
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

}
