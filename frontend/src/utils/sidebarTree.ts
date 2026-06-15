/**
 * 侧边栏文件夹树的纯算法工具：子树收集 / 文件夹+叶子节点扁平化 / 「移动到…」目标计算。
 * <p>从 WorkspaceNode.vue 抽出，与 Vue 响应式无关、可独立测试。数据源树与经验库树共用同一套逻辑。
 */

/** 文件夹最小形状：有 id 与可选 parentId 即可参与建树。 */
export interface TreeFolder {
  id: string;
  name: string;
  parentId?: string;
}

/** 扁平化后的一行：文件夹或叶子（数据源 / 经验），带缩进深度。 */
export interface FlatRow<F extends TreeFolder, L> {
  kind: 'folder' | 'leaf';
  depth: number;
  folder?: F;
  leaf?: L;
}

const ROOT = '__root__';

/** 把某文件夹及其全部子孙 id 收集起来（移动文件夹时从候选目标里排除，避免成环）。 */
export function subtreeIds(rootId: string, folders: { id: string; parentId?: string }[]): Set<string> {
  const out = new Set<string>([rootId]);
  let changed = true;
  while (changed) {
    changed = false;
    for (const f of folders) {
      if (f.parentId && out.has(f.parentId) && !out.has(f.id)) { out.add(f.id); changed = true; }
    }
  }
  return out;
}

/**
 * 文件夹（按 parentId 任意层级）+ 叶子（按 folderId）扁平成带 depth 的行。
 * 折叠的文件夹（不在 openIds 中）不展开其子节点；根目录下的叶子追加在末尾。
 */
export function flattenFolderTree<F extends TreeFolder, L>(
  folders: F[],
  leaves: L[],
  leafFolderId: (leaf: L) => string | null | undefined,
  openIds: Set<string>,
): FlatRow<F, L>[] {
  const byParent = new Map<string, F[]>();
  for (const f of folders) {
    const k = f.parentId || ROOT;
    if (!byParent.has(k)) byParent.set(k, []);
    byParent.get(k)!.push(f);
  }
  const leafByFolder = new Map<string, L[]>();
  for (const l of leaves) {
    const k = leafFolderId(l) || ROOT;
    if (!leafByFolder.has(k)) leafByFolder.set(k, []);
    leafByFolder.get(k)!.push(l);
  }
  const rows: FlatRow<F, L>[] = [];
  const walk = (parentKey: string, depth: number) => {
    for (const f of (byParent.get(parentKey) ?? [])) {
      rows.push({ kind: 'folder', depth, folder: f });
      if (openIds.has(f.id)) {
        walk(f.id, depth + 1);
        for (const l of (leafByFolder.get(f.id) ?? [])) rows.push({ kind: 'leaf', depth: depth + 1, leaf: l });
      }
    }
  };
  walk(ROOT, 0);
  for (const l of (leafByFolder.get(ROOT) ?? [])) rows.push({ kind: 'leaf', depth: 0, leaf: l });
  return rows;
}

/**
 * 「移动到…」二级菜单的候选目标：所有文件夹按层级展开（带 depth）。
 * excludeId 非空时（移动文件夹自身），排除其子树以避免成环。
 */
export function buildMoveTargets<F extends TreeFolder>(
  folders: F[],
  excludeId?: string | null,
): { id: string; name: string; depth: number }[] {
  const exclude = excludeId ? subtreeIds(excludeId, folders) : new Set<string>();
  const byParent = new Map<string, F[]>();
  for (const f of folders) {
    const k = f.parentId || ROOT;
    if (!byParent.has(k)) byParent.set(k, []);
    byParent.get(k)!.push(f);
  }
  const out: { id: string; name: string; depth: number }[] = [];
  const walk = (parentKey: string, depth: number) => {
    for (const f of (byParent.get(parentKey) ?? [])) {
      if (!exclude.has(f.id)) out.push({ id: f.id, name: f.name, depth });
      walk(f.id, depth + 1);
    }
  };
  walk(ROOT, 0);
  return out;
}
