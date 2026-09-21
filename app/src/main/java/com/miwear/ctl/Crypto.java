package com.miwear.ctl;

import org.bouncycastle.crypto.modes.CCMBlockCipher;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/** REDMI Watch 5 认证/加密原语（逆向自 com.xiaomi.wearable: itn / ffc / sid / CRCUtil） */
public final class Crypto {
    private Crypto() {}

    /** HMAC-SHA256 (ffc.a) */
    public static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    /** HKDF-SHA256 Extract+Expand (sid + itn.i) */
    public static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int len) throws GeneralSecurityException {
        byte[] prk = hmac(salt, ikm);                 // Extract
        byte[] okm = new byte[len];
        byte[] t = new byte[0];
        int pos = 0;
        for (int i = 1; pos < len; i++) {             // Expand
            byte[] in = new byte[t.length + info.length + 1];
            System.arraycopy(t, 0, in, 0, t.length);
            System.arraycopy(info, 0, in, t.length, info.length);
            in[in.length - 1] = (byte) i;
            t = hmac(prk, in);
            int n = Math.min(t.length, len - pos);
            System.arraycopy(t, 0, okm, pos, n);
            pos += n;
        }
        Arrays.fill(prk, (byte) 0);
        return okm;
    }

    /** AES-GCM，tag 仅 4 字节（BC 的 GCMBlockCipher，macSize=32bit） */
    public static byte[] gcm(boolean encrypt, byte[] key, byte[] nonce, byte[] in, int tagBits) {
        GCMBlockCipher c = new GCMBlockCipher(new AESEngine());
        c.init(encrypt, new AEADParameters(new KeyParameter(key), tagBits, nonce, null));
        byte[] out = new byte[c.getOutputSize(in.length)];
        int n = c.processBytes(in, 0, in.length, out, 0);
        try { n += c.doFinal(out, n); } catch (Exception e) { return null; }
        return out;
    }

    /**
     * AES-CCM（BouncyCastle CCMBlockCipher，macSize=32bit）—— 这才是真机用的算法。
     * nonce = IV(4B) ‖ 0x00000000(4B) ‖ counter(4B)（非 101 类型时 counter 段全 0）
     * 返回 ciphertext ‖ mac(4B)
     */
    public static byte[] ccm(boolean encrypt, byte[] key, byte[] nonce, byte[] in, int macBits) {
        return ccm(encrypt, key, nonce, in, macBits, null);
    }

    /** 带 AAD 的版本（绑定流程 apiCode 25 用 aad="bind-data"） */
    public static byte[] ccm(boolean encrypt, byte[] key, byte[] nonce, byte[] in, int macBits, byte[] aad) {
        CCMBlockCipher c = new CCMBlockCipher(new AESEngine());
        c.init(encrypt, new AEADParameters(new KeyParameter(key), macBits, nonce, aad));
        byte[] out = new byte[c.getOutputSize(in.length)];
        int n = c.processBytes(in, 0, in.length, out, 0);
        try { n += c.doFinal(out, n); } catch (Exception e) { return null; }
        return out;
    }

    /**
     * 会话加密（真机算法，来自 defpackage.r1）：
     *   Cipher.getInstance("AES/CTR/NoPadding")，IV = key 本身（16 字节）
     *   无 MAC。App→Device 用 AppKey；Device→App 用 DeviceKey。
     */
    public static byte[] ctr(byte[] key, byte[] data) {
        try {
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding");
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"),
                   new javax.crypto.spec.IvParameterSpec(key));
            return c.doFinal(data);
        } catch (Exception e) { return null; }
    }

    /** 认证握手结果：4 个会话密钥 */
    public static final class Keys {
        public final byte[] deviceKey, appKey, deviceIv, appIv;
        Keys(byte[] dk, byte[] ak, byte[] div, byte[] aiv) {
            deviceKey = dk; appKey = ak; deviceIv = div; appIv = aiv;
        }
        /** 由 encrypt_key + 两个随机数派生（已对真机抓包验证签名一致） */
        public static Keys derive(byte[] secret, byte[] randomApp, byte[] randomDevice)
                throws GeneralSecurityException {
            byte[] salt = concat(randomApp, randomDevice);
            byte[] okm = hkdf(salt, secret, "miwear-auth".getBytes(), 64);
            return new Keys(Arrays.copyOfRange(okm, 0, 16), Arrays.copyOfRange(okm, 16, 32),
                            Arrays.copyOfRange(okm, 32, 36), Arrays.copyOfRange(okm, 36, 40));
        }

        /**
         * 本地绑定（pairing）的密钥派生 —— 逆向自 LocalWearBinderV2.q()：
         *   okm = HKDF(ikm=ECDH共享密钥, salt=appRandom||deviceRandom, info="miwear-bind", 64)
         *   [0:16]=bindDeviceKey [16:32]=bindAppKey [32:36]/[36:40]=IV [40:56]=auth key
         */
        public static byte[] deriveBind(byte[] shared, byte[] appRandom, byte[] deviceRandom)
                throws GeneralSecurityException {
            return hkdf(concat(appRandom, deviceRandom), shared, "miwear-bind".getBytes(), 64);
        }
    }

    public static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
