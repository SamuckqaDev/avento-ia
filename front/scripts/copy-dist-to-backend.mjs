// Publica o front construido dentro dos recursos estaticos do backend.
//
// Motivo: fora do modo de desenvolvimento nao existe o proxy do Vite. Se a janela carregasse o
// build por file://, o `baseURL: '/'` do apiClient nao resolveria e o `withCredentials` mandaria o
// cookie de sessao para outra origem — a tela abriria e nada autenticaria. Servindo pelo proprio
// backend, janela e API ficam no mesmo host e nada disso precisa mudar.
import { cp, mkdir, readdir, rm } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const FRONT_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const DIST_DIR = path.join(FRONT_DIR, 'dist');
const STATIC_DIR = path.resolve(FRONT_DIR, '..', 'back', 'avento', 'avento-app', 'src', 'main', 'resources', 'static');

if (!existsSync(DIST_DIR)) {
  console.error('dist/ nao existe. Rode "npm run build" antes.');
  process.exit(1);
}

// Só o que o build gera. A pasta static tambem guarda docs.html, docs-en.html e o logo, que sao do
// backend e nao podem ser apagados por um deploy do front.
await rm(path.join(STATIC_DIR, 'assets'), { recursive: true, force: true });
await mkdir(STATIC_DIR, { recursive: true });

for (const entry of await readdir(DIST_DIR, { withFileTypes: true })) {
  await cp(path.join(DIST_DIR, entry.name), path.join(STATIC_DIR, entry.name), { recursive: true });
}

console.log(`front publicado em ${path.relative(process.cwd(), STATIC_DIR)}`);
