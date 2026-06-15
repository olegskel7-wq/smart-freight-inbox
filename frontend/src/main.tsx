import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { AuthProvider } from 'react-oidc-context'
import 'bootstrap/dist/css/bootstrap.min.css'
import App from './App.tsx'
import { oidcConfig } from './auth/oidc.ts'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AuthProvider {...oidcConfig}>
      <BrowserRouter basename="/app">
        <App />
      </BrowserRouter>
    </AuthProvider>
  </StrictMode>,
)
