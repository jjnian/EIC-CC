export const NT = {
  entity:  {color:'#3d9bff',bg:'#071d3a',label:'实体'},
  process: {color:'#ffaa22',bg:'#221500',label:'流程'},
  event:   {color:'#22dd88',bg:'#002418',label:'事件'},
  data:    {color:'#bb77ff',bg:'#170a2a',label:'数据源'},
  external:{color:'#ff6644',bg:'#220800',label:'外部'},
  rule:    {color:'#ff3399',bg:'#2a0515',label:'规则/条件'},
};

export const NW = 172;
export const NH = 44;

export const INIT_NODES = [
  {id:'n1',label:'供应商',type:'entity',x:130,y:330},
  {id:'n2',label:'原材料订单',type:'process',x:360,y:190},
  {id:'n3',label:'工厂',type:'entity',x:360,y:400},
  {id:'n4',label:'产品',type:'entity',x:610,y:280},
  {id:'n5',label:'配送中心',type:'process',x:860,y:170},
  {id:'n6',label:'客户',type:'entity',x:1090,y:260},
  {id:'n7',label:'订单',type:'process',x:860,y:360},
  {id:'n8',label:'仓库',type:'entity',x:610,y:470},
  {id:'n9',label:'ERP系统',type:'external',x:130,y:490},
  {id:'n10',label:'时序数据',type:'data',x:1090,y:450},
];

export const INIT_EDGES = [
  {id:'e1',from:'n1',to:'n2',label:'提供'},{id:'e2',from:'n2',to:'n3',label:'输入'},
  {id:'e3',from:'n3',to:'n4',label:'生产'},{id:'e4',from:'n4',to:'n5',label:'发货'},
  {id:'e5',from:'n5',to:'n6',label:'送达'},{id:'e6',from:'n6',to:'n7',label:'下单'},
  {id:'e7',from:'n7',to:'n5',label:'触发'},{id:'e8',from:'n3',to:'n8',label:'入库'},
  {id:'e9',from:'n8',to:'n5',label:'调拨'},{id:'e10',from:'n9',to:'n3',label:'驱动'},
  {id:'e11',from:'n7',to:'n10',label:'记录'},
];

export function getPath(fn: any, tn: any) {
  const x1 = fn.x + NW / 2;
  const y1 = fn.y + NH / 2;
  const x2 = tn.x + NW / 2;
  const y2 = tn.y + NH / 2;
  const dx = Math.abs(x2 - x1);
  const cp = Math.max(dx * 0.5, 50);
  return {
    d: `M${x1},${y1} C${x1+cp},${y1} ${x2-cp},${y2} ${x2},${y2}`,
    mx: (x1+x2)/2,
    my: (y1+y2)/2
  };
}
