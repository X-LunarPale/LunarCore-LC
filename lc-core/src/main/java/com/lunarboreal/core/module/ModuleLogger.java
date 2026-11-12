package com.lunarboreal.core.module;

import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** 带 [模块ID] 前缀、转发到 LunarCore 插件日志的 Logger。 */
final class ModuleLogger extends Logger {

    private final String prefix;

    ModuleLogger(String id, Logger parent) {
        super("LunarCore-" + id, null);
        this.prefix = "[" + id + "] ";
        setParent(parent);
        setUseParentHandlers(true);
    }

    @Override
    public void log(LogRecord record) {
        record.setMessage(prefix + record.getMessage());
        super.log(record);
    }
}
