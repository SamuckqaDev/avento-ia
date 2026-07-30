// Processo principal do Avento Desktop.
//
// Ele existe para uma coisa: transformar "instale JDK, Docker, Ollama e rode tres comandos" em
// "abra o aplicativo". Sobe o backend, espera ele responder e so entao mostra a janela.
//
// O que ele NAO faz, de proposito: hospedar os servidores MCP. Eles continuam no backend, junto do
// laco do agente, do sandbox de workspace e da aprovacao de ferramenta. Mover a execucao para ca
// colocaria a fronteira de seguranca no cliente e mataria as execucoes agendadas, que hoje rodam
// pelo AgentRunWorker sem nenhuma janela aberta.
'use strict';

const { app, BrowserWindow, ipcMain, shell } = require('electron');
const { spawn, execFile } = require('node:child_process');
const http = require('node:http');
const https = require('node:https');
const net = require('node:net');
const path = require('node:path');
const fs = require('node:fs');

const PROJECT_ROOT = path.resolve(__dirname, '..', '..');
const LOG_DIR = path.join(PROJECT_ROOT, 'tmp', 'dev');

/**
 * Carrega o .env do projeto no ambiente deste processo.
 *
 * <p>O ./scripts/dev-up.sh faz isso com `set -a; source .env`, e todo o ajuste fino do usuario mora
 * la: raiz de workspace, limites de rodada e de chamada de ferramenta, auto-aprovacao. Sem carregar
 * aqui, subir pelo app usaria os padroes do application.yml — a aplicacao abriria normalmente e se
 * comportaria diferente, que e pior do que falhar.
 *
 * <p>Nao sobrescreve variavel ja definida: quem exporta na mao antes de abrir o app quer aquele
 * valor, nao o do arquivo.
 */
