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
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: { parser: tseslint.parser },
    },
  },
)
