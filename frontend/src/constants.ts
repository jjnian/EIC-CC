// 静态本体层 (TBox / 世界的家具与规则)
//   class         : 对象节点  —— 图上的方块
//   relation_type : 关系类型     —— 图上的边(label = 关系名)
//   attribute     : 属性定义     —— 不在图上,Schema 面板里
//   constraint    : 约束         —— 不在图上,Schema 面板里;有约束的类/边上挂 🔒
//
// NT 仍然保留 4 个类别供 Schema 面板分类着色;但 GraphCanvas 只把 `class` 节点画到画布上。
export const NT = {
  class:         {color:'#3d9bff',bg:'#071d3a',label:'对象'},
  relation_type: {color:'#22dd88',bg:'#002418',label:'关系类型'},
  attribute:     {color:'#ffaa22',bg:'#221500',label:'属性'},
  constraint:    {color:'#ff3399',bg:'#2a0515',label:'约束'},
  // 领域折叠产生的超级节点（useDomainCollapse 渲染层派生，非真实节点）
  domain:        {color:'#a78bfa',bg:'#1d1440',label:'领域'},
};

export const NW = 172;
export const NH = 44;

export const INIT_NODES = [
  {
    id:'n1', label:'国家', type:'class', x:130, y:200,
    attributes:[
      {name:'GDP增速', valueSpace:'number'},
      {name:'人口',    valueSpace:'number'},
    ],
    constraints:[
      {kind:'exclusive', note:'同一国家不能同时与同一对象既结盟又交战'},
    ],
  },
  {
    id:'n2', label:'坦克', type:'class', x:430, y:380,
    attributes:[
      {name:'重量', valueSpace:'number(吨)'},
      {name:'士气', valueSpace:'0..1'},
    ],
  },
  {
    id:'n3', label:'谈判', type:'class', x:730, y:200,
    attributes:[
      {name:'议题', valueSpace:'string'},
    ],
    constraints:[
      {kind:'cardinality', note:'参与方至少 2 个'},
    ],
  },
];

export const INIT_EDGES = [
  {
    id:'e1', from:'n1', to:'n2', label:'拥有',
    constraints:[{kind:'cardinality', note:'多对多'}],
  },
  {
    id:'e2', from:'n1', to:'n1', label:'敌对',
    constraints:[{kind:'symmetric', note:'对称'}],
  },
  {
    id:'e3', from:'n1', to:'n3', label:'参与',
  },
];

export function getPath(fn: any, tn: any) {
  const x1 = fn.x + NW / 2;
  const y1 = fn.y + NH / 2;
  const x2 = tn.x + NW / 2;
  const y2 = tn.y + NH / 2;
  const dx = Math.abs(x2 - x1);
  const dy = Math.abs(y2 - y1);
  // 主方向是横向(LR 布局)还是纵向(TB 布局)?控制点跟着主方向走,
  // 这样切换布局方向时连线弯曲方向也自然一致。
  if (dy > dx) {
    const cp = Math.max(dy * 0.5, 40);
    return {
      d: `M${x1},${y1} C${x1},${y1+cp} ${x2},${y2-cp} ${x2},${y2}`,
      mx: (x1+x2)/2,
      my: (y1+y2)/2,
    };
  }
  const cp = Math.max(dx * 0.5, 50);
  return {
    d: `M${x1},${y1} C${x1+cp},${y1} ${x2-cp},${y2} ${x2},${y2}`,
    mx: (x1+x2)/2,
    my: (y1+y2)/2,
  };
}
