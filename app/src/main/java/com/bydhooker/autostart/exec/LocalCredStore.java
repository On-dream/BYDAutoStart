package com.bydhooker.autostart.exec;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.Charset;
import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 各被注入进程自己的凭据库（架构 C，`docs/34` §5 N2）—— **零项目依赖**。
 *
 * 【与 `core/CredentialVault` 的关系】同一套文件格式与派生方式，但去掉了三处不可用依赖：
 * · `BydApp.appCtx.packageName` → 硬编码模块包名（seed 必须逐字一致，否则解不开）
 * · `EventLog` → {@link ExecLog}
 * · `Credential` 项目数据类 → 内部静态类 {@link Cred}
 *
 * 【文件格式】（`CredentialVault.HDR_CBC`，0x03）
 * ```
 * [0]     0x03
 * [1..16] iv (16B)
 * [17..]  ciphertext
 * [-16..] HMAC-SHA256(iv || ct) 前 16 字节
 * ```
 * 密钥 = PBKDF2WithHmacSHA256(seed="bydhooker-cred-v1|<模块包名>", salt=cred.salt, 20000 轮, 256 bit)
 *
 * 【⚠️ 三份密文互不通用】每个进程的 salt 文件独立。这里读的是模块侧那一份的副本，
 * 由 root 复制到 `/data/system/bydhk/`（模块 App 有 root，system_server 没有）。
 * 各进程独立目录、独立 salt 的完整方案在第二/三期落地。
 */
public final class LocalCredStore {

    /** 与模块侧 `CredentialVault.softwareKey()` 的 seed 逐字一致，改一个字就解不开。 */
    private static final String SEED_PREFIX = "bydhooker-cred-v1|";

    private static final String PKG = "com.bydhooker.autostart";

    private static final String FILE_NAME = "cred.bin";
    private static final String SALT_NAME = "cred.salt";

    private static final byte HDR_CBC = 3;

    private static final int PBKDF2_ROUNDS = 20000;

    private static final int IV_LEN = 16;
    private static final int TAG_LEN = 16;

    private LocalCredStore() {
    }

    /**
     * 从指定目录读并解密 `cred.bin`。
     *
     * @param dir 目录（system 侧 `/data/system/bydhk`）
     * @return null = 文件不存在 / 解密失败 / 字段不全（调用方据此放弃本次执行）
     */
    public static com.bydhooker.autostart.model.Credential load(File dir) {
        if (dir == null) {
            return null;
        }
        File f = new File(dir, FILE_NAME);
        if (!f.exists() || f.length() < 1 + IV_LEN + TAG_LEN + 1) {
            ExecLog.warn("CRED_MISS", "cred.bin 不存在或过短：" + f.getAbsolutePath());
            return null;
        }
        try {
            byte[] blob = readAll(f);
            if (blob[0] != HDR_CBC) {
                ExecLog.error("CRED_ERR", "文件头不是 0x03（head=" + (blob[0] & 0xFF)
                        + "），本进程只支持 CBC 格式");
                return null;
            }
            byte[] key = deriveKey(dir);
            if (key == null) {
                return null;
            }
            byte[] iv = slice(blob, 1, 1 + IV_LEN);
            byte[] ct = slice(blob, 1 + IV_LEN, blob.length - TAG_LEN);
            byte[] tag = slice(blob, blob.length - TAG_LEN, blob.length);

            // 先验 HMAC 再解密：顺序不能反，否则会拿篡改数据去解密
            byte[] expect = hmac(key, iv, ct);
            if (!MessageDigest.isEqual(expect, tag)) {
                ExecLog.error("CRED_ERR", "HMAC 校验不通过（salt 不匹配或数据损坏）");
                return null;
            }
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            String json = new String(c.doFinal(ct), Charset.forName("UTF-8"));

            com.bydhooker.autostart.model.Credential cred = parse(json);
            if (cred == null || !complete(cred)) {
                ExecLog.error("CRED_ERR", "凭据解析失败或字段不全");
                return null;
            }
            ExecLog.info("CRED_OK", "凭据已载入|vin=" + mask(cred.getVin()));
            return cred;
        } catch (Throwable t) {
            ExecLog.error("CRED_ERR", t.getClass().getSimpleName() + "|" + t.getMessage());
            return null;
        }
    }

    /** 关键字段齐不齐全（缺一个都不能发指令）。 */
    private static boolean complete(com.bydhooker.autostart.model.Credential c) {
        return notEmpty(c.getWatchImei()) && notEmpty(c.getSignToken())
                && notEmpty(c.getEncryToken()) && notEmpty(c.getPassWord())
                && notEmpty(c.getVin());
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    /** PBKDF2 派生（与模块侧 `softwareKey()` 同参数）。 */
    private static byte[] deriveKey(File dir) throws Exception {
        File saltFile = new File(dir, SALT_NAME);
        if (!saltFile.exists() || saltFile.length() < 16) {
            ExecLog.error("CRED_ERR", "cred.salt 不存在：" + saltFile.getAbsolutePath());
            return null;
        }
        byte[] salt = readAll(saltFile);
        String seed = SEED_PREFIX + PKG;
        PBEKeySpec spec = new PBEKeySpec(seed.toCharArray(), salt, PBKDF2_ROUNDS, 256);
        SecretKeyFactory f;
        try {
            f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256", "Conscrypt");
        } catch (Throwable t) {
            f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        }
        SecretKey k = f.generateSecret(spec);
        return k.getEncoded();
    }

    private static byte[] hmac(byte[] key, byte[] iv, byte[] ct) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update(iv);
        mac.update(ct);
        byte[] full = mac.doFinal();
        return slice(full, 0, TAG_LEN);
    }

    private static com.bydhooker.autostart.model.Credential parse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            return new com.bydhooker.autostart.model.Credential(
                    o.optString("watchImei", ""),
                    o.optString("signToken", ""),
                    o.optString("encryToken", ""),
                    o.optString("passWord", ""),
                    o.optString("vin", ""),
                    o.optString("identifier", ""),
                    o.optString("identifierType", ""),
                    o.optString("vehicleBrand", ""),
                    o.optString("appChannel", ""),
                    o.optString("autoType", ""),
                    o.optString("tboxVersion", ""),
                    o.optLong("acquireTokenTimestamp", 0L));
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] readAll(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) {
                    break;
                }
                off += n;
            }
            return buf;
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static byte[] slice(byte[] src, int from, int to) {
        int len = Math.max(0, to - from);
        byte[] out = new byte[len];
        System.arraycopy(src, from, out, 0, len);
        return out;
    }

    private static String mask(String s) {
        if (s == null || s.length() <= 6) {
            return "***";
        }
        return s.substring(0, 3) + "***" + s.substring(s.length() - 3);
    }
}
