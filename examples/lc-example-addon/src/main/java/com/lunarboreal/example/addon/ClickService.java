package com.lunarboreal.example.addon;

import java.util.List;
import java.util.UUID;

/**
 * 示例服务接口：外部插件也可以通过 LunarCore 的 ServiceRegistry 向其它插件/模块暴露服务。
 * <p>
 * 注意：服务接口类必须对消费方可见。跨插件共享时通常把接口放在一个公共 API jar 中；
 * 若希望 LC 模块也能使用，请联系 LunarCore 维护者把接口加入 lc-api。
 */
public interface ClickService {

    record Entry(UUID uuid, String name, long points) {}

    long points(UUID player);

    /** @return 增加后的点数 */
    long add(UUID player, String name, long delta);

    void reset(UUID player);

    List<Entry> top(int limit);
}
