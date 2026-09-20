package com.xiaomi.fitness.netproxy.core;

/**
 * 复刻自小米运动健康的 NetProxy（JNI 绑定 libnetproxy.so）。
 *
 * ⚠️ 包名 / 类名 / native 方法名 / sendData 的签名 ([B)V 必须与 .so 里
 * JNI_OnLoad 动态注册的完全一致，否则 RegisterNatives 会失败。
 * 所以这个类**不能改名、不能混淆**。
 */
public final class NetProxy {

    public static final NetProxy INSTANCE = new NetProxy();

    /** .so 是否成功加载 */
    public static final boolean LOADED;

    private static NetProxySender sender;

    static {
        boolean ok;
        try { System.loadLibrary("netproxy"); ok = true; }
        catch (Throwable t) { ok = false; }
        LOADED = ok;
    }

    private NetProxy() {}

    /** native 侧通过 JNI 回调这里把回包交出来 */
    private void sendData(byte[] buffer) {
        NetProxySender s = sender;
        if (s != null) s.sendData(buffer);
    }

    public final native long netproxy_init(int sdk);

    public final native void netproxy_start(long context, int loglevel);

    public final native void netproxy_run(long context);

    public final native void netproxy_stop(long context);

    public final native void netproxy_clear(long context);

    public final native void netproxy_done(long context);

    public final native void netproxy_upstream(long context, byte[] buffer);

    public final native void netproxy_pcap(String name, int recordSize, int fileSize);

    public final void setNetProxySender(NetProxySender s) { sender = s; }
}
