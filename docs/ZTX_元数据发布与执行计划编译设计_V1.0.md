# ZTX 元数据发布与执行计划编译设计

**文档版本：** V1.0\
**文档类型：** 专项技术设计\
**所属规范：** ZTX 低代码业务开发规范与运行时需求\
**状态：** 技术设计草案

------------------------------------------------------------------------

## 1. 文档目的

本文细化 ZTX 元数据运行时中的发布和执行计划编译机制。

总体需求规定了 ZTX
的开发原则：简单业务使用元数据，普通单据采用元数据加少量
Kotlin，复杂制造业务保留 Kotlin 实现；框架自动提供通用运行时能力。

本文进一步回答：

1.  元数据如何从可编辑定义变成可执行的运行时计划？
2.  发布时需要执行哪些解析、校验和编译步骤？
3.  执行计划如何被 CRUD、表单、列表和 Ability Runtime 使用？
4.  如何管理版本、缓存、原子切换和回滚？
5.  如何避免元数据变更破坏数据库、权限和正在执行的请求？

本文只设计元数据编译和发布子系统，不重新定义 Document、Ability
或整个低代码平台。

## 2. 设计目标与非目标

### 2.1 设计目标

-   元数据在发布阶段解析、标准化和校验。
-   发布阶段生成结构化执行计划，避免每次请求重复解析 YAML。
-   执行计划不可变、可版本化、可缓存。
-   发布失败时，当前有效版本保持不变。
-   运行时请求使用一致的计划版本。
-   计划可被 Document Runtime、Query Runtime、页面运行时和 Ability
    Runtime 共同使用。
-   支持依赖检查、数据库结构兼容性检查和受控迁移。
-   支持版本回滚及发布审计。
-   元数据定义与 Kotlin 业务代码保持独立构建、明确绑定。

### 2.2 非目标

第一版不做以下事情：

-   不把 YAML 动态编译成 JVM 字节码。
-   不在每次 HTTP 请求时重新编译元数据。
-   不将所有业务逻辑转成表达式或脚本。
-   不允许元数据任意执行 SQL、Shell 或 JVM 代码。
-   不让元数据编译器承担所有业务事务。
-   不通过执行计划替代复杂制造业务 Kotlin 代码。
-   不在一次元数据发布中无条件自动执行不可逆数据库变更。

第一版的"编译"是将元数据转换为经过校验的、结构化的 Kotlin
运行时对象及可持久化计划表示，不是通用程序语言编译器。

## 3. 核心概念

### 3.1 元数据定义（Definition）

管理员或开发者编辑的业务描述，例如 YAML 或管理界面生成的结构化 JSON。

它是可编辑的源定义，可能处于草稿状态，不能直接作为生产请求的执行依据。

### 3.2 标准化元数据（Normalized Metadata）

解析源定义后形成的统一内部模型。

标准化过程应：

-   统一字段默认值。
-   规范化类型名称。
-   规范化权限声明。
-   检查重复字段和非法配置。
-   将不同输入格式转换为统一表示。

### 3.3 执行计划（DocumentPlan）

编译器生成的不可变计划，包含运行时执行所需的结构信息，例如：

-   业务对象标识和版本。
-   数据库映射。
-   字段定义。
-   字段转换和校验规则。
-   关联引用。
-   权限声明。
-   表单描述。
-   列表和查询描述。
-   生命周期及动作声明。
-   对应 Kotlin 业务实现标识。
-   依赖版本和内容校验值。

### 3.4 发布版本（Release）

一次经过校验并正式对运行时生效的业务定义版本。发布记录应能追溯源定义、编译器版本、计划校验值、数据库迁移和发布人。

### 3.5 运行时快照（RuntimeSnapshot）

某一时刻运行时使用的一组一致版本的执行计划及相关绑定信息。请求开始时取得快照，并在请求生命周期内使用同一快照。

## 4. 总体处理流程

