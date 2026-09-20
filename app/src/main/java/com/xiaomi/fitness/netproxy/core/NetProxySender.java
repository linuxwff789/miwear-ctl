package com.xiaomi.fitness.netproxy.core;

/** 复刻自小米运动健康：native 侧通过它把回包交给 Java 侧 */
public interface NetProxySender {
    void sendData(byte[] buffer);
}
