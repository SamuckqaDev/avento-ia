import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import basicSsl from '@vitejs/plugin-basic-ssl'
import process from 'node:process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const apiProxyTarget = process.env.AVENTO_BACKEND_URL || 'http://127.0.0.1:8000'

// HTTPS é o padrão, e desligar é que exige `AVENTO_HTTPS=0`.
//
// O iPhone só abre a câmera em contexto seguro: `getUserMedia` é bloqueado em http://192.168.x.x,
// e localhost é a única exceção — que não vale para o telefone, que chega pelo IP da rede. Sem
// HTTPS a tela /remote carrega e a câmera nunca liga, o que parece defeito do Avento e é política
// do navegador.
//
// Já foi opcional. Na prática o custo de esquecer de ligar (o visor abre morto, sem nada no log
// explicando) é muito maior que o de deixar ligado sempre — no Mac o HTTPS não atrapalha nada.
const useHttps = process.env.AVENTO_HTTPS !== '0'

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