``` text
编辑元数据
    |
    v
保存草稿定义
    |
    v
解析 YAML / JSON
    |
    v
标准化元数据
    |
    v
静态校验
    |
    v
依赖关系检查
    |
    v
数据库结构兼容性检查
    |
    v
编译执行计划
    |
    v
生成发布候选版本
    |
    v
审核 / 执行必要迁移
    |
    v
构建完整 RuntimeSnapshot
    |
    v
原子切换运行时版本
```

任何必要步骤失败，都不得切换当前运行版本。发布失败应保留错误详情，当前有效版本继续服务。

## 5. 元数据源定义示例

以下为示意 YAML，实际字段规范应由 ZTX 元数据 Schema 单独冻结。

``` yaml
doctype: PurchaseOrder
version: 3
table: pur.purchase_order

fields:
  - name: order_no
    type: string
    required: true
    unique: true

  - name: supplier_id
    type: reference
    target: Supplier
    required: true

  - name: order_date
    type: date
    required: true

  - name: status
    type: select
    options:
      - DRAFT
      - SUBMITTED
      - CANCELLED

permissions:
  read:
    roles:
      - purchase_user
      - purchase_manager
  write:
    roles:
      - purchase_manager

form:
  fields:
    - order_no
    - supplier_id
    - order_date
    - status

list:
  columns:
    - order_no
    - supplier_id
    - order_date
    - status
```

源定义是声明式配置，不应包含任意可执行代码。若需要复杂校验或业务动作，应绑定受控的
Kotlin 业务实现。

## 6. 编译阶段设计

### 6.1 读取与解析

输入可以是 YAML 或 JSON。解析层负责：

-   语法解析。
-   Schema 版本识别。
-   必须字段检查。
-   生成原始元数据对象。
-   提供准确的文件路径、字段路径和错误位置。

解析器不应直接访问生产数据库或执行发布动作。

### 6.2 标准化

将源定义转换为统一的内部模型：

-   填充默认属性。
-   统一枚举和类型。
-   统一字段顺序或采用稳定排序。
-   规范化引用标识。
-   明确隐式默认值。
-   去除不影响语义的格式差异，以便生成稳定校验值。

标准化结果应是确定性的：相同语义输入在相同编译器版本下应得到相同的标准化表示。

### 6.3 静态校验

至少检查：

  类别     检查内容
  -------- --------------------------------------
  标识     DocType 名称合法、唯一
  表映射   Schema 和表名符合命名规则及白名单
  字段     字段名合法、无重复、类型受支持
  约束     required、unique、默认值等配置合法
  引用     引用目标存在、目标字段类型兼容
  权限     角色、权限动作和字段权限声明合法
  表单     表单字段均已定义
  列表     列、筛选、排序字段均已定义
  动作     动作标识唯一，绑定的业务实现存在
  安全     不允许未受控 SQL、脚本或动态代码入口

校验错误应返回机器可读错误码、业务对象、字段路径和人类可读说明。

### 6.4 依赖分析

例如 PurchaseOrder 引用 Supplier，则发布候选版本需要确认 Supplier
的有效定义存在。

依赖分析应支持：

-   引用目标检查。
-   依赖版本记录。
-   依赖图构建。
-   循环依赖识别。
-   依赖变更影响分析。

循环依赖是否允许，应按关系类型区分。普通字段引用可以允许互相引用，但编译期必须避免递归无限展开；真正需要顺序执行的依赖则应禁止环。

### 6.5 数据库结构兼容性检查

元数据定义与数据库表结构必须一致或存在经过批准的迁移方案。

检查内容包括：

-   表是否存在。
-   字段是否存在。
-   数据类型是否兼容。
-   空值约束和默认值是否兼容。
-   唯一约束及索引是否满足要求。
-   外键或引用约束是否需要创建。
-   删除或缩窄字段是否会造成数据丢失。

编译器可以生成迁移建议或迁移计划，但不应在未审核的情况下自动执行破坏性
DDL。

数据库迁移应采用独立、可审计的迁移工具和流程。

### 6.6 计划生成

校验成功后，将标准化元数据转换为执行计划。

计划只保存执行所需的信息，不应复制不必要的原始文本或引入可变业务状态。

## 7. 执行计划模型

以下是第一版建议模型，具体字段可随实现调整。

