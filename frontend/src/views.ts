// 视图注册表 —— 应用级「页面」的单一事实源。
//
// 把原先散落在 App.vue（view 联合类型、onNav 路由映射）与 Sidebar.vue（isActive 判断）
// 三处的硬编码知识，收敛到这一份声明式数据里。新增一个导航页面只需在 VIEWS 里加一条，
// App.vue 主体与 Sidebar 的激活态判断都无需再改。
//
// 注意：graph / chat 这两个重交互视图绑定了大量 bespoke props/events，仍由 App.vue 显式渲染，
// 不纳入「动态组件」统一渲染——强行收口只会把 App.vue 最核心的绑定墙复杂化、收益甚微。
// 本注册表聚焦于真正会频繁扩展的部分：视图标识、导航路由、激活态。

/** 全部视图标识。新增页面时在此并入联合类型即可获得类型检查。 */
export type ViewId =
  | 'workspace-picker'
  | 'list'
  | 'graph'
  | 'chat'
  | 'conv-list'
  | 'datasource'
  | 'datasource-list'
  | 'experience-list'
  | 'settings';

/** 单个视图的注册信息。 */
export interface ViewDef {
  /** 视图标识。 */
  id: ViewId;
  /**
   * 该视图被「激活」时，侧边栏哪个导航路由应高亮。
   * 例如 datasource 详情页与 datasource-list 都归属 'datasource' 导航项。
   */
  navRoute?: NavRoute;
}

/** 侧边栏顶级导航路由。 */
export type NavRoute = 'welcome' | 'graph-list' | 'conv-list' | 'datasource' | 'experience' | 'settings';

/**
 * 导航路由 → 目标视图。onNav 据此切换 view。
 * 'welcome' 是个动作（新建对话）而非静态视图，由 App.vue 单独处理，故不在此表。
 */
export const NAV_TO_VIEW: Record<Exclude<NavRoute, 'welcome'>, ViewId> = {
  'graph-list': 'list',
  'conv-list': 'conv-list',
  'datasource': 'datasource-list',
  'experience': 'experience-list',
  'settings': 'settings',
};

/**
 * 视图 → 它在侧边栏归属的导航路由。供 Sidebar 判断激活态。
 * 没列出的视图（workspace-picker / list / graph）不点亮任何顶级导航项。
 */
const VIEW_TO_NAV: Partial<Record<ViewId, NavRoute>> = {
  'chat': 'welcome',
  'datasource': 'datasource',
  'datasource-list': 'datasource',
  'experience-list': 'experience',
  'settings': 'settings',
};

/** 给定当前视图，判断某个导航路由是否应高亮。 */
export function isNavActive(route: NavRoute, currentView: ViewId): boolean {
  return VIEW_TO_NAV[currentView] === route;
}
