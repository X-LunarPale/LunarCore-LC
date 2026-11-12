package com.lunarboreal.api.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 声明式 Web 面板。core 序列化为 JSON（GET /api/panels），前端 Vue 自动渲染，无需编写前端代码。
 *
 * <h3>端点约定</h3>
 * endpoint 以 "/" 开头为绝对路径（如 "/api/ext/economy/accounts"），否则相对于 /api/ext/{namespace}/。
 * <ul>
 *   <li>Table 数据：GET endpoint?page=1&size=20&q=关键字&sort=key&order=asc|desc → {"total":N,"items":[{..}]}</li>
 *   <li>Action：method(默认 POST) endpoint，endpoint 中 {列key} 会被行数据替换；
 *       请求体 = {"row":{..行数据..}, ...表单字段值}；响应 {"ok":true,"message":"..."}，失败返回 4xx + {"error":"..."}</li>
 *   <li>Form：GET loadEndpoint → {字段: 值}；保存 POST saveEndpoint，请求体 {字段: 值}</li>
 *   <li>Stats：GET endpoint → [{"label":"在线","value":"12","icon":"users"}]</li>
 * </ul>
 * 图标名使用 lucide 图标名（如 "coins"、"shield"、"map"）。
 */
public final class WebPanel {

    private final String id;
    private final String title;
    private String icon = "box";
    private String group = "模块";
    private int order = 100;
    private String permission;
    private final List<Section> sections = new ArrayList<>();
    /** 由 core 填充。 */
    private String namespace;

    private WebPanel(String id, String title) {
        this.id = id;
        this.title = title;
    }

    public static WebPanel of(String id, String title) {
        return new WebPanel(id, title);
    }

    public WebPanel icon(String icon) { this.icon = icon; return this; }

    /** 侧边栏分组，如 "玩家"、"经济"、"安全"、"模块"。 */
    public WebPanel group(String group) { this.group = group; return this; }

    public WebPanel order(int order) { this.order = order; return this; }

    /** 查看面板所需权限节点，null 为任何登录用户。 */
    public WebPanel permission(String permission) { this.permission = permission; return this; }

    public WebPanel section(Section section) { this.sections.add(section); return this; }

    public String id() { return id; }
    public String title() { return title; }
    public String permission() { return permission; }
    public int order() { return order; }
    public List<Section> sections() { return sections; }
    public String namespace() { return namespace; }
    public void namespace(String ns) { this.namespace = ns; }

    // =====================================================================

    public abstract static sealed class Section permits Table, Form, Stats, Iframe, Text {
        protected final String type;
        protected String title;
        /** 占用栅格宽度 1-24，默认 24（整行）。 */
        protected int span = 24;

        protected Section(String type, String title) {
            this.type = type;
            this.title = title;
        }
    }

    public static final class Table extends Section {
        private final String endpoint;
        private final List<Column> columns = new ArrayList<>();
        private final List<Action> rowActions = new ArrayList<>();
        private final List<Action> toolbarActions = new ArrayList<>();
        private boolean searchable = true;
        private int pageSize = 20;
        private String rowKey = "id";
        private int refreshSeconds = 0;
        private Map<String, Map<String, String>> valueLabels;

        public Table(String title, String endpoint) {
            super("table", title);
            this.endpoint = endpoint;
        }

        public Table column(String key, String label) { return column(new Column(key, label, "text")); }

        public Table column(String key, String label, String type) { return column(new Column(key, label, type)); }

        public Table column(Column c) { columns.add(c); return this; }

        public Table rowAction(Action a) { rowActions.add(a); return this; }

        public Table toolbarAction(Action a) { toolbarActions.add(a); return this; }

        public Table searchable(boolean s) { this.searchable = s; return this; }

        public Table pageSize(int s) { this.pageSize = s; return this; }

        public Table rowKey(String k) { this.rowKey = k; return this; }

        /** 固定枚举值的显示文案；只翻译显式列出的值，不修改原始行数据。 */
        public Table valueLabels(String columnKey, Map<String, String> labels) {
            if (columnKey == null || columnKey.isBlank()) throw new IllegalArgumentException("column key is required");
            if (valueLabels == null) valueLabels = new LinkedHashMap<>();
            valueLabels.put(columnKey, Map.copyOf(labels));
            return this;
        }

        /** 自动刷新间隔（秒），0 为关闭；前端在区块移除时停止轮询。 */
        public Table refresh(int seconds) {
            if (seconds < 0) throw new IllegalArgumentException("refresh seconds must be non-negative");
            this.refreshSeconds = seconds;
            return this;
        }

        public Table span(int s) { this.span = s; return this; }
    }

    /**
     * @param type text | number | money | datetime(毫秒时间戳) | bool | player(UUID或名字，显示头像) | tag | duration(毫秒)
     */
    public record Column(String key, String label, String type, boolean sortable) {
        public Column(String key, String label, String type) { this(key, label, type, false); }
    }

