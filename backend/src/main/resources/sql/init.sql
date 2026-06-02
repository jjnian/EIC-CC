-- ===========================================================================
-- 推演平台 (EIC-CC) 数据库初始化脚本
-- 适配 PostgreSQL 12+
-- 执行方式：psql -U <user> -d <database> -f init.sql
-- 幂等：可重复执行（使用 IF NOT EXISTS）
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 0. 工作空间（workspace）
-- 所有业务数据按 workspace_id 隔离。
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS workspace (
    id           VARCHAR(64)  PRIMARY KEY,
    name         VARCHAR(120) NOT NULL,
    description  TEXT,
    is_default   BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_no      INTEGER      NOT NULL DEFAULT 0,
    created_at   BIGINT       NOT NULL,
    updated_at   BIGINT       NOT NULL
);

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
    derived_tables_json     TEXT,
    attributes_json         TEXT,
    constraints_json        TEXT,
    derived_source          VARCHAR(255),
    derived_database        VARCHAR(255),
    x                       DOUBLE PRECISION,
    y                       DOUBLE PRECISION,
    predicted_step          INTEGER,
    predicted_intent        VARCHAR(16),
    confidence              DOUBLE PRECISION,
    effective_probability   DOUBLE PRECISION,
    explanation             TEXT,
    PRIMARY KEY (model_id, id)
);
ALTER TABLE ontology_node
    ADD COLUMN IF NOT EXISTS derived_tables_json TEXT,
    ADD COLUMN IF NOT EXISTS attributes_json TEXT,
    ADD COLUMN IF NOT EXISTS constraints_json TEXT,
    ADD COLUMN IF NOT EXISTS derived_source VARCHAR(255),
    ADD COLUMN IF NOT EXISTS derived_database VARCHAR(255);
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
    derived_tables_json TEXT,
    constraints_json    TEXT,
    derived_source      VARCHAR(255),
    derived_database    VARCHAR(255),
    rule_driven    BOOLEAN      NOT NULL DEFAULT FALSE,
    rule_id        VARCHAR(64),
    PRIMARY KEY (model_id, id)
);
ALTER TABLE ontology_edge
    ADD COLUMN IF NOT EXISTS derived_tables_json TEXT,
    ADD COLUMN IF NOT EXISTS constraints_json TEXT,
    ADD COLUMN IF NOT EXISTS derived_source VARCHAR(255),
    ADD COLUMN IF NOT EXISTS derived_database VARCHAR(255);
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
    derived_tables_json     TEXT,
    attributes_json         TEXT,
    constraints_json        TEXT,
    derived_source          VARCHAR(255),
    derived_database        VARCHAR(255),
    x                       DOUBLE PRECISION,
    y                       DOUBLE PRECISION,
    predicted_step          INTEGER,
    predicted_intent        VARCHAR(16),
    confidence              DOUBLE PRECISION,
    effective_probability   DOUBLE PRECISION,
    explanation             TEXT,
    PRIMARY KEY (version_id, node_id)
);
ALTER TABLE ontology_version_node
    ADD COLUMN IF NOT EXISTS derived_tables_json TEXT,
    ADD COLUMN IF NOT EXISTS attributes_json TEXT,
    ADD COLUMN IF NOT EXISTS constraints_json TEXT,
    ADD COLUMN IF NOT EXISTS derived_source VARCHAR(255),
    ADD COLUMN IF NOT EXISTS derived_database VARCHAR(255);

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
    derived_tables_json TEXT,
    constraints_json    TEXT,
    derived_source      VARCHAR(255),
    derived_database    VARCHAR(255),
    rule_driven    BOOLEAN      NOT NULL DEFAULT FALSE,
    rule_id        VARCHAR(64),
    PRIMARY KEY (version_id, edge_id)
);
ALTER TABLE ontology_version_edge
    ADD COLUMN IF NOT EXISTS derived_tables_json TEXT,
    ADD COLUMN IF NOT EXISTS constraints_json TEXT,
    ADD COLUMN IF NOT EXISTS derived_source VARCHAR(255),
    ADD COLUMN IF NOT EXISTS derived_database VARCHAR(255);

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

