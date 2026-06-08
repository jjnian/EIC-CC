package com.tuiyan.backend.model;

/**
 * 用户在 chat 输入框里用 @ 引用的对象。
 * <p>每个 @ 选中一次,前端就追加一条本结构,随 ChatRequest 一起 POST 给后端。
 * 后端据此做"定向上下文":
 * <ul>
 *   <li>kind="datasource": 仅注入这些数据源的 schema(不再无差别地拉所有 DB);</li>
 *   <li>kind="experience": 把这些经验库文件的全文作为定向上下文注入(优先于自动 RAG 命中);</li>
 *   <li>kind="node" / "relation": 当前图谱较大时,以这些 id 为中心做 N-hop 邻域聚焦;</li>
 *   <li>kind="graph": 默认就是全图,无需特殊处理。</li>
 * </ul>
 * <p>不做校验,业务规则在 ChatLlmService 处理。
 */
public class MentionRef {
    /** "graph" / "node" / "relation" / "datasource" / "experience" */
    private String kind;
    /** 对应实体的 id(节点 id、边 id、数据源 id、经验 id、图谱 id) */
    private String id;
    /** 展示文本(用于 prompt 中复述给 LLM) */
    private String label;

    public MentionRef() {}

    public MentionRef(String kind, String id, String label) {
        this.kind = kind;
        this.id = id;
        this.label = label;
    }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
}