function loadDotEnv() {
  const envFile = path.join(PROJECT_ROOT, '.env');
  if (!fs.existsSync(envFile)) {
    return;
  }
  for (const line of fs.readFileSync(envFile, 'utf8').split('\n')) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) {
      continue;
    }
    const separator = trimmed.indexOf('=');
    if (separator < 1) {
      continue;
    }
    const key = trimmed.slice(0, separator).trim();
    if (process.env[key] !== undefined) {
      continue;
    }
    // Aspas ao redor do valor sao delimitador de shell, nao parte do dado.
    process.env[key] = trimmed
      .slice(separator + 1)
      .trim()
      .replace(/^(['"])(.*)\1$/, '$2');
  }
}

loadDotEnv();

const BACKEND_URL = process.env.AVENTO_BACKEND_URL || 'http://127.0.0.1:8000';
const FRONTEND_URL = process.env.AVENTO_FRONTEND_URL || 'http://127.0.0.1:5173';
const OLLAMA_URL = process.env.AVENTO_OLLAMA_URL || 'http://127.0.0.1:11434';
const SPRING_PROFILE = process.env.AVENTO_SPRING_PROFILE || 'local';
// Em desenvolvimento a janela aponta para o Vite, que tem o proxy de /api e o hot reload. Em
// producao aponta para o BACKEND: e ele que serve o front construido, e assim origem, cookie de
// sessao e chamada de API sao o mesmo host. Carregar de file:// quebraria o withCredentials.
const IS_DEV = process.env.AVENTO_DESKTOP_DEV === '1' || !app.isPackaged;

// O backend leva perto de um minuto para subir com o Maven; o build empacotado e bem mais rapido.
const BACKEND_STARTUP_TIMEOUT_MS = Number(process.env.AVENTO_DESKTOP_BACKEND_TIMEOUT_MS || 180_000);
const HEALTH_POLL_INTERVAL_MS = 1_000;

/** Processos que ESTE app subiu. So eles sao derrubados na saida. */
const ownedProcesses = [];
let mainWindow = null;
/** Tamanho e posicao de antes do modo sobreposicao, para devolver ao sair dele. */
let restoreBounds = null;

// Painel de canto do modo sobreposicao: cabe a camera e o estado do gesto, e nada mais.
const OVERLAY_WIDTH = 360;
const OVERLAY_HEIGHT = 420;

// ------------------------------------------------------------------ infra

function log(message) {
  process.stdout.write(`[avento-desktop] ${message}\n`);
}

/**
 * true quando a URL responde 2xx/3xx. Qualquer erro de rede conta como "ainda nao subiu".
 *
 * <p>Fala os DOIS protocolos. O servidor de desenvolvimento sobe em HTTPS quando o alvo e o iPhone
 * (o Safari do iOS so libera a camera em contexto seguro), e uma sonda so-http bate nessa porta, nao
 * entende a resposta e conclui que o front nao subiu — enquanto ele esta servindo normalmente.
 *
 * <p>`rejectUnauthorized: false` porque o certificado de desenvolvimento e autoassinado: validar a
 * cadeia aqui faria a sonda recusar o proprio servidor que ela deveria encontrar.
 */
function isReachable(url) {
  return new Promise(resolve => {
    let client;
    let options;
    try {
      const parsed = new URL(url);
      client = parsed.protocol === 'https:' ? https : http;
      options = parsed.protocol === 'https:' ? { rejectUnauthorized: false } : {};
    } catch {
      resolve(false);
      return;
    }
    const request = client.get(url, options, response => {
      response.resume();
      resolve(response.statusCode >= 200 && response.statusCode < 400);
    });
    request.setTimeout(2_000, () => {
      request.destroy();
      resolve(false);
    });
    request.on('error', () => resolve(false));
  });
}

/**
 * Enderecos equivalentes de loopback, IPv4 e IPv6.
 *
 * <p>Nao e preciosismo: o Vite sobe escutando em `[::1]:5173` — SO IPv6 — e anuncia
 * "http://localhost:5173". Uma sonda fixada em 127.0.0.1 leva ECONNREFUSED em cima de um servidor
 * que esta de pe e funcionando, e o app acusa "o front nao subiu" enquanto o log do Vite diz
 * "ready". Testar as duas familias e usar a que responder resolve nos dois sentidos, porque o
 * inverso (servidor so em IPv4, sonda em ::1) tambem acontece.
 */
function loopbackVariants(baseUrl, { tryBothSchemes = false } = {}) {
  let parsed;
  try {
    parsed = new URL(baseUrl);
  } catch {
    return [baseUrl];
  }

  const siblings = { localhost: '127.0.0.1', '127.0.0.1': 'localhost', '[::1]': 'localhost' };
  const sibling = siblings[parsed.hostname] || siblings[`[${parsed.hostname}]`];
  const hosts = sibling ? [parsed.hostname, sibling] : [parsed.hostname];
  // O servidor de desenvolvimento pode estar em http ou https conforme o alvo seja o Mac ou o
  // iPhone, e quem sobe o app nao tem como saber qual dos dois esta valendo.
  const schemes = tryBothSchemes
    ? [parsed.protocol, parsed.protocol === 'https:' ? 'http:' : 'https:']
    : [parsed.protocol];

  const variants = [];
  for (const scheme of schemes) {
    for (const host of hosts) {
      const candidate = new URL(baseUrl);
      candidate.protocol = scheme;
      candidate.hostname = host;
      variants.push(candidate.origin);
    }
  }
  return variants;
}

/**
 * Espera o servico responder e devolve a URL BASE que funcionou, ou null no estouro.
 *
 * <p>Devolver a URL importa: a janela precisa carregar exatamente o endereco que respondeu. Sondar
 * por localhost e depois abrir 127.0.0.1 daria tela de erro com o servidor no ar.
 */
async function waitFor(baseUrl, healthPath, label, timeoutMs, options = {}) {
  const candidates = loopbackVariants(baseUrl, options);
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    for (const candidate of candidates) {
      if (await isReachable(`${candidate}${healthPath}`)) {
        return candidate;
      }
    }
    await new Promise(resolve => setTimeout(resolve, HEALTH_POLL_INTERVAL_MS));
  }
  log(`${label} nao respondeu em ${Math.round(timeoutMs / 1000)}s (tentei ${candidates.join(' e ')})`);
  return null;
}

/**
 * Sobe um processo filho no proprio grupo.
 *
 * <p>`detached: true` e o que permite derrubar a arvore inteira depois. O `mvn spring-boot:run`
 * bifurca uma JVM separada: matar so o Maven deixaria o Java segurando a porta 8000, e a proxima
 * abertura do app encontraria a porta ocupada por um backend velho.
 */