-- ---------------------------------------------------------------------------
-- 5.5 数据源（导入过的文档 / 网页）
-- 每次成功 extract 后写一行；按工作空间隔离，删除工作空间时级联清理。
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS data_source (
    id            VARCHAR(64)  PRIMARY KEY,
    workspace_id  VARCHAR(64)  NOT NULL,
    kind          VARCHAR(16)  NOT NULL,                          -- file | url
    name          VARCHAR(512) NOT NULL,                          -- 文件名 或 URL
    mime          VARCHAR(128),
    size_bytes    BIGINT,
    extra_json    TEXT,                                           -- pages/chars/title 等附加元信息
    created_at    BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_data_source_ws_created
    ON data_source (workspace_id, created_at DESC);

-- 5.5.1 数据源新增字段：持续挂载类型所需的配置 + 状态
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS config_json    TEXT;
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS status         VARCHAR(16) DEFAULT 'idle';
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS last_tested_at BIGINT;
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS last_error     TEXT;
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS updated_at     BIGINT;

-- 5.5.2 数据源执行历史（仅 https_api 写入）：单数据源最多保留 20 条
CREATE TABLE IF NOT EXISTS data_source_fetch_log (
    id              BIGSERIAL    PRIMARY KEY,
    data_source_id  VARCHAR(64)  NOT NULL REFERENCES data_source(id) ON DELETE CASCADE,
    fetched_at      BIGINT       NOT NULL,
    status_code     INTEGER,
    success         BOOLEAN      NOT NULL,
    response_body   TEXT,
    error_msg       TEXT,
    duration_ms     INTEGER
);
CREATE INDEX IF NOT EXISTS idx_fetch_log_ds_at
    ON data_source_fetch_log (data_source_id, fetched_at DESC);

-- ---------------------------------------------------------------------------
-- 6. 工作空间字段：所有主体表加 workspace_id；旧数据回填到 ws_default
-- ---------------------------------------------------------------------------

ALTER TABLE ontology_model      ADD COLUMN IF NOT EXISTS workspace_id VARCHAR(64);
ALTER TABLE scenario            ADD COLUMN IF NOT EXISTS workspace_id VARCHAR(64);
ALTER TABLE conversation        ADD COLUMN IF NOT EXISTS workspace_id VARCHAR(64);
ALTER TABLE graph_template      ADD COLUMN IF NOT EXISTS workspace_id VARCHAR(64);
ALTER TABLE hypothesis_template ADD COLUMN IF NOT EXISTS workspace_id VARCHAR(64);

UPDATE ontology_model      SET workspace_id = 'ws_default' WHERE workspace_id IS NULL;
UPDATE scenario            SET workspace_id = 'ws_default' WHERE workspace_id IS NULL;
UPDATE conversation        SET workspace_id = 'ws_default' WHERE workspace_id IS NULL;
UPDATE graph_template      SET workspace_id = 'ws_default' WHERE workspace_id IS NULL;
UPDATE hypothesis_template SET workspace_id = 'ws_default' WHERE workspace_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_ontology_model_ws      ON ontology_model      (workspace_id);
CREATE INDEX IF NOT EXISTS idx_scenario_ws            ON scenario            (workspace_id);
CREATE INDEX IF NOT EXISTS idx_conversation_ws        ON conversation        (workspace_id);
CREATE INDEX IF NOT EXISTS idx_graph_template_ws      ON graph_template      (workspace_id);
CREATE INDEX IF NOT EXISTS idx_hypothesis_template_ws ON hypothesis_template (workspace_id);

-- ---------------------------------------------------------------------------
-- 7. 数据源向量索引
-- ---------------------------------------------------------------------------

-- 7.1 数据源新增字段：向量索引状态
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS index_status VARCHAR(16) DEFAULT 'none';

-- ---------------------------------------------------------------------------
-- 7.1b 数据源文件夹：工作空间内任意层级归类（parent_id 自引用，NULL=根）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS data_source_folder (
    id            VARCHAR(64)  PRIMARY KEY,
    workspace_id  VARCHAR(64)  NOT NULL,
    parent_id     VARCHAR(64),                       -- NULL = 工作空间根目录
    name          VARCHAR(255) NOT NULL,
    sort_order    INTEGER      DEFAULT 0,
    created_at    BIGINT       NOT NULL,
    updated_at    BIGINT
);
CREATE INDEX IF NOT EXISTS idx_ds_folder_ws
    ON data_source_folder (workspace_id, parent_id);
-- 数据源所属文件夹（NULL = 工作空间根，不在任何文件夹内）
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS folder_id VARCHAR(64);

-- 7.2 文本块表
CREATE TABLE IF NOT EXISTS ds_chunk (
    id              VARCHAR(64) PRIMARY KEY,
    data_source_id  VARCHAR(64) NOT NULL REFERENCES data_source(id) ON DELETE CASCADE,
    workspace_id    VARCHAR(64) NOT NULL,
    chunk_index     INT         NOT NULL,
    content         TEXT        NOT NULL,
    token_count     INT         DEFAULT 0,
    created_at      BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ds_chunk_ds ON ds_chunk (data_source_id);
CREATE INDEX IF NOT EXISTS idx_ds_chunk_ws ON ds_chunk (workspace_id);

-- 7.3 向量嵌入表
CREATE TABLE IF NOT EXISTS ds_embedding (
    id          VARCHAR(64) PRIMARY KEY,
    chunk_id    VARCHAR(64) NOT NULL REFERENCES ds_chunk(id) ON DELETE CASCADE,
    embedding   TEXT        NOT NULL,
    model_name  VARCHAR(128),
    dimension   INT         DEFAULT 0,
    created_at  BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ds_embedding_chunk ON ds_embedding (chunk_id);

-- ===========================================================================
-- 初始化完成
-- ===========================================================================
