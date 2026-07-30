import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import basicSsl from '@vitejs/plugin-basic-ssl'
import process from 'node:process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const apiProxyTarget = process.env.AVENTO_BACKEND_URL || 'http://127.0.0.1:8000'

// HTTP é o padrão para desenvolvimento local. Para habilitar HTTPS: `AVENTO_HTTPS=1`.
const useHttps = process.env.AVENTO_HTTPS === '1'

// Certificado do mkcert, quando existir.
//
// O `basicSsl` resolve o contexto seguro, mas com certificado que nenhuma autoridade assina: o
// Safari do iPhone mostra "conexão não privada" a cada sessão, e o Electron loga erro de handshake
// no console. Com o par emitido por `scripts/setup-dev-cert.sh` a CA é a local do macOS, que os dois
// já confiam — o aviso some sem afrouxar validação nenhuma.
//
// Ausente, cai no autoassinado: quem só usa o Mac não precisa instalar mkcert para trabalhar.
const certDir = path.join(path.dirname(fileURLToPath(import.meta.url)), 'certs')
const certFile = path.join(certDir, 'dev-cert.pem')
const keyFile = path.join(certDir, 'dev-key.pem')
const hasLocalCert = fs.existsSync(certFile) && fs.existsSync(keyFile)

const httpsOptions = hasLocalCert
  ? { key: fs.readFileSync(keyFile), cert: fs.readFileSync(certFile) }
  : undefined

export default defineConfig({
  plugins: [react(), ...(useHttps && !hasLocalCert ? [basicSsl()] : [])],
  server: {
    ...(useHttps && httpsOptions ? { https: httpsOptions } : {}),
    // Permite que qualquer aparelho na rede local (Wi-Fi) acesse o Vite.
    host: true,
    proxy: {
      '/api': {
        target: apiProxyTarget,
        changeOrigin: true,
      },
      '/ws': {
        target: apiProxyTarget,
        changeOrigin: true,
        ws: true,
      },
      '/docs.html': {
        target: apiProxyTarget,
        changeOrigin: true,
      }
    }
  }
})
