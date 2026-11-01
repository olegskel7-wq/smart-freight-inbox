import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
    },
    rules: {
      // Every page loads its data with `useEffect(() => { void load() }, [load])`, and load() resets
      // its error state synchronously. Kept visible as a warning, not an error, until the pages are
      // split and the fetch-on-mount pattern is replaced (docs/refactoring.md, Stage 1.3).
      'react-hooks/set-state-in-effect': 'warn',
    },
  },
])
