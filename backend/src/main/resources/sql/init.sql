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
    confidence              DOUBLE PRECISION,
    evidence                TEXT,                                        -- 该节点的证据(≤30字引文/出处)，血缘可审计
    PRIMARY KEY (model_id, id)
);
ALTER TABLE ontology_node
    ADD COLUMN IF NOT EXISTS derived_tables_json TEXT,
    ADD COLUMN IF NOT EXISTS attributes_json TEXT,
    ADD COLUMN IF NOT EXISTS constraints_json TEXT,
    ADD COLUMN IF NOT EXISTS derived_source VARCHAR(255),
    ADD COLUMN IF NOT EXISTS derived_database VARCHAR(255),
    ADD COLUMN IF NOT EXISTS evidence TEXT,
    ADD COLUMN IF NOT EXISTS derived_sources_json TEXT;  -- 多数据源血缘：[{source,database,tables[]}]，单值 derived_source 仅保留首个来源作兼容
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
    ADD COLUMN IF NOT EXISTS derived_database VARCHAR(255),
    ADD COLUMN IF NOT EXISTS rel_type   VARCHAR(64),   -- 边语义类型(derived_from/composed_of/triggers/governs…)，上下游遍历据此判方向
    ADD COLUMN IF NOT EXISTS evidence   TEXT,           -- 该边的证据(FK 列/视图名/命名依据等，粒度尽量细)
    ADD COLUMN IF NOT EXISTS confidence DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS derived_sources_json TEXT; -- 多数据源血缘：[{source,database,tables[]}]
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
    confidence              DOUBLE PRECISION,
    evidence                TEXT,
    PRIMARY KEY (version_id, node_id)
);
ALTER TABLE ontology_version_node
    ADD COLUMN IF NOT EXISTS derived_tables_json TEXT,
    ADD COLUMN IF NOT EXISTS attributes_json TEXT,
    ADD COLUMN IF NOT EXISTS constraints_json TEXT,
    ADD COLUMN IF NOT EXISTS derived_source VARCHAR(255),
    ADD COLUMN IF NOT EXISTS derived_database VARCHAR(255),
    ADD COLUMN IF NOT EXISTS evidence TEXT,
    ADD COLUMN IF NOT EXISTS derived_sources_json TEXT;

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
    rel_type       VARCHAR(64),
    evidence       TEXT,
    confidence     DOUBLE PRECISION,
    PRIMARY KEY (version_id, edge_id)
);
ALTER TABLE ontology_version_edge
    ADD COLUMN IF NOT EXISTS derived_tables_json TEXT,
    ADD COLUMN IF NOT EXISTS constraints_json TEXT,
    ADD COLUMN IF NOT EXISTS derived_source VARCHAR(255),
    ADD COLUMN IF NOT EXISTS derived_database VARCHAR(255),
    ADD COLUMN IF NOT EXISTS rel_type VARCHAR(64),
    ADD COLUMN IF NOT EXISTS evidence TEXT,
    ADD COLUMN IF NOT EXISTS confidence DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS derived_sources_json TEXT;

-- ---------------------------------------------------------------------------
-- 4. 对话历史
-- ---------------------------------------------------------------------------

-- 会话元信息
CREATE TABLE IF NOT EXISTS conversation (
    id           VARCHAR(64)  PRIMARY KEY,
    title        VARCHAR(255),
    model_id     VARCHAR(64),                                           -- 绑定的本体血缘图 id（一会话一图）
    created_at   BIGINT       NOT NULL,
    updated_at   BIGINT       NOT NULL
);
-- 老库轻量迁移：早期 conversation 表无 model_id 列
ALTER TABLE conversation
    ADD COLUMN IF NOT EXISTS model_id VARCHAR(64);
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
ALTER TABLE conversation        ADD COLUMN IF NOT EXISTS workspace_id VARCHAR(64);
ALTER TABLE graph_template      ADD COLUMN IF NOT EXISTS workspace_id VARCHAR(64);

