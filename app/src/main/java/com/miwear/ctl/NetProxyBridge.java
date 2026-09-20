package com.miwear.ctl;

import com.xiaomi.fitness.netproxy.core.NetProxy;
import com.xiaomi.fitness.netproxy.core.NetProxySender;

/**
 * 手表联网网关：手表自带 TCP/IP 栈，把**原始 IP 包**通过蓝牙（L2 通道 7）丢给手机；
 * 这里用小米自己的 libnetproxy.so 做 NAT/代理，再把回包发回手表。
 *
 * 数据流：
 *   手表 --(ch7, 明文 IP 包)--> upstream() --JNI--> libnetproxy.so
 *   libnetproxy.so --JNI 回调 sendData()--> sink.send() --(ch7)--> 手表
 */
public class NetProxyBridge implements NetProxySender {

    /** 把回包发回手表的出口 */
    public interface Sink { void send(byte[] ipPacket) throws Exception; }

    private final WearLink.Log log;
    private final Sink sink;
    private volatile long ctx = 0;
    private volatile boolean started = false;
    private Thread runner;

    public NetProxyBridge(WearLink.Log log, Sink sink) { this.log = log; this.sink = sink; }

    public boolean isStarted() { return started; }

    public void start() {
        if (!NetProxy.LOADED) { log.log("⚠ libnetproxy.so 未加载，联网不可用"); return; }
        if (started) { log.log("netproxy 已在运行"); return; }
        NetProxy.INSTANCE.setNetProxySender(this);
        ctx = NetProxy.INSTANCE.netproxy_init(android.os.Build.VERSION.SDK_INT);
        NetProxy.INSTANCE.netproxy_start(ctx, 4);
        started = true;
        runner = new Thread(() -> {
            try { NetProxy.INSTANCE.netproxy_run(ctx); }
            catch (Throwable t) { log.log("netproxy_run 退出: " + t); }
        }, "netproxy");
        runner.setDaemon(true);
        runner.start();
        log.log("🌐 netproxy 已启动 (ctx=" + ctx + ")");
    }

    public void stop() {
        if (!started) return;
        started = false;
        try { NetProxy.INSTANCE.netproxy_stop(ctx); } catch (Throwable ignored) {}
        ctx = 0;
        log.log("netproxy 已停止");
    }

    /** 手表 → 手机：一个原始 IP 包 */
    public void upstream(byte[] ipPacket) {
        if (!started) return;
        try { NetProxy.INSTANCE.netproxy_upstream(ctx, ipPacket); }
        catch (Throwable t) { log.log("netproxy upstream 异常: " + t); }
    }

    /** libnetproxy.so → 手表：一个原始 IP 包 */
    @Override public void sendData(byte[] ipPacket) {
        if (ipPacket == null || ipPacket.length == 0) return;
        try {
            sink.send(ipPacket);
            log.log("🌐 ← 下发 " + ipPacket.length + "B  " + ipSummary(ipPacket));
        } catch (Exception e) {
            log.log("netproxy 下发失败: " + e);
        }
    }

    /** 打印 IP 包摘要（源/目的 IP + 协议），方便调试 */
    public static String ipSummary(byte[] p) {
        if (p.length < 20 || (p[0] >> 4) != 4) return "(非 IPv4, " + p.length + "B)";
        int ihl = (p[0] & 0x0F) * 4;
        if (p.length < ihl) return "(IP 头截断)";
        String src = (p[12] & 0xFF) + "." + (p[13] & 0xFF) + "." + (p[14] & 0xFF) + "." + (p[15] & 0xFF);
        String dst = (p[16] & 0xFF) + "." + (p[17] & 0xFF) + "." + (p[18] & 0xFF) + "." + (p[19] & 0xFF);
        int proto = p[9] & 0xFF;
        String pn = proto == 6 ? "TCP" : proto == 17 ? "UDP" : proto == 1 ? "ICMP" : ("proto" + proto);
        String extra = "";
        if ((proto == 6 || proto == 17) && p.length >= ihl + 4) {
            int sp = ((p[ihl] & 0xFF) << 8) | (p[ihl + 1] & 0xFF);
            int dp = ((p[ihl + 2] & 0xFF) << 8) | (p[ihl + 3] & 0xFF);
            extra = " " + sp + "→" + dp;
        }
        return src + "→" + dst + " " + pn + extra;
    }
}
