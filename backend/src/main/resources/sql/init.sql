-- ===========================================================================
-- 推演平台 (EIC-CC) 数据库初始化脚本
-- 适配 PostgreSQL 12+
-- 执行方式：psql -U <user> -d <database> -f init.sql
-- 幂等：可重复执行（使用 IF NOT EXISTS）
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. 本体模型（ontology_model）
-- ---------------------------------------------------------------------------

-- 本体图谱模型主表：每行对应一个图谱画布
CREATE TABLE IF NOT EXISTS ontology_model (
    id              VARCHAR(64)  PRIMARY KEY,
    title           VARCHAR(255) NOT NULL,
    description     TEXT,
    updated_label   VARCHAR(32),                 -- 人类可读时间，如"刚刚"、"3 分钟前"
    created_at      BIGINT       NOT NULL,      -- 毫秒时间戳
    updated_at      BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ontology_model_updated_at
    ON ontology_model (updated_at DESC);

-- 本体节点：与模型一对多
CREATE TABLE IF NOT EXISTS ontology_node (
    id                      VARCHAR(64)      NOT NULL,
    model_id                VARCHAR(64)      NOT NULL REFERENCES ontology_model(id) ON DELETE CASCADE,
    label                   VARCHAR(255),
    type                    VARCHAR(32),
    source                  VARCHAR(32),
    x                       DOUBLE PRECISION,
    y                       DOUBLE PRECISION,
    predicted_step          INTEGER,
    predicted_intent        VARCHAR(16),
    confidence              DOUBLE PRECISION,
    effective_probability   DOUBLE PRECISION,
    explanation             TEXT,
    PRIMARY KEY (model_id, id)
);
CREATE INDEX IF NOT EXISTS idx_ontology_node_model
    ON ontology_node (model_id);

-- 节点属性：用户/LLM 自由命名键值对
CREATE TABLE IF NOT EXISTS ontology_node_prop (
    id          BIGSERIAL    PRIMARY KEY,
    model_id    VARCHAR(64)  NOT NULL,
    node_id     VARCHAR(64)  NOT NULL,
    prop_key    VARCHAR(255) NOT NULL,
    prop_value  TEXT,
    value_type  VARCHAR(16),                    -- string | number | bool | json
    source      VARCHAR(32),
    sort_no     INTEGER      NOT NULL DEFAULT 0,
    FOREIGN KEY (model_id, node_id) REFERENCES ontology_node(model_id, id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_ontology_node_prop_owner
    ON ontology_node_prop (model_id, node_id);

-- 本体边：因果/关联关系
CREATE TABLE IF NOT EXISTS ontology_edge (
    id             VARCHAR(64)  NOT NULL,
    model_id       VARCHAR(64)  NOT NULL REFERENCES ontology_model(id) ON DELETE CASCADE,
    from_node_id   VARCHAR(64)  NOT NULL,
    to_node_id     VARCHAR(64)  NOT NULL,
    label          VARCHAR(255),
    source         VARCHAR(32),
    rule_driven    BOOLEAN      NOT NULL DEFAULT FALSE,
    rule_id        VARCHAR(64),
    PRIMARY KEY (model_id, id)
);
CREATE INDEX IF NOT EXISTS idx_ontology_edge_model
    ON ontology_edge (model_id);
CREATE INDEX IF NOT EXISTS idx_ontology_edge_from
    ON ontology_edge (model_id, from_node_id);
CREATE INDEX IF NOT EXISTS idx_ontology_edge_to
    ON ontology_edge (model_id, to_node_id);

-- ---------------------------------------------------------------------------
-- 2. 模型版本快照（每模型最多保留 100 个）
-- ---------------------------------------------------------------------------

-- 版本元信息
CREATE TABLE IF NOT EXISTS ontology_model_version (
    id            BIGSERIAL    PRIMARY KEY,
    model_id      VARCHAR(64)  NOT NULL REFERENCES ontology_model(id) ON DELETE CASCADE,
    snapshot_at   BIGINT       NOT NULL,
    title         VARCHAR(255),
    description   TEXT
);
CREATE INDEX IF NOT EXISTS idx_version_model_at
    ON ontology_model_version (model_id, snapshot_at DESC);

-- 版本节点
CREATE TABLE IF NOT EXISTS ontology_version_node (
    version_id              BIGINT           NOT NULL REFERENCES ontology_model_version(id) ON DELETE CASCADE,
    node_id                 VARCHAR(64)      NOT NULL,
    label                   VARCHAR(255),
    type                    VARCHAR(32),
    source                  VARCHAR(32),
    x                       DOUBLE PRECISION,
    y                       DOUBLE PRECISION,
    predicted_step          INTEGER,
    predicted_intent        VARCHAR(16),
    confidence              DOUBLE PRECISION,
    effective_probability   DOUBLE PRECISION,
    explanation             TEXT,
    PRIMARY KEY (version_id, node_id)
);

-- 版本节点属性
CREATE TABLE IF NOT EXISTS ontology_version_node_prop (
    id          BIGSERIAL    PRIMARY KEY,
    version_id  BIGINT       NOT NULL,
    node_id     VARCHAR(64)  NOT NULL,
    prop_key    VARCHAR(255) NOT NULL,
    prop_value  TEXT,
    value_type  VARCHAR(16),
    source      VARCHAR(32),
    sort_no     INTEGER      NOT NULL DEFAULT 0,
    FOREIGN KEY (version_id, node_id) REFERENCES ontology_version_node(version_id, node_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_version_node_prop_owner
    ON ontology_version_node_prop (version_id, node_id);

-- 版本边
CREATE TABLE IF NOT EXISTS ontology_version_edge (
    version_id     BIGINT       NOT NULL REFERENCES ontology_model_version(id) ON DELETE CASCADE,
    edge_id        VARCHAR(64)  NOT NULL,
    from_node_id   VARCHAR(64)  NOT NULL,
    to_node_id     VARCHAR(64)  NOT NULL,
    label          VARCHAR(255),
    source         VARCHAR(32),
    rule_driven    BOOLEAN      NOT NULL DEFAULT FALSE,
    rule_id        VARCHAR(64),
    PRIMARY KEY (version_id, edge_id)
);

-- ---------------------------------------------------------------------------
-- 3. 推演分支（Scenario）
-- ---------------------------------------------------------------------------

-- 分支元信息
CREATE TABLE IF NOT EXISTS scenario (
    id                  VARCHAR(64)  PRIMARY KEY,
    model_id            VARCHAR(64)  NOT NULL REFERENCES ontology_model(id) ON DELETE CASCADE,
    parent_branch_id    VARCHAR(64),                                  -- 父分支 id；null 表示从 trunk 创建
    name                VARCHAR(255),
    intent              VARCHAR(16),                                  -- forward | backward
    steps               INTEGER      NOT NULL DEFAULT 0,
    prompt              TEXT,
    raw_prompt          TEXT,                                         -- 本次发给 LLM 的完整 prompt 快照
    created_at          BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_scenario_model
    ON scenario (model_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_scenario_parent
    ON scenario (parent_branch_id);

-- 起点节点（seeds）
CREATE TABLE IF NOT EXISTS scenario_seed (
    scenario_id  VARCHAR(64)  NOT NULL REFERENCES scenario(id) ON DELETE CASCADE,
    node_id      VARCHAR(64)  NOT NULL,
    sort_no      INTEGER      NOT NULL DEFAULT 0,
    PRIMARY KEY (scenario_id, node_id)
);

-- DAG 增量节点（仅本分支新增）
CREATE TABLE IF NOT EXISTS scenario_node (
    scenario_id             VARCHAR(64)      NOT NULL REFERENCES scenario(id) ON DELETE CASCADE,
    node_id                 VARCHAR(64)      NOT NULL,
    label                   VARCHAR(255),
    type                    VARCHAR(32),
    source                  VARCHAR(32),
    x                       DOUBLE PRECISION,
    y                       DOUBLE PRECISION,
    predicted_step          INTEGER,
    confidence              DOUBLE PRECISION,
    effective_probability   DOUBLE PRECISION,
    explanation             TEXT,
    PRIMARY KEY (scenario_id, node_id)
);

-- DAG 增量边（仅本分支新增）
CREATE TABLE IF NOT EXISTS scenario_edge (
    scenario_id    VARCHAR(64)  NOT NULL REFERENCES scenario(id) ON DELETE CASCADE,
    edge_id        VARCHAR(64)  NOT NULL,
    from_node_id   VARCHAR(64)  NOT NULL,
    to_node_id     VARCHAR(64)  NOT NULL,
    label          VARCHAR(255),
    source         VARCHAR(32),
    rule_driven    BOOLEAN      NOT NULL DEFAULT FALSE,
    rule_id        VARCHAR(64),
    PRIMARY KEY (scenario_id, edge_id)
);

-- 推演链（按 step_no 顺序）
CREATE TABLE IF NOT EXISTS scenario_chain_step (
    id                      BIGSERIAL        PRIMARY KEY,
    scenario_id             VARCHAR(64)      NOT NULL REFERENCES scenario(id) ON DELETE CASCADE,
    step_no                 INTEGER          NOT NULL,
    node_id                 VARCHAR(64)      NOT NULL,
    triggered_by_json       TEXT,                                     -- 上游节点 id 数组的 JSON 序列化
    rule_id                 VARCHAR(64),
    explanation             TEXT,
    confidence              DOUBLE PRECISION,
    effective_probability   DOUBLE PRECISION,
    cumulative_credibility  DOUBLE PRECISION
);
CREATE INDEX IF NOT EXISTS idx_chain_step_scenario
    ON scenario_chain_step (scenario_id, step_no);

-- what-if 约束
CREATE TABLE IF NOT EXISTS scenario_constraint (
    id            BIGSERIAL        PRIMARY KEY,
    scenario_id   VARCHAR(64)      NOT NULL REFERENCES scenario(id) ON DELETE CASCADE,
    node_id       VARCHAR(64)      NOT NULL,
    mode          VARCHAR(16)      NOT NULL,                          -- force | block | probability
    note          TEXT,
    probability   DOUBLE PRECISION
);
CREATE INDEX IF NOT EXISTS idx_scenario_constraint_owner
    ON scenario_constraint (scenario_id);

-- 节点三段式解释缓存
CREATE TABLE IF NOT EXISTS scenario_node_explanation (
    scenario_id        VARCHAR(64)  NOT NULL REFERENCES scenario(id) ON DELETE CASCADE,
    node_id            VARCHAR(64)  NOT NULL,
    evidence           TEXT,
    assumptions        TEXT,
    counterexamples    TEXT,
    generated_at       BIGINT,
    model_name         VARCHAR(128),
    PRIMARY KEY (scenario_id, node_id)
);

-- ---------------------------------------------------------------------------
-- 4. 对话历史
-- ---------------------------------------------------------------------------

-- 会话元信息
CREATE TABLE IF NOT EXISTS conversation (
    id           VARCHAR(64)  PRIMARY KEY,
    title        VARCHAR(255),
    created_at   BIGINT       NOT NULL,
    updated_at   BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_conversation_updated
    ON conversation (updated_at DESC);

-- 消息：role+content 强结构 + payload_json 兜底自由载荷
CREATE TABLE IF NOT EXISTS conversation_message (
    id                 BIGSERIAL    PRIMARY KEY,
    conversation_id    VARCHAR(64)  NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    seq_no             INTEGER      NOT NULL,                         -- 消息在会话中的顺序
    role               VARCHAR(32),
    content            TEXT,
    payload_json       TEXT,                                          -- 附件、推演消息、@引用等扩展字段的 JSON
    created_at         BIGINT
);
CREATE INDEX IF NOT EXISTS idx_conversation_message_owner
    ON conversation_message (conversation_id, seq_no);

-- ---------------------------------------------------------------------------
-- 5. 模板
-- ---------------------------------------------------------------------------

-- 图谱模板：nodes/edges 整体序列化为 JSON
CREATE TABLE IF NOT EXISTS graph_template (
    id           VARCHAR(64)  PRIMARY KEY,
    title        VARCHAR(255),
    description  TEXT,
    nodes_json   TEXT,
    edges_json   TEXT,
    created_at   BIGINT       NOT NULL,
    updated_at   BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_graph_template_updated
    ON graph_template (updated_at DESC);

-- 假设模板：seeds/constraints 序列化为 JSON
CREATE TABLE IF NOT EXISTS hypothesis_template (
    id                  VARCHAR(64)  PRIMARY KEY,
    model_id            VARCHAR(64),
    name                VARCHAR(255),
    intent              VARCHAR(16),
    steps               INTEGER,
    prompt              TEXT,
    seeds_json          TEXT,
    constraints_json    TEXT,
    created_at          BIGINT       NOT NULL,
    last_used_at        BIGINT
);
CREATE INDEX IF NOT EXISTS idx_hypothesis_template_model
    ON hypothesis_template (model_id, last_used_at DESC);

-- ===========================================================================
-- 初始化完成
-- ===========================================================================
