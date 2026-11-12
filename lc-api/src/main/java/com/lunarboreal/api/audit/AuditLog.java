package com.lunarboreal.api.audit;

import java.util.List;

/** 审计日志（表 lc_audit），web 面板"操作日志"页展示。 */
public interface AuditLog {

    record Entry(long id, long time, String module, String actor, String action, String detail) {}

    /** 异步写入。actor 如 "console"、玩家名、"web:admin"。 */
    void log(String module, String actor, String action, String detail);

    List<Entry> recent(String module, int offset, int limit);
}