function spawnOwned(command, args, options, logFile) {
  fs.mkdirSync(LOG_DIR, { recursive: true });
  const output = fs.openSync(path.join(LOG_DIR, logFile), 'a');
  const child = spawn(command, args, {
    cwd: PROJECT_ROOT,
    detached: true,
    stdio: ['ignore', output, output],
    ...options,
  });
  child.unref();
  ownedProcesses.push(child);
  return child;
}

function stopOwnedProcesses() {
  while (ownedProcesses.length > 0) {
    const child = ownedProcesses.pop();
    if (!child.pid || child.exitCode !== null) {
      continue;
    }
    try {
      // Negativo = o GRUPO todo, nao so o lider.
      process.kill(-child.pid, 'SIGTERM');
    } catch {
      // Ja morreu, ou o grupo nao existe mais. Nada a fazer.
    }
  }
}

// ------------------------------------------------------------ dependencias

/**
 * Porta TCP aceitando conexao.
 *
 * <p>Postgres e Redis nao falam HTTP: a sonda de saude do backend nao serve para eles, e um GET
 * numa porta de banco so gera lixo no log do servidor.
 */
function isPortOpen(host, port) {
  return new Promise(resolve => {
    const socket = net.connect({ host, port });
    const finish = result => {
      socket.destroy();
      resolve(result);
    };
    socket.setTimeout(1_500);
    socket.on('connect', () => finish(true));
    socket.on('timeout', () => finish(false));
    socket.on('error', () => finish(false));
  });
}

function run(command, args, timeoutMs) {
  return new Promise(resolve => {
    execFile(command, args, { timeout: timeoutMs, cwd: PROJECT_ROOT }, error => resolve(!error));
  });
}

async function waitForPorts(timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const [postgres, redis] = await Promise.all([
      isPortOpen('127.0.0.1', 5432),
      isPortOpen('127.0.0.1', 6379),
    ]);
    if (postgres && redis) {
      return true;
    }
    await new Promise(resolve => setTimeout(resolve, HEALTH_POLL_INTERVAL_MS));
  }
  return false;
}

/**
 * Garante Postgres e Redis de pe antes do backend.
 *
 * <p>Sem isto o backend sobe, falha ao abrir o datasource e morre — e o erro no log fala de conexao
 * recusada, nao de "o Docker esta parado", que e a causa real. Com a maquina recem-ligada isso
 * acontece toda vez.
 *
 * <p>Os containers sao os que ja existem no docker-compose.yml, com os volumes que ja existem.
 * Embarcar um Postgres proprio dentro do app apontaria para um banco VAZIO: as conversas, os planos
 * e a memoria continuariam no volume antigo, e a impressao seria de que o app perdeu tudo.
 */
async function ensureDependencies(onProgress) {
  const [postgresUp, redisUp] = await Promise.all([
    isPortOpen('127.0.0.1', 5432),
    isPortOpen('127.0.0.1', 6379),
  ]);
  if (postgresUp && redisUp) {
    log('postgres e redis ja respondem');
    return true;
  }

  // Docker parado costuma ser a Colima desligada; ela e o runtime nesta maquina.
  if (!(await run('docker', ['info'], 20_000))) {
    onProgress('Ligando o Docker (Colima)…');
    log('docker nao respondeu; tentando subir a Colima');
    await run('colima', ['start'], 180_000);
  }

  onProgress('Subindo Postgres e Redis…');
  log('subindo os containers do docker-compose');
  const started =
    (await run('docker', ['compose', 'up', '-d', 'postgres', 'redis-stack'], 180_000)) ||
    (await run('docker-compose', ['up', '-d', 'postgres', 'redis-stack'], 180_000));
  if (!started) {
    log('nao consegui subir os containers');
  }
  return waitForPorts(120_000);
}

/**
 * Sobe o Ollama quando ele for local e estiver parado.
 *
 * <p>Nao e opcional para quem usa o provedor local: sem ele a conversa falha na primeira mensagem,
 * com um erro de conexao que nao diz que o servico de modelo nem estava rodando.
 *
 * <p>Endereco de outra maquina nao e tentado — `ollama serve` aqui nao sobe um servidor que mora
 * noutro host, e a tentativa esconderia a causa real (maquina de inferencia desligada) atras de um
 * erro de bind.
 */