UPDATE ontology_model      SET workspace_id = 'ws_default' WHERE workspace_id IS NULL;
UPDATE conversation        SET workspace_id = 'ws_default' WHERE workspace_id IS NULL;
UPDATE graph_template      SET workspace_id = 'ws_default' WHERE workspace_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_ontology_model_ws      ON ontology_model      (workspace_id);
CREATE INDEX IF NOT EXISTS idx_conversation_ws        ON conversation        (workspace_id);
CREATE INDEX IF NOT EXISTS idx_graph_template_ws      ON graph_template      (workspace_id);

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

-- ---------------------------------------------------------------------------
-- 8. 经验库：工作空间下的经验文档库（与数据源 / 历史记录同级别）
--    每条 = 一篇经验文档（标题 + 正文 + 标签），按工作空间隔离。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS experience (
    id            VARCHAR(64)  PRIMARY KEY,
    workspace_id  VARCHAR(64)  NOT NULL,
    title         VARCHAR(512) NOT NULL,
    content       TEXT,
    tags          VARCHAR(1024),                                  -- 逗号分隔的标签
    created_at    BIGINT       NOT NULL,
    updated_at    BIGINT
);
CREATE INDEX IF NOT EXISTS idx_experience_ws_created
    ON experience (workspace_id, created_at DESC);

-- 8.0b 经验库文件夹：工作空间内任意层级归类（parent_id 自引用，NULL=根），与数据源文件夹平行
CREATE TABLE IF NOT EXISTS experience_folder (
    id            VARCHAR(64)  PRIMARY KEY,
    workspace_id  VARCHAR(64)  NOT NULL,
    parent_id     VARCHAR(64),                       -- NULL = 工作空间根目录
    name          VARCHAR(255) NOT NULL,
    sort_order    INTEGER      DEFAULT 0,
    created_at    BIGINT       NOT NULL,
    updated_at    BIGINT
);
CREATE INDEX IF NOT EXISTS idx_exp_folder_ws
    ON experience_folder (workspace_id, parent_id);
-- 经验所属文件夹（NULL = 工作空间根，不在任何文件夹内）
ALTER TABLE experience ADD COLUMN IF NOT EXISTS folder_id VARCHAR(64);

-- 8.1 经验库向量索引：状态字段 + 文本块 + 向量（与数据源 ds_chunk/ds_embedding 平行）
ALTER TABLE experience ADD COLUMN IF NOT EXISTS index_status VARCHAR(16) DEFAULT 'none';

-- 8.1.1 经验来源 + 上传原始文件归档（上传文件可在经验库预览原件）
--   origin: manual（手写）| upload（上传文件）| ddl（数据源结构导出供血）
ALTER TABLE experience ADD COLUMN IF NOT EXISTS origin       VARCHAR(16)  DEFAULT 'manual';
ALTER TABLE experience ADD COLUMN IF NOT EXISTS file_name    VARCHAR(512);
ALTER TABLE experience ADD COLUMN IF NOT EXISTS file_mime    VARCHAR(128);
ALTER TABLE experience ADD COLUMN IF NOT EXISTS file_size    BIGINT;
ALTER TABLE experience ADD COLUMN IF NOT EXISTS storage_path VARCHAR(1024);

-- 8.1.2 接入 Web 系统：origin=websystem 的经验条目，保存自动探索的连接配置
--   （入口地址 / 账号 / 密码 / 最多步数 / 只读 / storageState，JSON 存于 source_config）。
--   在条目上点「探索」即按此配置运行自动探索，每次生成一篇 origin=explore 的业务说明经验。
ALTER TABLE experience ADD COLUMN IF NOT EXISTS source_config TEXT;

