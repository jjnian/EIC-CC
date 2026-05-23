// 静态本体层 (TBox / 世界的家具与规则)
//   class         : 概念/类节点          —— 范畴层级 (坦克、国家、谈判)
//   relation_type : 关系类型定义         —— 隶属/敌对/依赖的合法性声明 + 定义域/值域
//   attribute     : 属性定义与值空间     —— 重量/士气/GDP 增速 + 取值范围
//   constraint    : 约束                 —— 基数限制 / 互斥
export const NT = {
  class:         {color:'#3d9bff',bg:'#071d3a',label:'概念/类'},
  relation_type: {color:'#22dd88',bg:'#002418',label:'关系类型'},
  attribute:     {color:'#ffaa22',bg:'#221500',label:'属性'},
  constraint:    {color:'#ff3399',bg:'#2a0515',label:'约束'},
};

export const NW = 172;
export const NH = 44;

export const INIT_NODES = [
  {id:'n1',label:'国家',type:'class',x:130,y:330},
  {id:'n2',label:'坦克',type:'class',x:360,y:330},
  {id:'n3',label:'谈判',type:'class',x:610,y:330},
  {id:'n4',label:'敌对',type:'relation_type',x:360,y:170},
  {id:'n5',label:'隶属',type:'relation_type',x:130,y:170},
  {id:'n6',label:'GDP 增速',type:'attribute',x:130,y:490},
  {id:'n7',label:'士气',type:'attribute',x:360,y:490},
  {id:'n8',label:'互斥:结盟⊕交战',type:'constraint',x:610,y:170},
];

export const INIT_EDGES = [
  {id:'e1',from:'n5',to:'n1',label:'定义域'},
  {id:'e2',from:'n4',to:'n1',label:'定义域/值域'},
  {id:'e3',from:'n6',to:'n1',label:'属于'},
  {id:'e4',from:'n7',to:'n2',label:'属于'},
  {id:'e5',from:'n8',to:'n4',label:'约束'},
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
