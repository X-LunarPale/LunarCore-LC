package com.lunarboreal.permissions;

import java.util.*;

/** Bukkit recursively applies registered child permissions in attachment insertion order. */
final class AttachmentOrder {
    static List<String> sort(Set<String> explicit, Map<String, Set<String>> children) {
        Set<String> all = new TreeSet<>(children.keySet()); all.addAll(explicit);
        children.values().forEach(all::addAll);
        Map<String,Integer> degrees = new TreeMap<>(); all.forEach(node -> degrees.put(node,0));
        for (var edges : children.values()) for (String child : edges) degrees.merge(child,1,Integer::sum);
        PriorityQueue<String> ready = new PriorityQueue<>(); degrees.forEach((node,count) -> { if (count==0) ready.add(node); });
        List<String> ordered = new ArrayList<>(); int visited=0;
        while(!ready.isEmpty()) {
            String node=ready.remove(); visited++; if(explicit.contains(node)) ordered.add(node);
            for(String child:children.getOrDefault(node,Set.of())) if(degrees.merge(child,-1,Integer::sum)==0) ready.add(child);
        }
        if(visited!=all.size()) throw new IllegalArgumentException("插件注册的权限子节点存在循环，无法安全应用权限");
        return List.copyOf(ordered);
    }
}
