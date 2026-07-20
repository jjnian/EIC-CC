// 数据源连接配置的「新建默认值」——按数据库类型给出 host/port/database 等初始值。
// 由 DataSourceCreateDialog 与 DataSourcePageView 共用，避免两处各写一份默认值产生漂移。

import type { DataSourceKind } from '../api/dataSources';

/** RDBMS 家族（mysql/pgsql/oracle/dm/gbase）共用同一套字段：host/port/database/username/password/params。 */
export interface RdbmsConfig {
  host: string;
  port: number;
  database: string;
  username: string;
  password: string;
  /** 连接参数（部分库用，如 ?useSSL=false）。gbase/mysql 用到。 */
  params?: string;
}

/** HTTPS 接口数据源的连接配置。 */
export interface HttpsApiConfig {
  url: string;
  method: string;
  headers: Record<string, string>;
  body: string;
  timeoutMs: number;
  schedule: { enabled: boolean; intervalSec: number };
}

/** 各 RDBMS 类型的默认端口（也用于表单提示/校验）。集中一处避免散落硬编码。 */
export const DEFAULT_PORTS: Partial<Record<DataSourceKind, number>> = {
  mysql: 3306,
  pgsql: 5432,
  oracle: 1521,
  dm: 5236,
  gbase: 5258,
};

const RDBMS_KINDS: DataSourceKind[] = ['mysql', 'pgsql', 'oracle', 'dm', 'gbase'];
const PARAM_KINDS: DataSourceKind[] = ['mysql', 'gbase'];

/** 判断是否为 RDBMS 家族（共用 host/port 配置形态）。 */
export function isRdbms(kind: DataSourceKind): kind is 'mysql' | 'pgsql' | 'oracle' | 'dm' | 'gbase' {
  return (RDBMS_KINDS as string[]).includes(kind);
}

/**
 * 给定数据源类型，返回该类型「新建」时的初始连接配置对象。
 * <p>RDBMS 家族返回 {@link RdbmsConfig}（mysql/gbase 带额外 params 字段）；
 * https_api 返回 {@link HttpsApiConfig}；其余（file/url 等旧类型）返回空对象。
 */
export function defaultConfigFor(kind: DataSourceKind): RdbmsConfig | HttpsApiConfig | Record<string, never> {
  if (isRdbms(kind)) {
    const base: RdbmsConfig = {
      host: 'localhost',
      port: DEFAULT_PORTS[kind] ?? 3306,
      database: '',
      username: '',
      password: '',
    };
    // mysql/gbase 带连接参数；oracle/dm 不带
    if ((PARAM_KINDS as string[]).includes(kind)) base.params = '';
    return base;
  }
  if (kind === 'https_api') {
    return {
      url: '',
      method: 'GET',
      headers: {},
      body: '',
      timeoutMs: 15000,
      schedule: { enabled: false, intervalSec: 300 },
    };
  }
  return {};
}