async function ensureOllama(onProgress) {
  let host;
  try {
    host = new URL(OLLAMA_URL);
  } catch {
    return false;
  }
  if (await isReachable(`${OLLAMA_URL}/api/tags`)) {
    log(`ollama ja responde em ${OLLAMA_URL}`);
    return true;
  }
  if (!['127.0.0.1', 'localhost', '0.0.0.0', '[::1]'].includes(host.hostname)) {
    log(`ollama remoto em ${OLLAMA_URL} nao respondeu; nao ha o que subir daqui`);
    return false;
  }

  onProgress('Subindo o Ollama…');
  log('subindo o ollama');
  spawnOwned(
    'ollama',
    ['serve'],
    {
      env: {
        ...process.env,
        OLLAMA_HOST: host.host,
        OLLAMA_FLASH_ATTENTION: process.env.AVENTO_OLLAMA_FLASH_ATTENTION || '1',
        // Cache KV quantizado corta a RAM de contexto; nesta maquina e a diferenca entre caber e
        // nao caber.
        OLLAMA_KV_CACHE_TYPE: process.env.AVENTO_OLLAMA_KV_CACHE_TYPE || 'q4_0',
      },
    },
    'ollama.log',
  );
  return Boolean(await waitFor(OLLAMA_URL, '/api/tags', 'ollama', 60_000));
}

// ---------------------------------------------------------------- backend

/** Jar empacotado, quando existir. Sem ele o Maven assume, que e o caminho de desenvolvimento. */
function packagedJar() {
  const targetDir = path.join(PROJECT_ROOT, 'back', 'avento', 'avento-app', 'target');
  if (!fs.existsSync(targetDir)) {
    return null;
  }
  const jar = fs
    .readdirSync(targetDir)
    .filter(name => name.endsWith('.jar') && !name.endsWith('-sources.jar'))
    .sort()
    .pop();
  return jar ? path.join(targetDir, jar) : null;
}

async function ensureBackend() {
  // Reaproveita um backend ja de pe. Sem esta checagem, abrir o app com o ./scripts/dev-up.sh
  // rodando subiria um SEGUNDO backend, que morreria na porta ocupada — e o log culparia a porta,
  // nao a causa.
  for (const candidate of loopbackVariants(BACKEND_URL)) {
    if (await isReachable(`${candidate}/api/health`)) {
      log(`backend ja saudavel em ${candidate}`);
      return candidate;
    }
  }

  const jar = packagedJar();
  if (jar) {
    log(`subindo o backend pelo jar: ${path.basename(jar)}`);
    spawnOwned('java', [`-Dspring.profiles.active=${SPRING_PROFILE}`, '-jar', jar], {}, 'backend.log');
  } else {
    log(`subindo o backend pelo Maven (perfil ${SPRING_PROFILE})`);
    spawnOwned(
      'mvn',
      [
        '-f',
        path.join(PROJECT_ROOT, 'back', 'avento', 'pom.xml'),
        'spring-boot:run',
        '-pl',
        'avento-app',
        '-am',
        `-Dspring-boot.run.profiles=${SPRING_PROFILE}`,
      ],
      { env: { ...process.env, AVENTO_PROJECT_ROOT: PROJECT_ROOT } },
      'backend.log',
    );
  }

  return waitFor(BACKEND_URL, '/api/health', 'backend', BACKEND_STARTUP_TIMEOUT_MS);
}

async function ensureViteDevServer() {
  // Reaproveita o que ja estiver servindo, em qualquer protocolo. Sem isto, subir um segundo Vite
  // com a porta ocupada o empurra para 5174 — e o app fica esperando na 5173 por um servidor que
  // mudou de endereco sem avisar ninguem.
  for (const candidate of loopbackVariants(FRONTEND_URL, { tryBothSchemes: true })) {
    if (await isReachable(candidate)) {
      log(`servidor de desenvolvimento ja de pe em ${candidate}`);
      return candidate;
    }
  }
  // HTTP e o padrao. `AVENTO_HTTPS=1` habilita https no desenvolvimento.
  const useHttps = process.env.AVENTO_HTTPS === '1';
  const script = useHttps ? 'dev:web:https' : 'dev:web';

  log(`subindo o servidor de desenvolvimento do front (${script})`);
  spawnOwned('npm', ['run', script], { cwd: path.join(PROJECT_ROOT, 'front') }, 'frontend.log');
  return waitFor(FRONTEND_URL, '', 'front', 60_000, { tryBothSchemes: true });
}

// ----------------------------------------------------------------- janela