``` kotlin
data class DocumentPlan(
    val name: String,
    val version: Int,
    val table: TableRef,
    val fields: List<FieldPlan>,
    val references: List<ReferencePlan>,
    val validations: List<ValidationPlan>,
    val permissions: PermissionPlan,
    val form: FormPlan,
    val list: ListPlan,
    val actions: List<ActionPlan>,
    val implementation: ImplementationRef?,
    val dependencies: List<PlanDependency>,
    val checksum: String
)

data class TableRef(
    val schema: String,
    val table: String
)

data class FieldPlan(
    val name: String,
    val type: FieldType,
    val required: Boolean,
    val unique: Boolean,
    val reference: String? = null
)

enum class FieldType {
    STRING,
    UUID,
    DATE,
    DECIMAL,
    INTEGER,
    BOOLEAN,
    JSON
}

data class ReferencePlan(
    val field: String,
    val targetDoctype: String,
    val targetVersion: Int?
)

data class ValidationPlan(
    val field: String?,
    val rule: ValidationRule
)

enum class ValidationRule {
    REQUIRED,
    UNIQUE
}

data class PermissionPlan(
    val readRoles: Set<String>,
    val writeRoles: Set<String>
)

data class FormPlan(
    val fields: List<String>
)

data class ListPlan(
    val columns: List<String>
)

data class ActionPlan(
    val name: String,
    val implementationMethod: String,
    val permission: String?
)

data class ImplementationRef(
    val className: String,
    val artifactVersion: String
)

data class PlanDependency(
    val doctype: String,
    val version: Int
)
```

说明：

-   这是设计示例，不是已经存在的 ZTX API。
-   正式实现中，集合应使用不可变结构或在构建后封装，避免运行时修改。
-   `checksum` 应基于规范化内容和编译器版本计算。
-   计划不应只依赖业务名称；运行时需要明确版本及依赖。
-   敏感权限策略不应只靠前端使用的展示配置表达。

## 8. 编译器接口与简化实现

### 8.1 编译器职责

编译器只负责纯粹的转换和校验工作，不直接发布、不直接切换运行时、不随意执行数据库迁移。

建议接口：

``` kotlin
interface MetadataCompiler {
    fun compile(
        source: DocumentMetadata,
        context: CompileContext
    ): CompileResult
}

data class CompileContext(
    val compilerVersion: String,
    val availableDocuments: Map<String, DocumentSchema>,
    val databaseSchema: DatabaseSchemaSnapshot,
    val availableRoles: Set<String>,
    val implementations: Map<String, ImplementationDescriptor>
)

sealed interface CompileResult {
    data class Success(
        val plan: DocumentPlan,
        val diagnostics: List<Diagnostic>
    ) : CompileResult

    data class Failure(
        val diagnostics: List<Diagnostic>
    ) : CompileResult
}
```

### 8.2 简化编译流程示例

``` kotlin
class DefaultMetadataCompiler : MetadataCompiler {

    override fun compile(
        source: DocumentMetadata,
        context: CompileContext
    ): CompileResult {
        val diagnostics = mutableListOf<Diagnostic>()

        validateIdentity(source, diagnostics)
        validateTable(source, context, diagnostics)
        validateFields(source, diagnostics)
        validateReferences(source, context, diagnostics)
        validatePermissions(source, context, diagnostics)
        validatePages(source, diagnostics)
        validateImplementations(source, context, diagnostics)

        if (diagnostics.any { it.severity == Severity.ERROR }) {
            return CompileResult.Failure(diagnostics)
        }

        val plan = buildPlan(
            source = source,
            context = context
        )

        return CompileResult.Success(
            plan = plan,
            diagnostics = diagnostics
        )
    }
}
```

编译器的各个校验函数可以先放在同一个实现文件中。只有复杂度和复用需求出现后，再拆分模块，不预设庞大的编译器类体系。

## 9. 执行计划如何被运行时使用

