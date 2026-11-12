package com.lunarboreal.playertitleweb;

import java.lang.reflect.*;
import java.util.*;

/** Only documented public API methods and public DTO accessors; no private access. */
final class PublicTitleApi {
    private final Class<?> api;
    PublicTitleApi(ClassLoader loader) throws ReflectiveOperationException {
        api = Class.forName("cn.handyplus.title.api.PlayerTitleApi", true, loader);
        api.getMethod("findTitleById", Integer.class);
        api.getMethod("playerUseTitle", UUID.class, Integer.class);
    }
    private Object call(String method, Class<?>[] types, Object... args) throws Exception {
        try { return api.getMethod(method, types).invoke(null, args); }
        catch (InvocationTargetException e) { throw new IllegalStateException("PlayerTitle API 操作失败", e.getCause()); }
    }
    static Object get(Object dto, String property) throws ReflectiveOperationException {
        return dto.getClass().getMethod("get" + property).invoke(dto);
    }
    static Map<String, Object> catalogRow(Object dto) throws ReflectiveOperationException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String property : List.of("Id", "TitleName", "Description", "Day", "IsHide", "Position")) row.put(Character.toLowerCase(property.charAt(0)) + property.substring(1), get(dto, property));
        return row;
    }
    static Map<String, Object> ownedRow(Object dto) throws ReflectiveOperationException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String property : List.of("Id", "TitleId", "TitleName", "Description", "ExpirationTime", "IsUseShow", "PlayerUuid", "PlayerName")) {
            Object value = get(dto, property);
            if (value instanceof Date date) value = date.getTime();
            if (value instanceof UUID uuid) value = uuid.toString();
            row.put(Character.toLowerCase(property.charAt(0)) + property.substring(1), value);
        }
        return row;
    }
    Optional<Map<String, Object>> find(int id) throws Exception {
        Object value = call("findTitleById", new Class<?>[]{Integer.class}, id);
        if (!(value instanceof Optional<?> optional)) throw new IllegalStateException("Unexpected findTitleById result");
        return optional.isEmpty() ? Optional.empty() : Optional.of(catalogRow(optional.get()));
    }
    List<Map<String, Object>> shop() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        Object result = call("findShopTitle", new Class<?>[]{UUID.class}, (Object) null);
        if (result == null) return rows;
        if (!(result instanceof List<?> list)) throw new IllegalStateException("Unexpected shop result");
        for (Object dto : list) if (dto != null) rows.add(catalogRow(dto));
        return rows;
    }
    List<Map<String, Object>> owned(UUID uuid) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        Object result = call("findPlayerTitle", new Class<?>[]{UUID.class}, uuid);
        if (result == null) return rows;
        if (!(result instanceof List<?> list)) throw new IllegalStateException("Unexpected ownership result");
        for (Object dto : list) if (dto != null) rows.add(ownedRow(dto));
        return rows;
    }
    int create(String name, String description, int days, boolean prefix, String mode) throws Exception {
        ClassLoader loader = api.getClassLoader();
        Class<?> titleParam = Class.forName("cn.handyplus.title.api.param.TitleListParam", true, loader);
        Class<?> requireParam = Class.forName("cn.handyplus.title.api.param.TitleRequireParam", true, loader);
        Class<?> buyType = Class.forName("cn.handyplus.title.constants.BuyTypeEnum", true, loader);
        Object param = titleParam.getConstructor().newInstance();
        titleParam.getMethod("setTitleName", String.class).invoke(param, name);
        titleParam.getMethod("setDescription", String.class).invoke(param, description);
        titleParam.getMethod("setDay", Integer.class).invoke(param, days);
        titleParam.getMethod("setIsHide", Integer.class).invoke(param, 0);
        Class<?> position = Class.forName("cn.handyplus.title.constants.TitlePositionEnum", true, loader);
        titleParam.getMethod("setPosition", position).invoke(param, Enum.valueOf((Class) position, "ALL"));
        titleParam.getMethod("setIsPrefixAndSuffix", Boolean.class).invoke(param, prefix);
        Object type = Enum.valueOf((Class) buyType, mode);
        Object requirement = requireParam.getConstructor().newInstance();
        requireParam.getMethod("setBuyType", buyType).invoke(requirement, type);
        requireParam.getMethod("setBuyTypeName", String.class).invoke(requirement, buyType.getMethod("getBuyType").invoke(type));
        requireParam.getMethod("setAmount", Integer.class).invoke(requirement, 0);
        requireParam.getMethod("setBuyData", String.class).invoke(requirement, "");
        titleParam.getMethod("setRequires", List.class).invoke(param, List.of(requirement));
        titleParam.getMethod("setBuffs", List.class).invoke(param, List.of());
        Object result = call("addTitle", new Class<?>[]{titleParam}, param);
        if (!(result instanceof Integer id) || id < 1) throw new IllegalStateException("PlayerTitle 未返回有效的新称号 ID");
        return id;
    }
    void remove(int id) throws Exception { success(call("removeTitle", new Class<?>[]{Integer.class}, id)); }
    void rename(int id, String name, boolean prefix) throws Exception { success(call("updateTitleName", new Class<?>[]{Integer.class, String.class, boolean.class}, id, name, prefix)); }
    void describe(int id, String text) throws Exception { success(call("updateTitleDesc", new Class<?>[]{Integer.class, String.class}, id, text)); }
    void grant(UUID uuid, int id, int days) throws Exception { success(call("setPlayerTitle", new Class<?>[]{UUID.class, Integer.class, int.class}, uuid, id, days)); }
    void revoke(UUID uuid, int id) throws Exception { success(call("removePlayerTitle", new Class<?>[]{UUID.class, Integer.class}, uuid, id)); }
    void equip(UUID uuid, int ownedId) throws Exception {
        boolean found = owned(uuid).stream().anyMatch(row -> Objects.equals(row.get("id"), ownedId));
        if (!found) throw new IllegalArgumentException("该玩家不拥有此持有记录 ID");
        call("playerUseTitle", new Class<?>[]{UUID.class, Integer.class}, uuid, ownedId);
    }
    private static void success(Object result) { if (!Boolean.TRUE.equals(result)) throw new IllegalArgumentException("PlayerTitle API 返回失败；请检查称号、玩家状态或授权条件"); }
}
