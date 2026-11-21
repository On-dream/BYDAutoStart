package com.bydhooker.autostart.island;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 超级岛 {@code miui.focus.param} JSON 构造（纯 JVM，零第三方依赖）。
 *
 * 【动画】全部复用系统内置 Lottie 资源名（本机反编译 LottieResUtils.smali
 * 实测存在），不用自画 Bitmap、不引 Lottie 库、不受 100KB/HTTPS 图片限制：
 *   S0 hourglass（循环） / S1 car_loading（循环）
 *   S2 face_recognition_success（带动画的勾，只播一次）
 *   S3 face_recognition_failed
 *
 * 【存活时长】
 *   S0 islandTimeout = windowSeconds（随设置页可调）
 *   S1 islandTimeout = 90 s —— 只作事件丢失的 watchdog，正常路径由
 *      VERIFY_OK / VERIFY_PENDING 事件立即 cancel（用户拍板「严格按复核时长走」）
 *   S2 / S3 15 s，且由 SystemUI 侧 Handler 定时 cancel 兜底
 *   param_v2.timeout 单位【分钟】（官方默认 720 必须显式改小）
 */
public final class IslandPayload {

    public static final int NOTIF_ID = 4242;
    public static final String CHANNEL_ID = "bydhk_island";

    private static final String ANIM_WAIT = "hourglass";
    private static final String ANIM_SENDING = "car_loading";
    // 用 `_small` 版：非 small 的 success/failed 画布是 456×456，图形只占画布一部分，
    // 系统按画布等比缩放 ⇒ 真机上勾/叉只有约 10dp，显得极小。_small 画布 104×104，
    // 与 hourglass(66)/car_loading(42) 同一量级，图形能填满图标框。
    private static final String ANIM_OK = "face_recognition_success_small";
    private static final String ANIM_FAIL = "face_recognition_failed_small";

    /** S1 watchdog：复核上限 60 s + 30 s 余量（docs/37 §8.10）。 */
    private static final int S1_WATCHDOG_SEC = 90;

    private IslandPayload() {
    }

    /**
     * @param type          IslandEvent.TYPE_*
     * @param ts            事件时间（ms）
     * @param deadline      窗口截止（ms，仅 S0 用）
     * @param elapsed       VERIFY_OK 的下发→确认耗时（ms）
     * @param windowSeconds 当前开窗时长（秒，S0 用）
     */
    public static String build(String type, long ts, long deadline, long elapsed,
                               int windowSeconds) throws JSONException {
        boolean s0 = IslandEvent.TYPE_OPEN.equals(type) || IslandEvent.TYPE_REFRESH.equals(type);
        boolean s1 = IslandEvent.TYPE_SEND.equals(type);
        boolean s2 = IslandEvent.TYPE_VERIFY_OK.equals(type);

        String anim;
        boolean loop;
        String title;
        String content;
        String ticker;
        int islandTimeout;

        if (s0) {
            anim = ANIM_WAIT;
            loop = true;
            title = "等待开门上电";
            content = "打开主驾门即自动上电";
            ticker = "等待开门";
            islandTimeout = windowSeconds > 0 ? windowSeconds : 60;
        } else if (s1) {
            anim = ANIM_SENDING;
            loop = true;
            title = "正在上电";
            content = "启动指令已下发，等待车况确认";
            ticker = "正在上电";
            islandTimeout = S1_WATCHDOG_SEC;
        } else if (s2) {
            anim = ANIM_OK;
            loop = false;
            title = "车辆已上电";
            content = "复核通过（下发后 " + elapsed + " ms）";
            ticker = "车辆已上电";
            islandTimeout = 15;
        } else {
            anim = ANIM_FAIL;
            loop = false;
            title = "上电未确认";
            content = "指令未被受理或车况未确认上电";
            ticker = "上电未确认";
            islandTimeout = 15;
        }

        JSONObject v2 = new JSONObject();
        v2.put("protocol", 3);
        v2.put("business", "byd_autostart");
        v2.put("updatable", true);
        // 用户拍板：S2 不弹展开态；全过程都不自动展开，避免打扰
        v2.put("islandFirstFloat", false);
        v2.put("enableFloat", false);
        v2.put("timeout", 2);
        v2.put("ticker", ticker);
        v2.put("aodTitle", ticker);
        v2.put("filterWhenNoPermission", false);

        JSONObject base = new JSONObject();
        base.put("type", 1);
        base.put("title", title);
        base.put("content", content);
        v2.put("baseInfo", base);

        JSONObject island = new JSONObject();
        island.put("islandProperty", 1);
        island.put("islandTimeout", islandTimeout);

        JSONObject left = new JSONObject();
        left.put("type", 1);
        left.put("picInfo", pic(anim, loop));

        JSONObject big = new JSONObject();
        // 胶囊宽度是固定的（真机实测：内容再少也不收缩），所以右侧必须始终有内容才平衡。
        // 布局与 S0 同构：左＝图标（+S0 的标题），右＝计时器（S0）/ 状态文字（S1~S3）。
        if (s0) {
            JSONObject leftText = new JSONObject();
            leftText.put("title", "等待开门");
            leftText.put("showHighlightColor", true);
            left.put("textInfo", leftText);
        }
        big.put("imageTextInfoLeft", left);
        if (s0 && deadline > ts) {
            JSONObject timer = new JSONObject();
            timer.put("timerType", -1);
            timer.put("timerSystemCurrent", ts);
            timer.put("timerWhen", deadline);
            JSONObject digit = new JSONObject();
            // `content` 是数字后面的单位文案，留空则只显示系统计时器本身（00:50）。
            // 反编译 IslandSameWidthDigitViewHolder.smali:723-737 / 1550-1580 证实该字段
            // 为 null 时走「无内容」分支（宽度 0、不加间隔），不会崩也不会留空隙。
            // 用户 2026-11-13 23:4x 实机看过后拍板：去掉「秒」。
            digit.put("timerInfo", timer);
            big.put("sameWidthDigitInfo", digit);
        } else {
            // 含「S0 但没拿到 deadline」的兜底，保证右侧永不空白
            JSONObject rightText = new JSONObject();
            rightText.put("title", s0 ? "等待开门" : (s1 ? "上电中" : (s2 ? "已上电" : "未确认")));
            rightText.put("showHighlightColor", true);
            big.put("textInfo", rightText);
        }
        island.put("bigIslandArea", big);

        JSONObject small = new JSONObject();
        small.put("picInfo", pic(anim, loop));
        island.put("smallIslandArea", small);
        v2.put("param_island", island);

        JSONObject root = new JSONObject();
        root.put("param_v2", v2);
        return root.toString();
    }

    /** type=2 + 系统动画名：SystemUI 的 getLottieRes() 会解析到 R$raw 的 Lottie JSON。 */
    private static JSONObject pic(String anim, boolean loop) throws JSONException {
        JSONObject p = new JSONObject();
        p.put("type", 2);
        p.put("pic", anim);
        p.put("autoplay", true);
        p.put("loop", loop);
        return p;
    }
}
