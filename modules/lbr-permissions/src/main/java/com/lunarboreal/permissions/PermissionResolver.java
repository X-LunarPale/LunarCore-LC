package com.lunarboreal.permissions;

import java.util.*;

/** Pure resolution; expired root memberships cannot activate inherited permissions. */
public final class PermissionResolver {
    private PermissionResolver() {}
    private record Candidate(boolean direct, int specificity, int weight, int depth, boolean value) {}
    public static Optional<Boolean> resolve(PermissionState state, UUID uuid, String node, long now) {
        PermissionState.node(node);
        List<Candidate> candidates = new ArrayList<>();
        PermissionState.User user = state.users().get(uuid);
        if (user != null) collect(user.permissions(), node, now, true, 0, 0, candidates);
        Map<String,Integer> depths = groupDepths(state, user, now);
        for (var entry : depths.entrySet()) {
            var group = state.groups().get(entry.getKey());
            collect(group.permissions(), node, now, false, group.weight(), entry.getValue(), candidates);
        }
        return candidates.stream().max(PermissionResolver::compare).map(Candidate::value);
    }
    /** Finite explicit node set; Bukkit integrator must also resolve registered concrete nodes. */
    public static Map<String,Boolean> effective(PermissionState state, UUID uuid, long now) {
        Set<String> nodes = new TreeSet<>(); var user = state.users().get(uuid);
        if (user != null) nodes.addAll(user.permissions().keySet());
        for (String group : groupDepths(state, user, now).keySet()) nodes.addAll(state.groups().get(group).permissions().keySet());
        Map<String,Boolean> result = new LinkedHashMap<>();
        for (String node : nodes) resolve(state, uuid, node, now).ifPresent(value -> result.put(node,value));
        return Map.copyOf(result);
    }
    public static Map<String,Integer> groupDepths(PermissionState state, PermissionState.User user, long now) {
        Map<String,Integer> depths = new HashMap<>(); Deque<String> queue = new ArrayDeque<>();
        depths.put(state.defaultGroup(),0); queue.add(state.defaultGroup());
        if (user != null) user.groups().forEach((group,membership) -> { if (membership.active(now) && depths.putIfAbsent(group,0) == null) queue.add(group); });
        while (!queue.isEmpty()) {
            String group = queue.remove(); int next = depths.get(group)+1;
            for (String parent : state.groups().get(group).parents()) if (!depths.containsKey(parent) || next < depths.get(parent)) { depths.put(parent,next);queue.add(parent); }
        }
        return Map.copyOf(depths);
    }
    private static void collect(Map<String,PermissionState.Grant> grants, String node, long now, boolean direct, int weight, int depth, List<Candidate> candidates) {
        grants.forEach((pattern,grant) -> {
            if (!grant.active(now)) return;
            int specificity = specificity(pattern,node);
            if (specificity >= 0) candidates.add(new Candidate(direct,specificity,weight,depth,grant.value()));
        });
    }
    static int specificity(String pattern, String node) {
        if (pattern.equals(node)) return Integer.MAX_VALUE;
        if (pattern.equals("*")) return 0;
        if (pattern.endsWith(".*")) {
            String prefix=pattern.substring(0,pattern.length()-1);
            if (node.startsWith(prefix) && node.length()>prefix.length()) return prefix.length();
        }
        return -1;
    }
    private static int compare(Candidate a,Candidate b) {
        int result=Boolean.compare(a.direct,b.direct); if(result!=0)return result;
        result=Integer.compare(a.specificity,b.specificity);if(result!=0)return result;
        result=Integer.compare(a.weight,b.weight);if(result!=0)return result;
        result=Integer.compare(b.depth,a.depth);if(result!=0)return result;
        return Boolean.compare(!a.value,!b.value);
    }
}