执行计划是声明式运行信息，不负责亲自执行所有操作。各运行时组件读取计划并完成工作。

  计划内容     消费方             作用
  ------------ ------------------ ----------------------------
  字段计划     Field Runtime      类型转换、默认值和字段约束
  校验计划     Validator          执行通用字段校验
  数据库映射   Document Runtime   通用持久化和读取
  权限计划     Ability Runtime    解析并执行权限策略
  表单计划     Page Runtime       生成表单描述和控件
  列表计划     Query Runtime      生成安全的列表查询
  动作计划     Action Runtime     将动作入口绑定到业务实现
  依赖信息     Runtime Registry   确保相关计划版本兼容

### 9.1 保存请求示意

``` kotlin
suspend fun saveDocument(
    doctype: String,
    body: JsonObject,
    context: RequestContext
): JsonObject {
    val snapshot = runtimeRegistry.current()
    val plan = snapshot.requirePlan(doctype)

    abilityRuntime.checkPermission(plan, context)
    abilityRuntime.checkDataScope(plan, context)

    val data = fieldRuntime.convert(plan, body)
    validator.validate(plan, data)

    return transactionManager.inTransaction {
        documentRuntime.save(plan, data, context)
    }
}
```

这是目标流程示意。正式实现必须保证数据范围过滤、字段权限、事务、审计和并发控制均在可信服务端执行。

### 9.2 查询请求示意

列表查询可以读取 `ListPlan` 生成查询，但必须：

-   对字段、排序和筛选项做白名单校验。
-   使用参数化 SQL。
-   自动应用租户、账套和数据范围条件。
-   对字段级权限进行过滤。
-   限制分页和查询成本。
-   复杂报表允许使用受控的 Kotlin/SQL
    查询实现，不强制所有查询都由元数据生成。

## 10. 与 Kotlin 业务实现的绑定

元数据与 Kotlin 代码应分别管理、分别构建，但在发布时形成明确的绑定关系。

建议业务单元形态：

``` text
purchase_order/
├── purchase_order.yaml
└── PurchaseOrder.kt
```

Kotlin 业务代码由正常 Gradle
构建流程编译为应用或插件产物。元数据发布不应重新编译整个 Kotlin 项目。

发布时需要：

1.  确认元数据声明的业务类或动作实现存在。
2.  确认实现版本与当前部署的应用版本兼容。
3.  检查动作方法签名和允许暴露的入口。
4.  将类标识、动作标识和制品版本写入计划或发布清单。
5.  运行时只调用经过注册和授权的实现。

不得允许 YAML
任意填写类名后反射调用任意方法。实现绑定必须通过受控注册表或等效机制完成。

元数据版本与 Kotlin
制品版本可以独立演进，但发布记录必须记录两者的组合关系。

## 11. 版本管理与存储建议

建议至少区分以下概念：

### 11.1 定义版本

保存可编辑的元数据源定义及其版本。

### 11.2 编译版本

保存编译器版本、标准化结果、执行计划、依赖信息和校验值。

### 11.3 发布记录

保存某个编译版本何时、由谁、向哪个运行环境发布，以及发布结果。

建议表结构示意：

``` sql
CREATE TABLE meta.document_definition (
    id              UUID PRIMARY KEY,
    name            VARCHAR(120) NOT NULL,
    version         INTEGER NOT NULL,
    status          VARCHAR(20) NOT NULL,
    definition_json JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID NOT NULL,
    UNIQUE (name, version)
);

CREATE TABLE meta.document_plan (
    id               UUID PRIMARY KEY,
    document_name   VARCHAR(120) NOT NULL,
    version          INTEGER NOT NULL,
    compiler_version VARCHAR(40) NOT NULL,
    plan_json        JSONB NOT NULL,
    checksum         VARCHAR(128) NOT NULL,
    compiled_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_name, version, checksum)
);

CREATE TABLE meta.document_release (
    id               UUID PRIMARY KEY,
    document_name   VARCHAR(120) NOT NULL,
    version          INTEGER NOT NULL,
    plan_id          UUID NOT NULL REFERENCES meta.document_plan(id),
    artifact_version VARCHAR(120),
    status           VARCHAR(20) NOT NULL,
    released_at      TIMESTAMPTZ,
    released_by      UUID,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

以上是逻辑结构示意。正式建表前应结合 ZTX 现有 `meta`
Schema、审计规范、租户数据库策略和迁移体系统一审查，避免重复建设已有表。

建议状态至少包括：

-   Definition：DRAFT、VALIDATED、RELEASED、RETIRED。
-   Release：PREPARING、READY、ACTIVE、FAILED、ROLLED_BACK。

实际状态机应明确合法转换，不允许仅靠任意字符串更新状态。

## 12. 原子发布与运行时快照

### 12.1 为什么需要快照

如果各模块分别读取"当前版本"，发布过程中可能出现表单、API、权限和数据映射使用不同版本的情况。

因此运行时应使用一个不可变快照，包含本次发布所需的完整计划集合和绑定信息。

### 12.2 快照示意

``` kotlin
data class RuntimeSnapshot(
    val releaseId: UUID,
    val generation: Long,
    val plans: Map<String, DocumentPlan>
)