/**
 * Tela de espera enquanto o backend sobe.
 *
 * <p>Sem ela a janela fica branca por um minuto e parece travada — o Maven compila o projeto
 * inteiro na primeira vez. Mostrar o que esta acontecendo evita o fechar-e-abrir-de-novo, que so
 * faz o usuario perder a compilacao ja em andamento.
 */
function bootScreen(message) {
  const html = `<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><title>Avento</title>
<style>
  html,body{height:100%;margin:0;display:flex;align-items:center;justify-content:center;
    background:#0B1512;color:#F2FFFB;font:600 14px/1.6 ui-sans-serif,system-ui,sans-serif}
  .box{text-align:center;max-width:420px;padding:0 24px}
  .dot{width:10px;height:10px;border-radius:50%;background:#4FD1B4;display:inline-block;
    margin-right:8px;animation:pulse 1.2s ease-in-out infinite}
  @keyframes pulse{0%,100%{opacity:.25}50%{opacity:1}}
  small{display:block;margin-top:10px;color:#9FB8B1;font-weight:500}
</style></head><body><div class="box"><div><span class="dot"></span>${message}</div>
<small>A primeira abertura compila o backend e costuma demorar cerca de um minuto.</small>
</div></body></html>`;
  return `data:text/html;charset=utf-8,${encodeURIComponent(html)}`;
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 960,
    minHeight: 600,
    backgroundColor: '#0B1512',
    title: 'Avento',
    // Nasce escondida e aparece em 'ready-to-show': aberta antes de ter conteudo, a janela pisca
    // branca e, quando o processo e lancado de um shell em segundo plano, o macOS as vezes cria a
    // janela sem traze-la para a frente — ela existe, fica atras de tudo, e parece que o app nao
    // abriu.
    show: false,
    webPreferences: {
      // O renderer continua sendo sandbox de navegador. O que precisa de Node mora aqui no main —
      // abrir isso transformaria qualquer XSS numa execucao de codigo na maquina.
      contextIsolation: true,
      nodeIntegration: false,
      preload: path.join(__dirname, 'preload.cjs'),
    },
  });

  // Link externo abre no navegador do sistema, nao dentro do app: uma pagina de terceiro rodando
  // na mesma janela teria acesso a sessao ja autenticada.
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: 'deny' };
  });

  mainWindow.once('ready-to-show', () => {
    mainWindow.show();
    mainWindow.focus();
    // Sem isto o icone nao volta para o Dock quando o app sobe de um terminal, e nao ha por onde
    // clicar para trazer a janela de volta.
    if (process.platform === 'darwin' && app.dock) {
      app.dock.show();
    }
    app.focus({ steal: true });
    const bounds = mainWindow.getBounds();
    log(`janela visivel=${mainWindow.isVisible()} em ${bounds.width}x${bounds.height} @ ${bounds.x},${bounds.y}`);
  });

  mainWindow.webContents.on('render-process-gone', (_event, details) => {
    log(`o renderizador morreu: ${details.reason}`);
  });

  mainWindow.webContents.on('did-fail-load', (_event, code, description, url) => {
    log(`falha ao carregar ${url}: ${description} (${code})`);
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
  });

  return mainWindow;
}

async function boot() {
  const window = createWindow();
  const showProgress = message => window.loadURL(bootScreen(message));
  await showProgress('Subindo o Avento…');

  // Ordem importa: o backend abre o datasource ao subir. Invertida, ele morre com "conexao
  // recusada" e o log culpa a rede em vez do Postgres que ainda nao existia.
  const dependenciesUp = await ensureDependencies(showProgress);
  if (!dependenciesUp) {
    await showProgress('Postgres ou Redis não subiram. Confira se o Docker (Colima) está ligado.');
    return;
  }
  // O Ollama nao bloqueia: com um provedor de nuvem ativo, a conversa funciona sem ele.
  await ensureOllama(showProgress);

  await showProgress('Subindo o backend…');
  const backendUrl = await ensureBackend();
  if (!backendUrl) {
    await window.loadURL(
      bootScreen(`Não consegui falar com o backend em ${BACKEND_URL}. Veja tmp/dev/backend.log`),
    );
    return;
  }

  if (IS_DEV) {
    const frontUrl = await ensureViteDevServer();
    // Carrega o endereco que RESPONDEU, nao o configurado: o Vite escuta so em IPv6, e abrir a
    // variante que nao atende daria tela de erro com o servidor no ar.
    await window.loadURL(frontUrl || bootScreen('O servidor de desenvolvimento não subiu. Veja tmp/dev/frontend.log'));
    return;
  }

  // Em producao quem serve o front e o proprio backend, entao a janela e a API compartilham origem
  // e o cookie de sessao viaja normalmente.
  await window.loadURL(backendUrl);
}

