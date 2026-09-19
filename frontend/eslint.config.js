import js from '@eslint/js'
import prettierConfig from 'eslint-config-prettier'
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'
import autoImportGlobals from './.eslintrc-auto-import.json' with { type: 'json' }

export default tseslint.config(
  { ignores: ['dist', 'src/auto-imports.d.ts', 'src/components.d.ts'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  prettierConfig,
  {
    files: ['**/*.{js,ts,vue}'],
    languageOptions: {
      globals: autoImportGlobals.globals,
    },
  },
  {
    files: ['**/*.{ts,vue}'],
    rules: {
      // 未定义变量由 vue-tsc 编译期检查（typescript-eslint 官方建议）
      'no-undef': 'off',
      // 下划线前缀为有意忽略的解构变量（如导出剔除分页参数）
      '@typescript-eslint/no-unused-vars': ['error', { varsIgnorePattern: '^_' }],
    },
  },
  {
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: { parser: tseslint.parser },
    },
  },
  {
    files: ['**/views/**/index.vue'],
    rules: {
      // 页面统一以 views/<模块>/index.vue 命名（与菜单表组件路径约定一致）
      'vue/multi-word-component-names': 'off',
    },
  },
)