CREATE TABLE IF NOT EXISTS exp_chunk (
    id             VARCHAR(64) PRIMARY KEY,
    experience_id  VARCHAR(64) NOT NULL REFERENCES experience(id) ON DELETE CASCADE,
    workspace_id   VARCHAR(64) NOT NULL,
    chunk_index    INT         NOT NULL,
    content        TEXT        NOT NULL,
    token_count    INT         DEFAULT 0,
    created_at     BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_exp_chunk_exp ON exp_chunk (experience_id);
CREATE INDEX IF NOT EXISTS idx_exp_chunk_ws  ON exp_chunk (workspace_id);
-- 块内容哈希：保存经验时按 hash 复用未变化块的向量，只重算新增/改动块（Cursor 同款增量）
ALTER TABLE exp_chunk ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_exp_chunk_hash ON exp_chunk (experience_id, content_hash);

CREATE TABLE IF NOT EXISTS exp_embedding (
    id          VARCHAR(64) PRIMARY KEY,
    chunk_id    VARCHAR(64) NOT NULL REFERENCES exp_chunk(id) ON DELETE CASCADE,
    embedding   TEXT        NOT NULL,
    model_name  VARCHAR(128),
    dimension   INT         DEFAULT 0,
    created_at  BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_exp_embedding_chunk ON exp_embedding (chunk_id);

-- 8.2 节点数据供血绑定：把「已建好的本体血缘图节点」绑定到数据源的表/列，运行时取数供血。
--   一个节点可有多条绑定（不同数据源/表）；column_map 把表列映射到节点属性，便于解读取数结果。
CREATE TABLE IF NOT EXISTS node_data_binding (
    id             VARCHAR(64)  PRIMARY KEY,
    workspace_id   VARCHAR(64)  NOT NULL,
    model_id       VARCHAR(64)  NOT NULL,
    node_id        VARCHAR(128) NOT NULL,
    data_source_id VARCHAR(64)  NOT NULL,
    table_name     VARCHAR(256),
    column_map     TEXT,                       -- JSON: [{"column":"..","attribute":".."}]
    filter_sql     VARCHAR(1024),              -- 可选只读 WHERE 片段（不含 where 关键字）
    created_at     BIGINT       NOT NULL,
    updated_at     BIGINT
);
CREATE INDEX IF NOT EXISTS idx_ndb_model_node ON node_data_binding (model_id, node_id);
CREATE INDEX IF NOT EXISTS idx_ndb_ws ON node_data_binding (workspace_id);

-- ---------------------------------------------------------------------------
-- 8.4 建图来源记录：某模型由「哪些经验的哪个内容版本」构建。
--     增量建图据此跳过内容未变化的经验（海量经验时只抽新增/变更部分）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS model_build_source (
    model_id       VARCHAR(64)  NOT NULL REFERENCES ontology_model(id) ON DELETE CASCADE,
    experience_id  VARCHAR(64)  NOT NULL,
    content_hash   VARCHAR(64)  NOT NULL,                    -- 经验(标题+正文)的 SHA-256
    built_at       BIGINT       NOT NULL,
    PRIMARY KEY (model_id, experience_id)
);

-- 本体词表骨架（Schema-First 建图的规约产物）：每工作空间一份受控词表
-- {vocab:[{canonical,type,aliases[]}]}。批抽取时作为命名规约注入，消除跨批命名漂移；
-- 增量建图直接复用，全量建图重建并覆盖。
CREATE TABLE IF NOT EXISTS workspace_vocab (
    workspace_id  VARCHAR(64)  NOT NULL PRIMARY KEY REFERENCES workspace(id) ON DELETE CASCADE,
    vocab_json    TEXT         NOT NULL,
    source_count  INTEGER,                                   -- 构建该词表时采样的经验篇数
    updated_at    BIGINT       NOT NULL
);

-- ---------------------------------------------------------------------------
-- 8.5 推演功能移除（老库清理）：平台聚焦业务血缘图构建，推演分支相关表与字段一并下线
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS scenario_node_explanation;
DROP TABLE IF EXISTS scenario_constraint;
DROP TABLE IF EXISTS scenario_chain_step;
DROP TABLE IF EXISTS scenario_edge;
DROP TABLE IF EXISTS scenario_node;
DROP TABLE IF EXISTS scenario_seed;
DROP TABLE IF EXISTS scenario;
DROP TABLE IF EXISTS hypothesis_template;
ALTER TABLE ontology_node
    DROP COLUMN IF EXISTS predicted_step,
    DROP COLUMN IF EXISTS predicted_intent,
    DROP COLUMN IF EXISTS effective_probability,
    DROP COLUMN IF EXISTS explanation;
ALTER TABLE ontology_version_node
    DROP COLUMN IF EXISTS predicted_step,
    DROP COLUMN IF EXISTS predicted_intent,
    DROP COLUMN IF EXISTS effective_probability,
    DROP COLUMN IF EXISTS explanation;

-- ---------------------------------------------------------------------------
-- 9. 公共库 + 工作空间引用（reference）
--    经验库 / 数据源均为「全局公共资源」：新增时只进公共库，不再自动归属任何工作空间。
--    工作空间通过「引用（reference）」把公共库里的经验/数据源纳入自己的侧栏树；
--    每条引用记录归类文件夹（folder_id，按引用所在工作空间各自归类，互不影响）。
-- ---------------------------------------------------------------------------

-- 9.1 经验库引用：工作空间 → 经验（多对多）。folder_id = 该工作空间内的归类文件夹（NULL=根）。
CREATE TABLE IF NOT EXISTS experience_ref (
    id            VARCHAR(160) PRIMARY KEY,
    workspace_id  VARCHAR(64)  NOT NULL,
    experience_id VARCHAR(64)  NOT NULL,
    folder_id     VARCHAR(64),
    created_at    BIGINT       NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_experience_ref ON experience_ref (workspace_id, experience_id);
CREATE INDEX IF NOT EXISTS idx_experience_ref_ws ON experience_ref (workspace_id);
-- experience_id 不是上面复合唯一索引的前导列，删除经验时 deleteByExperience() 按 experience_id
-- 单独过滤会退化成全表扫描；单列索引让这条随经验库增长愈发频繁的删除路径吃到索引。
CREATE INDEX IF NOT EXISTS idx_experience_ref_exp ON experience_ref (experience_id);

-- 9.2 数据源引用：工作空间 → 数据源（多对多）。folder_id 同上。
CREATE TABLE IF NOT EXISTS data_source_ref (
    id             VARCHAR(160) PRIMARY KEY,
    workspace_id   VARCHAR(64)  NOT NULL,
    data_source_id VARCHAR(64)  NOT NULL,
    folder_id      VARCHAR(64),
    created_at     BIGINT       NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_data_source_ref ON data_source_ref (workspace_id, data_source_id);
CREATE INDEX IF NOT EXISTS idx_data_source_ref_ws ON data_source_ref (workspace_id);
-- 同上：data_source_id 单列索引，配合 deleteByDataSource() 的按数据源删除引用路径。
CREATE INDEX IF NOT EXISTS idx_data_source_ref_ds ON data_source_ref (data_source_id);

-- 9.3 一次性迁移标记表：让回填脚本只在首次升级时执行一次（init.sql 每次启动都跑，需幂等且不重复回填）。
CREATE TABLE IF NOT EXISTS schema_migration (
    mig_key     VARCHAR(128) PRIMARY KEY,
    applied_at  BIGINT       NOT NULL
);

-- 9.4 一次性回填：升级前已存在的经验/数据源，按其原归属工作空间 + 文件夹各补一条引用，
--     保证升级后这些条目仍出现在原工作空间侧栏（不丢可见性）。新建条目此后不再自动回填。
INSERT INTO experience_ref (id, workspace_id, experience_id, folder_id, created_at)
SELECT 'expref_' || e.id || '_' || e.workspace_id, e.workspace_id, e.id, e.folder_id, e.created_at
FROM experience e
WHERE e.workspace_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM schema_migration m WHERE m.mig_key = 'backfill_refs_v1')
ON CONFLICT (workspace_id, experience_id) DO NOTHING;

INSERT INTO data_source_ref (id, workspace_id, data_source_id, folder_id, created_at)
SELECT 'dsref_' || d.id || '_' || d.workspace_id, d.workspace_id, d.id, d.folder_id, d.created_at
FROM data_source d
WHERE d.workspace_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM schema_migration m WHERE m.mig_key = 'backfill_refs_v1')
ON CONFLICT (workspace_id, data_source_id) DO NOTHING;

INSERT INTO schema_migration (mig_key, applied_at)
SELECT 'backfill_refs_v1', (extract(epoch from now()) * 1000)::bigint
WHERE NOT EXISTS (SELECT 1 FROM schema_migration m WHERE m.mig_key = 'backfill_refs_v1');

-- ===========================================================================
-- 初始化完成
-- ===========================================================================