// ------------------------------------------------------------------ ciclo

/**
 * Modo sobreposição: a janela passa a flutuar sobre os outros aplicativos.
 *
 * <p>É o que torna o controle por gesto utilizável fora do Avento. Apresentando no WPS, a janela
 * ficava atrás do slide: o widget da câmera e o cursor sumiam, e não havia como ver o que a mão
 * estava fazendo enquanto se controlava outro programa.
 *
 * <p>O nível 'screen-saver' é o que passa por cima de aplicativo em tela cheia no macOS — com o
 * padrão, a apresentação continuaria cobrindo tudo. E `visibleOnAllWorkspaces` mantém a janela ao
 * mudar de Espaço, que é o que o macOS faz sozinho ao entrar em apresentação.
 */
function setOverlayMode(enabled) {
  if (!mainWindow) {
    return false;
  }

  mainWindow.setAlwaysOnTop(enabled, enabled ? 'screen-saver' : 'normal');
  mainWindow.setVisibleOnAllWorkspaces(enabled, { visibleOnFullScreenScreen: true });

  // ATRAVESSAVEL A CLIQUE. Sem isto o modo sobreposicao se sabota: a janela fica por cima de tudo,
  // e o clique do Robot — que age na tela, nao no aplicativo — cai NELA em vez de cair na
  // apresentacao atras. O cursor se move, o clique acontece, e nada responde.
  // `forward: true` mantem o hover chegando, entao o widget ainda reage ao passar o mouse.
  mainWindow.setIgnoreMouseEvents(enabled, { forward: true });

  if (enabled) {
    // Encolhe para um painel de canto: ocupando a tela inteira, ela taparia o slide que se quer
    // apresentar. O que precisa ficar visivel e a camera e o estado do gesto, nao a conversa.
    restoreBounds = mainWindow.getBounds();
    const { workArea } = require('electron').screen.getPrimaryDisplay();
    mainWindow.setBounds({
      width: OVERLAY_WIDTH,
      height: OVERLAY_HEIGHT,
      x: workArea.x + workArea.width - OVERLAY_WIDTH - 24,
      y: workArea.y + 24,
    });
  } else if (restoreBounds) {
    mainWindow.setBounds(restoreBounds);
    restoreBounds = null;
  }

  log(`modo sobreposicao ${enabled ? 'ligado' : 'desligado'}`);
  return true;
}

ipcMain.handle('avento:overlay-mode', (_event, enabled) => setOverlayMode(Boolean(enabled)));

/**
 * Aceita o certificado autoassinado do servidor de desenvolvimento — e SO dele.
 *
 * <p>Em HTTPS o Vite usa certificado gerado na hora, que nenhuma autoridade assina. Sem isto a
 * janela abre numa tela de erro de certificado, sem nada que explique.
 *
 * <p>A excecao vale apenas para loopback e faixas privadas. Aceitar certificado invalido em
 * endereco publico transformaria o app num alvo de intercepcao — exatamente o ataque que a
 * validacao existe para impedir.
 */
app.on('certificate-error', (event, _webContents, url, _error, _certificate, callback) => {
  let host;
  try {
    host = new URL(url).hostname;
  } catch {
    callback(false);
    return;
  }
  const isLocal =
    host === 'localhost' ||
    host === '127.0.0.1' ||
    host === '::1' ||
    host.endsWith('.local') ||
    /^192\.168\./.test(host) ||
    /^10\./.test(host) ||
    /^172\.(1[6-9]|2\d|3[01])\./.test(host);

  if (!isLocal) {
    callback(false);
    return;
  }
  event.preventDefault();
  callback(true);
});

app.whenReady().then(boot);

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) {
    boot();
  }
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

// Derruba so o que este app subiu: fechar a janela nao pode matar o backend de quem estava
// usando o ./scripts/dev-up.sh em paralelo.
app.on('before-quit', stopOwnedProcesses);
process.on('exit', stopOwnedProcesses);
process.on('SIGINT', () => app.quit());
process.on('SIGTERM', () => app.quit());
