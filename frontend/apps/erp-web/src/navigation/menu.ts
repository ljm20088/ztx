import type { Component } from 'vue'
import {
  BarChart3,
  Boxes,
  Calculator,
  ClipboardCheck,
  Factory,
  Handshake,
  House,
  Settings,
  ShoppingCart,
  ShieldCheck,
} from 'lucide-vue-next'

export interface MenuFeature { id:string; label:string; path:string; description:string }
export interface MenuCategory { id:string; label:string; features:MenuFeature[] }
export interface MenuModule { id:string; label:string; icon:Component; categories:MenuCategory[] }

const feature = (id:string, label:string, path:string, description:string):MenuFeature =>
  ({ id, label, path, description })

export const menuModules:MenuModule[] = [
  {
    id:'workspace', label:'工作台', icon:House, categories:[
      {id:'personal',label:'我的工作',features:[
        feature('home','工作台首页','/workspace','查看工作概况与常用入口'),
        feature('todo','我的待办','/workspace/todo','集中处理待办工作'),
        feature('recent','最近访问','/workspace/recent','快速返回最近使用的功能'),
      ]},
      {id:'preferences',label:'个人设置',features:[
        feature('profile','个人资料','/workspace/profile','维护个人资料与显示偏好'),
        feature('security','账户安全','/workspace/security','管理个人登录安全选项'),
      ]},
    ],
  },
  {
    id:'purchase', label:'采购管理', icon:ShoppingCart, categories:[
      {id:'purchase-work',label:'日常业务',features:[
        feature('purchase-requests','采购申请','/purchase/requests','采购需求与申请管理'),
        feature('purchase-orders','采购订单','/purchase/orders','采购订单管理'),
        feature('receipts','采购收货','/purchase/receipts','采购到货与收货管理'),
      ]},
      {id:'purchase-files',label:'基础档案',features:[
        feature('suppliers','供应商档案','/purchase/suppliers','供应商基础信息'),
        feature('purchase-items','采购物料','/purchase/items','采购物料与供应关系'),
      ]},
      {id:'purchase-analysis',label:'查询与分析',features:[
        feature('purchase-query','采购单据查询','/purchase/query','查询采购业务单据'),
        feature('purchase-reports','采购报表','/purchase/reports','采购业务统计与分析'),
      ]},
    ],
  },
  {
    id:'sales', label:'销售管理', icon:Handshake, categories:[
      {id:'sales-work',label:'日常业务',features:[
        feature('quotations','销售报价','/sales/quotations','客户报价管理'),
        feature('sales-orders','销售订单','/sales/orders','销售订单管理'),
        feature('shipments','销售发货','/sales/shipments','发货与交付管理'),
      ]},
      {id:'sales-files',label:'基础档案',features:[
        feature('customers','客户档案','/sales/customers','客户基础信息'),
        feature('sales-items','销售产品','/sales/items','销售产品与客户关系'),
      ]},
      {id:'sales-analysis',label:'查询与分析',features:[
        feature('sales-query','销售单据查询','/sales/query','查询销售业务单据'),
        feature('sales-reports','销售报表','/sales/reports','销售业务统计与分析'),
      ]},
    ],
  },
  {
    id:'inventory', label:'库存管理', icon:Boxes, categories:[
      {id:'inventory-work',label:'日常业务',features:[
        feature('stock-in','其他入库','/inventory/receipts','库存入库业务'),
        feature('stock-out','其他出库','/inventory/issues','库存出库业务'),
        feature('transfers','库存调拨','/inventory/transfers','仓库与库位间调拨'),
        feature('stock-count','库存盘点','/inventory/counts','库存盘点与差异处理'),
      ]},
      {id:'inventory-files',label:'库存档案',features:[
        feature('warehouses','仓库管理','/inventory/warehouses','仓库与库位档案'),
        feature('items','物料档案','/inventory/items','物料基础信息'),
      ]},
      {id:'inventory-analysis',label:'查询与分析',features:[
        feature('stock-query','库存查询','/inventory/query','库存余额与明细查询'),
        feature('stock-reports','库存报表','/inventory/reports','库存统计与分析'),
      ]},
    ],
  },
  {
    id:'production', label:'生产管理', icon:Factory, categories:[
      {id:'production-work',label:'生产业务',features:[
        feature('work-orders','生产工单','/production/orders','生产任务与工单管理'),
        feature('materials','生产领料','/production/materials','生产用料与领退料'),
        feature('reporting','生产报工','/production/reporting','工序与生产进度反馈'),
      ]},
      {id:'production-files',label:'生产基础',features:[
        feature('bom','物料清单','/production/bom','产品结构与物料清单'),
        feature('routing','工艺路线','/production/routing','工艺与工序定义'),
      ]},
      {id:'production-analysis',label:'查询与分析',features:[
        feature('production-query','生产进度查询','/production/query','生产任务进度查询'),
        feature('production-reports','生产报表','/production/reports','生产效率与产量分析'),
      ]},
    ],
  },
  {
    id:'finance', label:'财务管理', icon:Calculator, categories:[
      {id:'finance-work',label:'日常业务',features:[
        feature('receivables','应收管理','/finance/receivables','客户应收与收款管理'),
        feature('payables','应付管理','/finance/payables','供应商应付与付款管理'),
        feature('cash','资金管理','/finance/cash','资金与账户管理'),
      ]},
      {id:'finance-files',label:'财务基础',features:[
        feature('accounts','会计科目','/finance/accounts','会计科目与核算维度'),
        feature('books','账簿管理','/finance/books','账簿与会计期间'),
      ]},
      {id:'finance-analysis',label:'查询与分析',features:[
        feature('finance-query','财务查询','/finance/query','财务数据查询'),
        feature('finance-reports','财务报表','/finance/reports','财务报表与经营分析'),
      ]},
    ],
  },
  {
    id:'quality', label:'质量管理', icon:ClipboardCheck, categories:[
      {id:'quality-work',label:'质量业务',features:[
        feature('inspections','质量检验','/quality/inspections','来料、过程与成品检验'),
        feature('nonconformance','不合格处理','/quality/nonconformance','不合格品评审与处理'),
      ]},
      {id:'quality-analysis',label:'查询与分析',features:[
        feature('quality-query','质量追溯','/quality/query','质量记录与追溯查询'),
        feature('quality-reports','质量报表','/quality/reports','质量指标与趋势分析'),
      ]},
    ],
  },
  {
    id:'reports', label:'报表分析', icon:BarChart3, categories:[
      {id:'business-analysis',label:'经营分析',features:[
        feature('overview','经营概览','/reports/overview','跨业务模块经营指标'),
        feature('custom-reports','自定义报表','/reports/custom','配置和查看自定义报表'),
      ]},
    ],
  },
  {
    id:'management', label:'管理中心', icon:Settings, categories:[
      {id:'organization',label:'企业与组织',features:[
        feature('companies','企业信息','/enterprise/companies','维护当前租户企业信息'),
        feature('books','账套管理','/enterprise/books','维护企业账套与启用范围'),
        feature('departments','组织架构','/enterprise/departments','维护部门、工厂与组织层级'),
        feature('employees','员工档案','/enterprise/employees','维护员工基础信息'),
        feature('users','用户账号','/enterprise/users','维护独立用户账号'),
        feature('employments','员工任职','/enterprise/employments','维护企业任职期限、组织范围与岗位'),
        feature('group-master','集团基础档案','/enterprise/group-master','维护集团统一档案与企业启用范围'),
        feature('group-master-usage','企业集团档案','/enterprise/group-master-usage','查看企业已启用的集团档案及本地补充'),
      ]},
      {id:'permissions',label:'用户与权限',features:[
        feature('positions','岗位管理','/enterprise/positions','维护岗位与模块角色组合'),
        feature('roles','角色权限','/enterprise/roles','维护功能、数据和字段权限'),
      ]},
      {id:'configuration',label:'系统配置',features:[
        feature('appearance','界面偏好','/system/appearance','调整主题与界面密度'),
        feature('notifications','通知设置','/system/notifications','设置站内通知偏好'),
        feature('global-settings','企业参数','/system/settings','维护企业级系统参数'),
      ]},
      {id:'low-code',label:'低代码开发',features:[
        feature('doctypes','DocType 管理','/developer/doctypes','定义低代码业务对象'),
        feature('forms','表单设计器','/developer/forms','配置元数据表单布局'),
        feature('lists','列表设计器','/developer/lists','配置元数据列表视图'),
        feature('hooks','Hook 管理','/developer/hooks','管理租户受控扩展脚本'),
        feature('releases','发布记录','/developer/releases','查看元数据发布与版本'),
        feature('api-docs','API 文档','/developer/api-docs','查看平台 API 契约'),
      ]},
    ],
  },
]


export const platformMenuModules:MenuModule[] = [
  {
    id:'platform', label:'平台管理', icon:ShieldCheck, categories:[
      {id:'platform-operations',label:'平台运营',features:[
        feature('platform-overview','平台概览','/platform/admin','查看平台管理概况与管理员安全设置'),
        feature('platform-tenants','租户管理','/platform/tenants','创建、启用和管理租户'),
        feature('platform-operations-status','运维管理','/platform/operations','查看 Server、数据库和 Worker 运行状态'),
      ]},
      {id:'platform-security',label:'安全管理',features:[
        feature('platform-audit','安全审计','/platform/audit','查看平台管理员安全事件与操作记录'),
      ]},
    ],
  },
]

export function findFeature(path:string):MenuFeature|undefined {
  return [...menuModules,...platformMenuModules].flatMap(item=>item.categories).flatMap(item=>item.features).find(item=>item.path===path)
}