    public static final class Action {
        private final String id;
        private final String label;
        private final String endpoint;
        private String method = "POST";
        private String confirm;
        private String style = "default";
        private final List<Field> fields = new ArrayList<>();
        private String permission;
        private String visibleWhenField;
        private List<String> visibleWhenValues;

        public Action(String id, String label, String endpoint) {
            this.id = id;
            this.label = label;
            this.endpoint = endpoint;
        }

        public Action method(String m) { this.method = m; return this; }

        /** 执行前弹出确认框的文本。 */
        public Action confirm(String text) { this.confirm = text; return this; }

        /** default | primary | danger | warning */
        public Action style(String s) { this.style = s; return this; }

        /** 执行前弹窗收集字段。 */
        public Action field(Field f) { fields.add(f); return this; }

        /** 无此权限的用户前端隐藏按钮（后端仍需校验）。 */
        public Action permission(String p) { this.permission = p; return this; }

        /** 仅在指定字段匹配某个原始值时显示行操作；后端仍须验证状态。 */
        public Action visibleWhen(String field, String... values) {
            if (field == null || field.isBlank() || values.length == 0)
                throw new IllegalArgumentException("field and allowed values are required");
            this.visibleWhenField = field;
            this.visibleWhenValues = List.of(values);
            return this;
        }
    }

    public static final class Field {
        private final String key;
        private final String label;
        private final String type;
        private final List<Option> options = new ArrayList<>();
        private String placeholder;
        private String help;
        private String group;
        private boolean required;
        private Object defaultValue;
        private Double min;
        private Double max;
        private Double step;
        private Boolean preview;

        /**
         * @param type text | number | bool | select | textarea | password | player | duration("1d2h"格式) | datetime | code(yaml/json 编辑器)
         */
        public Field(String key, String label, String type) {
            this.key = key;
            this.label = label;
            this.type = type;
        }

        public Field option(String value, String label) { options.add(new Option(value, label)); return this; }

        public Field placeholder(String p) { this.placeholder = p; return this; }

        public Field help(String h) { this.help = h; return this; }

        /** 可选表单分组标题；未设置时沿用普通字段布局。 */
        public Field group(String label) { this.group = label; return this; }

        public Field required(boolean r) { this.required = r; return this; }

        public Field defaultValue(Object v) { this.defaultValue = v; return this; }

        /** 数值控件的可视范围，不替代服务端校验。 */
        public Field range(double min, double max, double step) {
            if (!Double.isFinite(min) || !Double.isFinite(max) || !Double.isFinite(step) || min >= max || step <= 0)
                throw new IllegalArgumentException("Invalid field range");
            this.min = min; this.max = max; this.step = step; return this;
        }

        /** 明确指定是否显示 Minecraft 文本预览。 */
        public Field preview(boolean enabled) { this.preview = enabled; return this; }
    }

    public record Option(String value, String label) {}

    public static final class Form extends Section {
        private final String loadEndpoint;
        private final String saveEndpoint;
        private final List<Field> fields = new ArrayList<>();
        private String submitLabel = "保存";
        private String savePermission;

        public Form(String title, String loadEndpoint, String saveEndpoint) {
            super("form", title);
            this.loadEndpoint = loadEndpoint;
            this.saveEndpoint = saveEndpoint;
        }

        public Form field(Field f) { fields.add(f); return this; }

        public Form submitLabel(String l) { this.submitLabel = l; return this; }

        /** 保存权限；无权限时前端只读并隐藏保存按钮。后端保存路由仍必须独立鉴权。 */
        public Form savePermission(String permission) { this.savePermission = permission; return this; }

        public Form span(int s) { this.span = s; return this; }
    }

    public static final class Stats extends Section {
        private final String endpoint;
        private int refreshSeconds = 0;

        public Stats(String title, String endpoint) {
            super("stats", title);
            this.endpoint = endpoint;
        }

        public Stats refresh(int seconds) { this.refreshSeconds = seconds; return this; }

        public Stats span(int s) { this.span = s; return this; }
    }

    public static final class Iframe extends Section {
        private final String url;
        private int height = 600;

        /** url 可以是 /ext/{namespace}/index.html（registerStatic 注册的资源）。 */
        public Iframe(String title, String url) {
            super("iframe", title);
            this.url = url;
        }

        public Iframe height(int h) { this.height = h; return this; }
    }

    public static final class Text extends Section {
        private final String markdown;
        private String markdownKey;

        public Text(String title, String markdown) {
            super("text", title);
            this.markdown = markdown;
        }

        /** 仅供模块自带固定说明使用，禁止混入玩家输入或配置内容。 */
        public Text markdownKey(String key) { this.markdownKey = key; return this; }
    }
}
