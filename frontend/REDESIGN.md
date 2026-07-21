# 前端重构说明（feature/redesign-clean-ui）

本分支对前端做了**彻底重设计**：放弃原先的单文件巨型 `App.vue` + 101 个组件的架构，
重写为一套**干净、整洁、友好**的现代 SPA。技术栈不变：**Vue 3 + Vite + TypeScript + Tailwind CSS**。

## 新架构

```
src/
├── main.ts                 # 入口：Pinia + Router + 全局样式
├── api/                    # 后端契约层（未改动，原样复用）
├── types.ts / constants.ts # 领域类型（未改动）
└── app/                    # ✨ 全新 UI 代码
    ├── App.vue             # 根组件（仅 router-view + Toast）
    ├── router.ts           # vue-router：工作台/图谱/经验库/对话/数据源/分析/设置
    ├── stores/             # Pinia：workspace（工作空间）、toast（全局通知）
    ├── styles/main.css     # 设计令牌：btn / card / input / badge 等统一类
    ├── lib/                # 格式化、Markdown 渲染、图谱分层布局、节点视觉编码
    ├── layouts/AppLayout.vue
    ├── components/         # 9 个精简组件（侧边栏、顶栏、画布、抽屉、弹窗…）
    └── views/              # 8 个页面，各自独立、按路由懒加载
```

## 设计原则

- **干净**：浅色中性底（slate-100）+ 白色卡片 + 单一靛蓝主色，无渐变堆砌、无玻璃拟态。
- **整洁**：左侧固定导航 + 顶栏（页面标题 + 工作空间切换），信息层级清晰，间距统一。
- **友好**：
  - 每个空状态都有引导文案 + 下一步按钮；
  - 建图 / 联网调研等长任务用 SSE 步骤流实时反馈；
  - 图谱支持搜索高亮、一键上/下游追溯、拖拽调整布局并保存；
  - 全局限流 Toast 反馈成功 / 失败。

## 页面一览

| 路由 | 页面 | 说明 |
| --- | --- | --- |
| `/workspaces` | 工作空间 | 卡片式选择 / 新建 / 删除 |
| `/` | 工作台 | 统计概览 + 快速操作 + 最近模型 |
| `/graph` | 血缘图谱 | SVG 画布（缩放/平移/拖拽/搜索/追溯/保存布局） |
| `/experiences` | 经验库 | 领域文件夹 + 新建/导入/联网调研/一键建图 |
| `/chat` | AI 对话 | 流式对话、步骤提示、澄清问题选项 |
| `/datasources` | 数据源 | 库表/HTTP 接入、连接测试、表预览 |
| `/analysis` | 血缘分析 | 概览统计 / 血缘追溯 / 结构体检（孤立点、重复点、矛盾、环、缺证据边） |
| `/settings` | 设置 | LLM 模型测试、图谱偏好 |

## 旧代码的处理

旧 UI 文件（`src/components/**`、`src/composables/**`、`src/utils/**`、`src/lib/**`）暂未删除，
但**已从 `tsconfig.json` 的 include 中排除，且不被任何新代码引用**——构建与类型检查均不受影响。
待新 UI 确认后，可一次性物理删除这些目录。

## 开发

```bash
cd frontend
npm install
npm run dev      # 代理 /api → http://localhost:8000
npm run build    # 产物 dist/
npm run lint     # tsc --noEmit（仅检查新代码与 api 层）
```
