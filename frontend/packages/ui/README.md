# ZTX UI 组件使用规范

## 基本原则

**UI 组件优先，精简代码为荣。**

所有业务页面必须优先使用 `@ztx/ui` 提供的 ZTX UI 组件。页面可以编写业务布局和业务专属展示，但不得重复实现组件库已有的基础控件或通用交互。

前后端均应优先复用已有能力，保持实现简洁，避免重复代码、无必要的抽象和过度设计。不要为了减少行数牺牲可读性、可测试性、安全性或性能。

## 组件选择

| 需求 | 组件 |
|---|---|
| 操作按钮 | `ZButton` |
| 仅图标的按钮 | `ZIconButton` |
| 文本、密码、验证码等输入 | `ZInput` |
| 表单字段标签与提示 | `ZFormField` |
| 成功、错误和一般反馈 | `ZAlert` |
| 数据表格基础外观与横向滚动 | `ZTable` |
| 下拉菜单、提示、标签页、图标、布局 | 对应的 ZTX UI 组件 |

## 页面与组件的职责

- 组件库负责基础控件的外观、尺寸、状态、可访问性和通用交互。
- 业务页面负责业务状态、业务规则、页面布局和专属展示。
- 多个页面确实存在相同的展示或交互时，再提取复用组件。
- 不要为只使用一次的业务展示创建没有复用价值的抽象。
- 业务页面不得直接依赖 Reka UI 等底层第三方组件 API；第三方原语只能由 ZTX UI 适配层使用。

## 示例

```vue
<script setup lang="ts">
import { ZAlert, ZButton, ZFormField, ZInput } from '@ztx/ui'
import { ref } from 'vue'

const username = ref('')
const error = ref('')
</script>

<template>
  <form>
    <ZFormField label="用户名" field-id="username" required>
      <ZInput id="username" v-model="username" required autocomplete="username" />
    </ZFormField>
    <ZAlert v-if="error" tone="error" role="alert">{{ error }}</ZAlert>
    <ZButton type="submit">保存</ZButton>
  </form>
</template>
```

## 组件变更要求

- 优先扩展已有组件 API，不重复造轮子。
- 新增组件应有明确复用场景、稳定 API 和组件测试。
- 变更基础视觉或交互时，应检查所有使用页面并运行组件测试、前端构建及 Playwright 回归。
