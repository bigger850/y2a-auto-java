package com.y2a.server.service.notification;

import java.util.Map;

/**
 * 通知渠道策略接口（策略模式）。
 * 新增渠道只需实现本接口并注册到 {@link NotificationDispatcher}，不改投递主流程。
 */
public interface NotificationChannel {

    /** 渠道标识，与配置 NOTIFY_CHANNELS 中的值一致。 */
    String id();

    /** 用给定配置发送一条通知；返回是否成功。 */
    boolean send(String title, String content, Map<String, Object> config);
}
