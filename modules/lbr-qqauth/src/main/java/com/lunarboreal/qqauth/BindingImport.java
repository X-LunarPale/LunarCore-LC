package com.lunarboreal.qqauth;

import com.lunarboreal.qqauth.binding.Binding;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.util.*;

final class BindingImport {
    static void importFile(QQAuthModule module) throws Exception {
        File source=new File(module.dataFolder(),"import-bindings.yml");
        if(!source.isFile() || source.length()>8*1024*1024) throw new IllegalArgumentException("缺少导入文件或文件过大");
        YamlConfiguration yaml=new YamlConfiguration(); yaml.load(source);
        var section=yaml.getConfigurationSection("bindings");
        if(section==null) throw new IllegalArgumentException("缺少 bindings");
        String group=module.config().getString("integration.group-id","");
        if(group.isBlank() || group.contains("REPLACE_") || group.equals("*")) throw new IllegalArgumentException("导入需要明确群ID");
        List<Binding> records=new ArrayList<>();
        for(String id:section.getKeys(false)) {
            var row=section.getConfigurationSection(id);
            if(row==null) throw new IllegalArgumentException("无效绑定记录");
            long timestamp=row.getLong("bound-at");
            // Upstream uses epoch seconds; the new module stores epoch milliseconds.
            if(timestamp>0 && timestamp<100_000_000_000L) timestamp=Math.multiplyExact(timestamp,1000);
            records.add(new Binding(UUID.fromString(id),row.getString("name"),row.getString("qq"),group,timestamp));
        }
        for (Binding binding : records) com.lunarboreal.qqauth.auth.AuthService.nameKey(binding.playerName());
        module.bindings.repository().importBindings(records,module.bindingSettings());
        records.forEach(binding -> module.bound.add(binding.uuid()));
    }
}