class RuntimeRegistry(
    initial: RuntimeSnapshot
) {
    private val current = AtomicReference(initial)

    fun current(): RuntimeSnapshot = current.get()

    fun switchTo(next: RuntimeSnapshot) {
        current.set(next)
    }
}
```

### 12.3 发布步骤

1.  在后台读取候选元数据。
2.  编译所有受影响的业务计划。
3.  检查依赖闭包和版本兼容性。
4.  检查数据库迁移方案。
5.  执行已批准且可安全执行的迁移。
6.  构建完整 RuntimeSnapshot。
7.  运行发布前健康检查。
8.  使用原子引用切换当前快照。
9.  记录发布成功及快照版本。
10. 保留旧快照，供在途请求和回滚使用。

切换前任何步骤失败，都不得改变当前快照。

### 12.4 在途请求

请求开始时取得一次快照引用，并在整个请求过程中使用该引用。禁止请求执行到一半重新读取当前快照。

旧快照在没有在途请求引用、后台任务或其他运行时使用者后，才可以回收。

如果未来采用多实例部署，还需要通过集中发布记录、版本通知和一致的激活协议协调各实例，不能只依靠单进程
`AtomicReference`。

## 13. 缓存与校验值

-   以业务标识、版本和校验值定位计划。
-   计划对象发布后不可变。
-   缓存失效以发布版本为边界。
-   不允许仅以业务名称覆盖计划内容而丢失历史版本。
-   校验值应包含标准化元数据、编译器版本和影响计划语义的依赖信息。
-   运行时启动时应加载并验证当前有效发布版本。
-   缓存丢失时可从持久化计划重建，不应依赖临时内存状态作为唯一数据源。

## 14. 兼容性、迁移与回滚

### 14.1 兼容性分类

元数据变更至少应识别：

-   仅页面展示变更。
-   新增可空字段。
-   新增带默认值字段。
-   字段类型兼容调整。
-   字段重命名。
-   删除字段。
-   非空约束收紧。
-   唯一约束新增。
-   引用目标变更。

不同类型的变更应有不同迁移策略和风险级别。

### 14.2 数据库迁移

-   页面配置变更通常不需要 DDL。
-   新增字段应先判断默认值、空值和历史数据兼容性。
-   删除、缩窄或重命名字段属于高风险变更。
-   破坏性变更应经过审批、备份和迁移验证。
-   数据迁移应可观测、可重试，并记录执行结果。
-   元数据回滚不代表数据库迁移可以自动反向执行。

### 14.3 回滚

回滚应恢复到之前的有效发布快照，并检查：

-   当前数据库结构是否仍兼容旧计划。
-   当前 Kotlin 制品是否仍提供旧版本所需实现。
-   新版本是否已经写入旧版本无法读取的数据。
-   是否需要兼容视图、数据转换或前向修复。

若数据库已发生不可逆变化，不能承诺简单切换旧计划即可完成安全回滚。

## 15. 安全要求

-   元数据编辑、校验、发布和回滚均需权限控制。
-   发布操作必须记录操作人、时间、来源版本和结果。
-   元数据不允许任意执行代码。
-   SQL 查询配置必须参数化并受白名单限制。
-   数据范围和字段权限由服务端强制执行。
-   计划内容不能被普通业务请求修改。
-   运行时只能执行注册表中已批准的 Kotlin 业务实现。
-   发布包、元数据和依赖关系应具备完整性校验。
-   计划和诊断信息不得泄露敏感字段值或凭据。

## 16. 可观测性与故障处理

每次编译和发布至少记录：

-   业务对象名称和版本。
-   源定义校验值。
-   编译器版本。
-   编译耗时和结果。
-   诊断错误及警告。
-   依赖计划版本。
-   数据库迁移编号及结果。
-   发布人、发布时间和发布环境。
-   激活的快照 generation。
-   回滚原因和目标版本。

运行时请求日志应能够关联发布版本和计划版本，以便定位"某个请求使用了哪一版业务定义"。

## 17. 测试与验收

### 17.1 编译器单元测试

-   正确元数据可以编译。
-   重复字段名编译失败。
-   不支持的字段类型编译失败。
-   缺失引用目标编译失败。
-   非法角色或权限声明编译失败。
-   表单或列表引用不存在的字段时编译失败。
-   不合法数据库映射编译失败。
-   同一输入和编译器版本生成稳定校验值。

### 17.2 发布集成测试

-   编译失败时旧版本仍然有效。
-   依赖缺失时不能发布。
-   数据库迁移未批准时不能激活需要该迁移的版本。
-   发布成功后新请求使用新快照。
-   在途请求持续使用原快照。
-   多个相关业务计划作为一个一致发布单元切换。
-   回滚后新请求使用目标旧快照。
-   发布记录和审计记录完整。

### 17.3 安全测试

-   普通用户不能发布或回滚元数据。
-   无权限用户不能读取受限字段。
-   列表筛选、排序不能注入 SQL。
-   直接 SQL 查询不能绕过数据范围。
-   非注册 Kotlin 方法不能通过元数据调用。
-   恶意或异常元数据不能触发任意代码执行。

### 17.4 性能测试

至少测量：

-   元数据解析和编译耗时。
-   计划序列化、反序列化耗时。
-   冷启动计划加载耗时。
-   缓存命中时请求额外开销。
-   大量 DocType 的快照构建耗时。
-   发布期间请求延迟和错误率。
-   多实例版本同步时间。

性能目标应在 ZTX
实际部署环境和业务规模确定后设定，不在缺乏基准数据时虚构固定数字。

## 18. 分阶段实现建议

### M1：最小编译闭环

-   定义元数据 Kotlin 模型。
-   支持 YAML/JSON 解析。
-   完成基础静态校验。
-   生成不可变 DocumentPlan。
-   编写编译器单元测试。

### M2：运行时消费计划

-   PlanRegistry。
-   Document Runtime 接入字段及数据库映射计划。
-   Validator 接入校验计划。
-   Page Runtime 接入表单和列表计划。
-   Ability Runtime 接入权限计划。

### M3：发布和版本管理

-   定义草稿、校验、发布状态。
-   持久化源定义、计划和发布记录。
-   实现依赖检查。
-   实现发布候选版本和失败诊断。
-   实现单实例原子快照切换。

### M4：生产级治理

-   数据库迁移兼容性分析。
-   多实例发布协调。
-   Kotlin 实现绑定及版本兼容。
-   回滚检查和恢复策略。
-   审计、监控、性能和安全验收。

## 19. 与总体需求文档的关系

本文是《ZTX 低代码业务开发规范与运行时需求》的专项技术设计。

总体需求规定：

-   默认一个业务一个文件。
-   Document 承载常规单据数据和行为。
-   元数据描述通用字段、表单、列表和权限。
-   Ability 由运行时自动组合。
-   复杂业务使用 Kotlin。
-   复杂查询允许直接 SQL，但必须遵守安全边界。

本文只负责细化元数据发布和执行计划编译机制，不得反向要求业务开发者增加
Model、Service、Repository、Controller
四层，也不得把复杂制造业务强制配置化。

两份文档应同步维护版本和术语。若专项设计发现总体需求需要调整，应先修订总体需求，再更新本文。
