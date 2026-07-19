import { useState, useRef, useEffect, useCallback, useMemo } from 'react';
import { Sidebar, type GeneratedMedia } from '../../modules/layout/Sidebar';
import { MessageBubble, Message, type DocumentAttachment, type ImageAttachment } from '../../modules/chat/MessageBubble';
import { InputArea } from '../../modules/chat/InputArea';
import { useChatStream, ChunkData } from '../../hooks/useChatStream';
import type { AgentActivityEvent, ChatStreamContext, ImageGenerationOptions, MessageContext } from '../../hooks/useChatStream';
import { useAudioServices } from '../../hooks/useAudioServices';
import { useChatHistory } from '../../hooks/useChatHistory';
import { useNotifications } from '../../hooks/useNotifications';
import { useFileSystem } from '../../hooks/useFileSystem';
import { useSkills } from '../../hooks/useSkills';
import { useMcpCatalog } from '../../hooks/useMcpCatalog';
import { SkillsManager } from '../../modules/chat/SkillsManager';
import { McpToolsManager } from '../../modules/chat/McpToolsManager';
import { useAuth } from '../../modules/auth/AuthProvider';
import { api, apiErrorMessage } from '../../services/apiClient';
import { List, Moon, Sun, SpeakerHigh, SpeakerSlash, Folder, Columns, SignOut, BookOpen, Lightning, Plug, SlidersHorizontal, ImageSquare } from '@phosphor-icons/react';
import { 
  AppLayout, 
  MainContent, 
  Topbar, 
  VoiceToggleWrapper,
  Snackbar,
  HeaderIconButton,
  HeaderCompactMenu,
  HeaderMenuPanel,
  HeaderMenuSection,
  HeaderMenuActions,
  MobileOnlyIconButton,
  HeaderLeft, 
  MobileOverlay,
  ChatContainer, 
  WelcomeState,
  RightPanel,
  RightPanelHeader,
  CloseButton,
  PanelContent,
  AnalysisSection,
  ChipList,
  Chip,
  FindingList,
  FindingItem,
  ScriptList,
  ActivityList,
  ActivityItem,
  CommandButton,
  CommandOutput,
  ScriptRow,
  ScriptMeta,
  AttentionText,
  EmptyPanelState,
  ProcessList,
  ProcessCard,
  ProcessHeader,
  ProcessCommand,
  ProcessStatus
} from './styles';
import { X } from '@phosphor-icons/react';
import { FileDiffViewer } from '../../modules/chat/FileDiffViewer';

interface HomeProps {
  isDarkMode: boolean;
  toggleTheme: () => void;
}

interface ProjectScript {
  runner: string;
  name: string;
  command: string;
  path: string;
}

interface PendingApproval {
  approvalId: string;
  toolName: string;
  toolArguments: Record<string, unknown>;
}

export interface QueuedMessage {
  id: string;
  text: string;
  images: ImageAttachment[];
  documents: DocumentAttachment[];
}

const MAX_IMAGE_ATTACHMENTS = 4;
const MAX_IMAGE_ATTACHMENT_BYTES = 6 * 1024 * 1024;
const MAX_DOCUMENT_ATTACHMENTS = 4;
const MAX_DOCUMENT_ATTACHMENT_BYTES = 50 * 1024 * 1024;
const MAX_DOCUMENT_CONTEXT_CHARS = 5000;
const IMAGE_PREFERENCES_KEY = 'avento-image-generation-options';
const VOICE_ENABLED_KEY = 'avento-voice-enabled';

function loadVoiceEnabled(): boolean {
  try {
    return window.localStorage.getItem(VOICE_ENABLED_KEY) !== 'false';
  } catch {
    return true;
  }
}

interface DocumentExtractionResult {
  name: string;
  mediaType: string;
  bytes: number;
  reader: string;
  content: string;
  truncated: boolean;
}

interface ChatImageJob {
  id: string;
}

const IMAGE_JOB_MARKER_PATTERN = /\[\[avento-image-job:([0-9a-f-]{36})\]\]/gi;

function imageJobMarker(jobId: string): string {
  return `[[avento-image-job:${jobId}]]`;
}

function imageJobIds(content: string): Set<string> {
  return new Set(Array.from(content.matchAll(IMAGE_JOB_MARKER_PATTERN), match => match[1].toLowerCase()));
}

function isRestoredImageJobMessage(message: Message, generatedJobIds: Set<string>): boolean {
  if (generatedJobIds.size === 0 || message.content.replace(IMAGE_JOB_MARKER_PATTERN, '').trim()) {
    return false;
  }
  return Array.from(imageJobIds(message.content)).some(jobId => generatedJobIds.has(jobId));
}

function buildDocumentContext(documents: DocumentAttachment[]): string {
  if (documents.length === 0) return '';
  let remaining = MAX_DOCUMENT_CONTEXT_CHARS;
  const sections: string[] = [];

  for (const document of documents) {
    if (remaining <= 0) break;
    const header = `[Documento anexado: ${document.name}]\n`;
    const available = Math.max(0, remaining - header.length);
    const content = document.content.slice(0, available);
    sections.push(`${header}${content}`);
    remaining -= header.length + content.length;
  }

  return sections.join('\n\n');
}

interface StoredImagePreferences extends ImageGenerationOptions {
  lockSeed: boolean;
}

function loadImagePreferences(): StoredImagePreferences {
  const defaults: StoredImagePreferences = {
    qualityPreset: 'balanced',
    aspectRatio: 'square',
    subjectType: 'auto',
    subjectCount: 0,
    enhancePrompt: true,
    refinementEnabled: true,
    refinementStrength: 0.3,
    detailMode: 'face',
    cfgScale: 6,
    referenceStrength: 0.65,
    referenceMode: 'composition',
    structureControl: 'depth',
    structureStrength: 0.75,
    poseStrength: 0.75,
    adherenceValidationEnabled: true,
    maxAdherenceRetries: 1,
    lockSeed: false,
    seed: 42,
  };
  try {
    const raw = window.localStorage.getItem(IMAGE_PREFERENCES_KEY);
    if (!raw) return defaults;
    const stored = JSON.parse(raw) as Partial<StoredImagePreferences>;
    return {
      qualityPreset: ['draft', 'balanced', 'quality'].includes(stored.qualityPreset || '')
        ? stored.qualityPreset as ImageGenerationOptions['qualityPreset']
        : defaults.qualityPreset,
      aspectRatio: ['square', 'portrait', 'landscape'].includes(stored.aspectRatio || '')
        ? stored.aspectRatio as ImageGenerationOptions['aspectRatio']
        : defaults.aspectRatio,
      subjectType: ['auto', 'person', 'object', 'environment', 'vehicle', 'animal'].includes(stored.subjectType || '')
        ? stored.subjectType as ImageGenerationOptions['subjectType']
        : defaults.subjectType,
      subjectCount: Math.max(0, Math.min(4, Number(stored.subjectCount) || 0)),
      enhancePrompt: stored.enhancePrompt !== false,
      refinementEnabled: stored.refinementEnabled !== false,
      refinementStrength: Math.max(0.15, Math.min(0.55, Number(stored.refinementStrength) || 0.3)),
      detailMode: ['none', 'face', 'face-hands'].includes(stored.detailMode || '')
        ? stored.detailMode as ImageGenerationOptions['detailMode']
        : defaults.detailMode,
      cfgScale: Math.max(1, Math.min(12, Number(stored.cfgScale) || 6)),
      referenceStrength: Math.max(0.1, Math.min(0.9, Number(stored.referenceStrength) || 0.65)),
      referenceMode: ['composition', 'identity', 'transform'].includes(stored.referenceMode || '')
        ? stored.referenceMode as ImageGenerationOptions['referenceMode']
        : defaults.referenceMode,
      structureControl: ['none', 'depth', 'canny'].includes(stored.structureControl || '')
        ? stored.structureControl as ImageGenerationOptions['structureControl']
        : defaults.structureControl,
      structureStrength: Math.max(0.2, Math.min(1.5, Number(stored.structureStrength) || 0.75)),
      poseStrength: Math.max(0.2, Math.min(1.5, Number(stored.poseStrength) || 0.75)),
      adherenceValidationEnabled: stored.adherenceValidationEnabled !== false,
      maxAdherenceRetries: Number.isFinite(Number(stored.maxAdherenceRetries))
        ? Math.max(0, Math.min(2, Math.trunc(Number(stored.maxAdherenceRetries))))
        : defaults.maxAdherenceRetries,
      lockSeed: stored.lockSeed === true,
      seed: Math.max(0, Math.trunc(Number(stored.seed) || 42)),
    };
  } catch {
    return defaults;
  }
}

// Ferramentas que podem mudar a estrutura de pastas/arquivos do projeto. Sem isso, a
// árvore de arquivos na sidebar fica desatualizada depois que o agente cria/apaga algo
// (ex.: delete_directory apaga uma pasta, mas o painel continua mostrando ela).
const FILESYSTEM_MUTATING_TOOLS = new Set([
  'write_file',
  'delete_file',
  'delete_directory',
  'create_directory',
  'create_vite_project',
  'terminal_run',
  'terminal_start'
]);

interface ProjectFinding {
  severity: string;
  title: string;
  detail: string;
}

interface ProjectAnalysis {
  rootPath: string;
  projectName: string;
  generatedAt: string;
  technologies: string[];
  scripts: ProjectScript[];
  entrypoints: string[];
  fileStats: {
    totalFiles: number;
    totalBytes: number;
    extensions: Record<string, number>;
    ignoredDirectories: string[];
    truncated: boolean;
  };
  findings: ProjectFinding[];
  recommendations: string[];
}

interface AvailableModel {
  name: string;
  sizeBytes: number;
  sizeLabel: string;
  parameterSize: string;
  family: string;
  recommended: boolean;
  heavy: boolean;
  vision: boolean;
  preferredForVision: boolean;
}

function imageModelLabel(name: string): string {
  const normalized = name.replace(/^comfyui:/, '');
  if (normalized.toLowerCase() === 'flux-2-klein-4b.safetensors') {
    return 'FLUX.2 Klein 4B · uso geral';
  }
  if (normalized.toLowerCase() === 'flux-2-klein-4b-fp8.safetensors') {
    return 'FLUX.2 Klein 4B · uso geral · FP8';
  }
  if (normalized.toLowerCase().includes('realistic_vision_v6')) {
    return 'Realistic Vision V6 · fotografia local';
  }
  if (normalized.toLowerCase().includes('realvisxl')) {
    return 'RealVisXL V5 · fidelidade e fotografia';
  }
  return normalized;
}

function isFlux2ImageModel(name: string): boolean {
  return name.replace(/^comfyui:/, '').toLowerCase().includes('flux-2-klein');
}


interface ProjectCommandResult {
  runner: string;
  name: string;
  command: string;
  exitCode: number;
  timedOut: boolean;
  durationSeconds: number;
  output: string;
  finishedAt: string;
}

interface RunningProcess {
  processId: string;
  command: string;
  logs: string;
  running: boolean;
  exitCode: number | null;
}

interface McpConnectStatus {
  connectedServers: string[];
  warnings: string[];
  environment?: {
    osName?: string;
    osArch?: string;
    osVersion?: string;
    macOs?: boolean;
    windows?: boolean;
    linux?: boolean;
    commands?: Record<string, boolean>;
    apps?: Record<string, boolean>;
    mcp?: Record<string, boolean>;
  };
}

interface AgentTimelineItem {
  id: number;
  runId: string;
  approvalId?: string | null;
  eventType: string;
  toolName?: string | null;
  detail?: string | null;
  payload?: string | null;
  createdAt: string;
}

interface AgentTimelineResponse {
  events: AgentTimelineItem[];
}

// NOTA: este valor é enviado como uma mensagem role:"system", mas o backend
// (AgentService.compactMessagesForModel) descarta TODA mensagem "system" vinda do frontend antes
// de chamar o Ollama — de propósito, para não confiar cegamente em conteúdo controlado pelo
// cliente. O prompt que o modelo realmente recebe é AGENT_SYSTEM_PROMPT em
// src/main/java/com/avento/service/AgentService.java. Edite lá, não aqui, se quiser mudar o
// comportamento do agente. Este valor só existe para inicializar o estado local `messages` (não é
// renderizado na UI, ver o filtro role !== 'system' no render das mensagens).
const SYSTEM_PROMPT = 'Avento — o prompt real do agente vive no backend (AgentService.AGENT_SYSTEM_PROMPT).';

function sanitizeSpeechText(text: string): string {
  return text
    .replace(/\[\[avento-image-job:[0-9a-f-]{36}\]\]/gi, ' ')
    .replace(/\[\[avento-video-job:[0-9a-f-]{36}\]\]/gi, ' ')
    .replace(/```[\s\S]*?```/g, ' bloco de código omitido. ')
    .replace(/~~~[\s\S]*?~~~/g, ' bloco de código omitido. ')
    .replace(/!\[([^\]]*)\]\([^)]+\)/g, '$1')
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/^\s{0,3}>\s?/gm, '')
    .replace(/^\s*[-+*]\s+/gm, '')
    .replace(/^\s*\d+[.)]\s+/gm, '')
    .replace(/[*_~]{1,3}/g, '')
    .replace(/<[^>]+>/g, '')
    .replace(/^\s*-{3,}\s*$/gm, '')
    .replace(/[\u{1F300}-\u{1FAFF}\u{1F1E6}-\u{1F1FF}\u{2600}-\u{27BF}]/gu, '')
    .replace(/[\uFE0E\uFE0F\u200D]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

export function extractFileEditContent(messageContent: string, targetPath: string): string {
  const regex = /```file-edit\n([\s\S]*?)```/g;
  let match;
  while ((match = regex.exec(messageContent)) !== null) {
    const blockContent = match[1];
    const lines = blockContent.split('\n');
    let path = '';
    if (lines[0] && lines[0].startsWith('path: ')) {
      path = lines[0].replace('path: ', '').trim();
    }
    if (path === targetPath) {
      const separatorIdx = lines.findIndex(l => l.startsWith('---'));
      if (separatorIdx !== -1) {
        return lines.slice(separatorIdx + 1).join('\n');
      }
      return blockContent;
    }
  }
  // Fallback case when streaming is incomplete and closing ``` is missing
  if (!messageContent.includes('```file-edit')) return '';
  const lastBlockIdx = messageContent.lastIndexOf('```file-edit');
  const blockContent = messageContent.slice(lastBlockIdx + 12).trimStart();
  const lines = blockContent.split('\n');
  let path = '';
  if (lines[0] && lines[0].startsWith('path: ')) {
    path = lines[0].replace('path: ', '').trim();
  }
  if (path === targetPath || !path) { // Se não tiver path ainda, assume o atual
    const separatorIdx = lines.findIndex(l => l.startsWith('---'));
    if (separatorIdx !== -1) {
      return lines.slice(separatorIdx + 1).join('\n');
    }
  }
  return '';
}

function parsePlanLines(block: string): string[] {
  return block
    .split('\n')
    .map(line => line.replace(/^\s*\d+[.)]\s*/, '').trim())
    .filter(Boolean);
}

// O modelo local nem sempre segue a instrução de usar o bloco ```plan```
// (mesmo problema de confiabilidade de formatação já visto em outras partes
// do agente) — às vezes escreve os passos como lista numerada solta no
// texto. Esse fallback pega essa lista mesmo assim. Só o bloco ```plan```
// explícito é escondido do chat (ver MessageBubble); uma lista numerada
// solta pode ser só uma explicação normal, não necessariamente um plano de
// execução, então por segurança ela continua visível no chat e só é
// duplicada no painel — nunca removida.
function extractLooseNumberedList(messageContent: string): string[] | null {
  const lines = messageContent.split('\n');
  const numberedLine = /^\s*\d+[.)]\s+(.+)$/;
  const steps: string[] = [];
  for (const line of lines) {
    const match = line.match(numberedLine);
    if (match) {
      steps.push(match[1].trim());
    } else if (steps.length > 0 && line.trim() !== '') {
      break;
    }
  }
  return steps.length >= 2 ? steps : null;
}

// Extrai os passos de um bloco ```plan``` da resposta do agente (ver
// agent/instructions/execution.md). Esse bloco nunca deve aparecer como
// texto solto no chat — os passos vão pro painel "Tarefas e Contexto"
// (ver renderAgentActivityPanel). Trata o caso de streaming incompleto
// (bloco ainda sem ``` de fechamento) igual ao extractFileEditContent acima.
function extractPlanSteps(messageContent: string): string[] | null {
  const closedMatch = messageContent.match(/```plan\n([\s\S]*?)```/);
  if (closedMatch) {
    const steps = parsePlanLines(closedMatch[1]);
    if (steps.length > 0) return steps;
  }

  const openIdx = messageContent.lastIndexOf('```plan');
  if (openIdx !== -1) {
    const steps = parsePlanLines(messageContent.slice(openIdx + '```plan'.length));
    if (steps.length > 0) return steps;
  }

  return extractLooseNumberedList(messageContent);
}

function buildProjectContextText(
  projectAnalysis: ProjectAnalysis | null,
  commandResults: ProjectCommandResult[]
): string {
  const parts: string[] = [];

  if (projectAnalysis) {
    const findings = projectAnalysis.findings
      .slice(0, 8)
      .map(finding => `- [${finding.severity}] ${finding.title}: ${finding.detail}`)
      .join('\n');
    const scripts = projectAnalysis.scripts
      .slice(0, 8)
      .map(script => `- ${script.runner}:${script.name} (${script.path}) -> ${script.command}`)
      .join('\n');
    const recommendations = projectAnalysis.recommendations
      .slice(0, 6)
      .map(recommendation => `- ${recommendation}`)
      .join('\n');

    parts.push(`[Project Analysis]
Nome: ${projectAnalysis.projectName}
Raiz: ${projectAnalysis.rootPath}
Stack: ${projectAnalysis.technologies.length > 0 ? projectAnalysis.technologies.join(', ') : 'Nao detectada'}
Arquivos analisados: ${projectAnalysis.fileStats.totalFiles}${projectAnalysis.fileStats.truncated ? ' (varredura truncada)' : ''}
${projectAnalysis.fileStats.totalFiles === 0
  ? 'ESTADO AUTORITATIVO: esta pasta está vazia. Não invente arquivos, stack, credenciais, imagens, riscos ou resultados de comandos. Nenhum comando foi executado nesta análise.'
  : 'Use somente os arquivos e achados listados abaixo. Não diga que executou comandos ou alterou arquivos sem um resultado real de ferramenta.'}
Entrypoints:
${projectAnalysis.entrypoints.slice(0, 8).map(entrypoint => `- ${entrypoint}`).join('\n') || '- Nenhum entrypoint comum detectado'}
Scripts:
${scripts || '- Nenhum script comum detectado'}
Achados:
${findings || '- Nenhum achado inicial'}
Recomendacoes:
${recommendations || '- Nenhuma recomendacao inicial'}`);
  }

  if (commandResults.length > 0) {
    const validationSummaries = commandResults
      .slice(0, 4)
      .map(result => `Comando: ${result.command}
Exit code: ${result.exitCode}
Timeout: ${result.timedOut ? 'sim' : 'nao'}
Duracao: ${result.durationSeconds}s
Saida final:
\`\`\`
${result.output.slice(-2500) || '(sem saida)'}
\`\`\``)
      .join('\n\n');

    parts.push(`[Recent Validation Results]
${validationSummaries}`);
  }

  if (parts.length === 0) {
    return '';
  }

  return `${parts.join('\n\n')}\n\nUse este contexto como informacao factual do estado atual do projeto. Se precisar de detalhes de arquivos, use as ferramentas antes de concluir.`;
}

function buildWorkspaceContextText(projectPaths: string[], homeWorkspaceRoot: string | null): string {
  const allRoots = [...(projectPaths || [])];
  if (homeWorkspaceRoot && !allRoots.includes(homeWorkspaceRoot)) {
    allRoots.push(homeWorkspaceRoot);
  }
  if (allRoots.length === 0) {
    return '';
  }

  return `[Workspace Roots]
${allRoots.map(path => `- ${path}`).join('\n')}

Estas pastas ja foram informadas pelo usuario na interface do Avento. Nao responda que voce nao tem acesso ao sistema. Use a analise local disponivel e, quando precisar de detalhes, use ferramentas ou peça arquivos especificos.`;
}

function buildEnvironmentContextText(mcpStatus: McpConnectStatus | null): string {
  if (!mcpStatus?.environment) {
    return '';
  }

  const environment = mcpStatus.environment;
  const connectedServers = mcpStatus.connectedServers.length > 0
    ? mcpStatus.connectedServers.join(', ')
    : 'nenhum MCP externo conectado';
  const availableApps = Object.entries(environment.apps || {})
    .filter(([, available]) => available)
    .map(([name]) => name)
    .join(', ') || 'nenhum app detectado';
  const availableCommands = Object.entries(environment.commands || {})
    .filter(([, available]) => available)
    .map(([name]) => name)
    .join(', ') || 'nenhum comando detectado';
  const warnings = mcpStatus.warnings.length > 0
    ? mcpStatus.warnings.map(warning => `- ${warning}`).join('\n')
    : '- Nenhum aviso';

  return `[Local Environment]
OS: ${environment.osName || 'desconhecido'} ${environment.osVersion || ''} (${environment.osArch || 'arch desconhecida'})
macOS: ${environment.macOs ? 'sim' : 'nao'} | Windows: ${environment.windows ? 'sim' : 'nao'} | Linux: ${environment.linux ? 'sim' : 'nao'}
Comandos disponiveis: ${availableCommands}
Apps detectados: ${availableApps}
MCPs conectados: ${connectedServers}
Avisos:
${warnings}

Use este bloco para escolher ferramentas compatíveis com o ambiente atual. Se o usuário pedir Brave, VS Code, Finder, Terminal ou navegador, primeiro confira apps/MCPs conectados aqui.`;
}

function extractLikelyAbsolutePath(text: string): string | null {
  const quotedMatch = text.match(/["'](\/(?:Users|Volumes|tmp|var|private)\/[^"']+)["']/);
  const rawPath = quotedMatch?.[1] || text.match(/\/(?:Users|Volumes|tmp|var|private)\/[^\n\r`"'<>]+/)?.[0];
  if (!rawPath) return null;
  return rawPath.replace(/[),.;:!?]+$/, '').trim();
}

function normalizeIntentText(text: string): string {
  return text
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^\p{L}\p{N}\s]/gu, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function activityTitleForEventType(eventType: string, toolName?: string | null): string {
  if (eventType === 'tool.approval.required') return `Aprovação necessária${toolName ? ` - ${toolName}` : ''}`;
  if (eventType === 'tool.approval.accepted') return 'Aprovação recebida';
  if (eventType === 'tool.rejected') return 'Ação cancelada';
  if (eventType === 'tool.started') return `Executando ${toolName || 'ferramenta'}`;
  if (eventType === 'tool.completed') return 'Ferramenta concluída';
  if (eventType === 'tool.failed') return 'Ferramenta falhou';
  return eventType;
}

function parseTimelinePayload(payload?: string | null): Record<string, unknown> | undefined {
  if (!payload) return undefined;
  try {
    const parsed = JSON.parse(payload);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : undefined;
  } catch {
    return undefined;
  }
}

function timelineItemToActivityEvent(item: AgentTimelineItem): AgentActivityEvent {
  return {
    type: item.eventType,
    title: activityTitleForEventType(item.eventType, item.toolName),
    detail: item.detail || item.toolName || '',
    timestamp: item.createdAt,
    approvalId: item.approvalId || undefined,
    toolName: item.toolName || undefined,
    toolArguments: parseTimelinePayload(item.payload),
    runId: item.runId,
  };
}

function isCasualMessage(text: string): boolean {
  const normalized = normalizeIntentText(text);
  if (!normalized || normalized.length > 80) {
    return false;
  }

  const projectActionWords = [
    'analisa', 'analisar', 'projeto', 'codigo', 'arquivo', 'pasta', 'cria',
    'criar', 'corrige', 'corrigir', 'roda', 'rodar', 'executa', 'terminal',
    'abre', 'abrir', 'app', 'aplicativo', 'atalho', 'browser', 'finder',
    'navegador', 'vite', 'react', 'mcp', 'docker', 'commit', 'erro', 'falha'
  ];
  if (projectActionWords.some(word => normalized.includes(word))) {
    return false;
  }

  const casualPhrases = [
    'oi', 'ola', 'comervais', 'comer vais', 'bom dia', 'boa tarde', 'boa noite', 'e ai', 'fala',
    'salve', 'hey', 'hello', 'hi', 'tudo bem', 'beleza', 'valeu',
    'obrigado', 'obrigada', 'como voce esta', 'como vc esta',
    'como voce ta', 'como vc ta', 'como vai', 'como vais',
    'o que voce pode fazer', 'o que vc pode fazer', 'o q voce pode fazer', 'o q vc pode fazer',
    'que voce pode fazer', 'que vc pode fazer', 'o que mais voce pode fazer', 'o que mais vc pode fazer',
    'com o que voce pode me ajudar', 'com o que vc pode me ajudar', 'com o q voce pode me ajudar', 'com o q vc pode me ajudar',
    'com que voce pode me ajudar', 'com que vc pode me ajudar', 'com q voce pode me ajudar', 'com q vc pode me ajudar',
    'no que voce pode me ajudar', 'no que vc pode me ajudar', 'no q voce pode me ajudar', 'no q vc pode me ajudar',
    'como voce pode me ajudar', 'como vc pode me ajudar',
    'quem e voce', 'quem voce e', 'explica para meu amigo', 'explica para o meu amigo',
    'portugues brasileiro natural', 'tudo certo',
    'ta tudo bem', 'esta tudo bem'
  ];
  return casualPhrases.some(phrase => normalized === phrase || normalized.startsWith(`${phrase} `));
}

function buildValidationFailurePrompt(commandResults: ProjectCommandResult[]): string {
  const failedResults = commandResults.filter(result => result.exitCode !== 0 || result.timedOut);
  if (failedResults.length === 0) {
    return 'Revise os resultados recentes de validação e indique se ainda existe algum risco ou próximo passo técnico relevante.';
  }

  const failureContext = failedResults
    .slice(0, 4)
    .map(result => `Comando: ${result.command}
Exit code: ${result.exitCode}
Timeout: ${result.timedOut ? 'sim' : 'nao'}
Duracao: ${result.durationSeconds}s
Saida:
\`\`\`
${result.output.slice(-5000) || '(sem saida)'}
\`\`\``)
    .join('\n\n');

  return `Analise as falhas de validação abaixo. Explique a causa provável, priorize o que corrigir primeiro e proponha um plano de patch seguro. Se precisar alterar código, leia os arquivos reais com ferramentas antes de sugerir file-edit.

${failureContext}`;
}

export function Home({ isDarkMode, toggleTheme }: HomeProps) {
  const initialImagePreferences = useMemo(loadImagePreferences, []);
  const { logout } = useAuth();
  const [messages, setMessages] = useState<Message[]>([{ role: 'system', content: SYSTEM_PROMPT }]);
  const [inputValue, setInputValue] = useState<string>('');
  const [imageAttachments, setImageAttachments] = useState<ImageAttachment[]>([]);
  const [documentAttachments, setDocumentAttachments] = useState<DocumentAttachment[]>([]);
  const [isAttachingDocuments, setIsAttachingDocuments] = useState(false);
  const documentAttachmentSessionRef = useRef(0);
  const [messageQueue, setMessageQueue] = useState<QueuedMessage[]>([]);
  const isProcessingQueueRef = useRef(false);
  const [isVoiceEnabled, setIsVoiceEnabled] = useState<boolean>(loadVoiceEnabled);
  const isVoiceEnabledRef = useRef<boolean>(isVoiceEnabled);
  const [isMobileOpen, setMobileOpen] = useState<boolean>(false);
  const [selectedModel, setSelectedModel] = useState<string>('');
  const [availableModels, setAvailableModels] = useState<AvailableModel[]>([]);
  const [availableImageModels, setAvailableImageModels] = useState<AvailableModel[]>([]);
  const [isLoadingModels, setIsLoadingModels] = useState<boolean>(true);
  const [modelLoadError, setModelLoadError] = useState<string | null>(null);
  const selectedModelInfo = useMemo(
    () => availableModels.find(model => model.name === selectedModel) || null,
    [availableModels, selectedModel]
  );
  const [selectedChatTitle, setSelectedChatTitle] = useState('Nova conversa');
  const [selectedImageModel, setSelectedImageModel] = useState('');
  const [imageQualityPreset, setImageQualityPreset] = useState(initialImagePreferences.qualityPreset);
  const [imageAspectRatio, setImageAspectRatio] = useState(initialImagePreferences.aspectRatio);
  const [imageSubjectType, setImageSubjectType] = useState(initialImagePreferences.subjectType);
  const [imageSubjectCount, setImageSubjectCount] = useState(initialImagePreferences.subjectCount);
  const [enhanceImagePrompt, setEnhanceImagePrompt] = useState(initialImagePreferences.enhancePrompt);
  const [imageRefinementEnabled, setImageRefinementEnabled] = useState(initialImagePreferences.refinementEnabled);
  const [imageRefinementStrength, setImageRefinementStrength] = useState(initialImagePreferences.refinementStrength);
  const [imageDetailMode, setImageDetailMode] = useState(initialImagePreferences.detailMode);
  const [imageCfgScale, setImageCfgScale] = useState(initialImagePreferences.cfgScale);
  const [imageReferenceStrength, setImageReferenceStrength] = useState(initialImagePreferences.referenceStrength);
  const [imageReferenceMode, setImageReferenceMode] = useState(initialImagePreferences.referenceMode);
  const [imageStructureControl, setImageStructureControl] = useState(initialImagePreferences.structureControl);
  const [imageStructureStrength, setImageStructureStrength] = useState(initialImagePreferences.structureStrength);
  const [poseStrength, setPoseStrength] = useState(initialImagePreferences.poseStrength);
  const [adherenceValidationEnabled, setAdherenceValidationEnabled] = useState(
    initialImagePreferences.adherenceValidationEnabled
  );
  const [maxAdherenceRetries, setMaxAdherenceRetries] = useState(initialImagePreferences.maxAdherenceRetries);
  const [poseReference, setPoseReference] = useState<{ name: string; dataUrl: string } | null>(null);
  const [lockImageSeed, setLockImageSeed] = useState(initialImagePreferences.lockSeed);
  const [imageSeed, setImageSeed] = useState(initialImagePreferences.seed || 42);
  const [snackbarMessage, setSnackbarMessage] = useState<string | null>(null);
  const lastMcpWarningsRef = useRef('');

  // Right Panel State
  const [isRightPanelOpen, setIsRightPanelOpen] = useState<boolean>(false);
  const [isSkillsManagerOpen, setIsSkillsManagerOpen] = useState<boolean>(false);
  const [isMcpToolsManagerOpen, setIsMcpToolsManagerOpen] = useState<boolean>(false);
  const [isHeaderMenuOpen, setIsHeaderMenuOpen] = useState<boolean>(false);
  const { skills, createSkill, deleteSkill } = useSkills();
  const [activeDiff, setActiveDiff] = useState<{path: string, messageIndex: number} | null>(null);
  const [projectAnalysis, setProjectAnalysis] = useState<ProjectAnalysis | null>(null);
  const [isAnalyzingProject, setIsAnalyzingProject] = useState<boolean>(false);
  const [projectAnalysisError, setProjectAnalysisError] = useState<string | null>(null);
  const [activityEvents, setActivityEvents] = useState<AgentActivityEvent[]>([]);
  const [isLoadingTimeline, setIsLoadingTimeline] = useState<boolean>(false);
  const [runningProcesses, setRunningProcesses] = useState<Record<string, RunningProcess>>({});
  const processPollTimersRef = useRef<Record<string, ReturnType<typeof setInterval>>>({});
  const [pendingApproval, setPendingApproval] = useState<PendingApproval | null>(null);
  const [approvalLoadingId, setApprovalLoadingId] = useState<string | null>(null);
  const [runningCommandKey, setRunningCommandKey] = useState<string | null>(null);
  const [commandResult, setCommandResult] = useState<ProjectCommandResult | null>(null);
  const previousProjectPathsRef = useRef<string[]>([]);
  const [commandResults, setCommandResults] = useState<ProjectCommandResult[]>([]);
  const [commandError, setCommandError] = useState<string | null>(null);
  const [mcpStatus, setMcpStatus] = useState<McpConnectStatus | null>(null);
  const [media, setMedia] = useState<GeneratedMedia[]>([]);
  const [selectedMedia, setSelectedMedia] = useState<GeneratedMedia | null>(null);
  const visibleMedia = media;
  const imageGenerationOptions = useMemo<ImageGenerationOptions>(() => ({
    qualityPreset: imageQualityPreset,
    aspectRatio: imageAspectRatio,
    subjectType: imageSubjectType,
    subjectCount: imageSubjectCount,
    enhancePrompt: enhanceImagePrompt,
    refinementEnabled: imageRefinementEnabled,
    refinementStrength: imageRefinementStrength,
    detailMode: imageDetailMode,
    cfgScale: imageCfgScale,
    referenceStrength: imageReferenceStrength,
    referenceMode: imageReferenceMode,
    structureControl: imageStructureControl,
    structureStrength: imageStructureStrength,
    poseStrength,
    adherenceValidationEnabled,
    maxAdherenceRetries,
    ...(poseReference ? { poseReferenceDataUrl: poseReference.dataUrl } : {}),
    ...(lockImageSeed ? { seed: Math.max(0, Math.trunc(imageSeed)) } : {}),
  }), [imageQualityPreset, imageAspectRatio, imageSubjectType, imageSubjectCount, enhanceImagePrompt, imageRefinementEnabled,
    imageRefinementStrength, imageDetailMode, imageCfgScale, imageReferenceStrength, imageReferenceMode,
    imageStructureControl, imageStructureStrength, poseStrength, adherenceValidationEnabled,
    maxAdherenceRetries, poseReference, lockImageSeed, imageSeed]);

  useEffect(() => {
    window.localStorage.setItem(IMAGE_PREFERENCES_KEY, JSON.stringify({
      qualityPreset: imageGenerationOptions.qualityPreset,
      aspectRatio: imageGenerationOptions.aspectRatio,
      subjectType: imageGenerationOptions.subjectType,
      subjectCount: imageGenerationOptions.subjectCount,
      enhancePrompt: imageGenerationOptions.enhancePrompt,
      refinementEnabled: imageGenerationOptions.refinementEnabled,
      refinementStrength: imageGenerationOptions.refinementStrength,
      detailMode: imageGenerationOptions.detailMode,
      cfgScale: imageGenerationOptions.cfgScale,
      referenceStrength: imageGenerationOptions.referenceStrength,
      referenceMode: imageGenerationOptions.referenceMode,
      structureControl: imageGenerationOptions.structureControl,
      structureStrength: imageGenerationOptions.structureStrength,
      poseStrength: imageGenerationOptions.poseStrength,
      adherenceValidationEnabled: imageGenerationOptions.adherenceValidationEnabled,
      maxAdherenceRetries: imageGenerationOptions.maxAdherenceRetries,
      lockSeed: lockImageSeed,
      seed: Math.max(0, Math.trunc(imageSeed)),
    }));
  }, [imageGenerationOptions, lockImageSeed, imageSeed]);

  // Plano da última resposta do assistente (ver extractPlanSteps). Some
  // sozinho assim que uma mensagem nova é enviada, porque deixa de ser a
  // última mensagem do array.
  const currentPlanSteps = useMemo(() => {
    for (let i = messages.length - 1; i >= 0; i -= 1) {
      if (messages[i].role === 'assistant') {
        return extractPlanSteps(messages[i].content);
      }
      if (messages[i].role === 'user') {
        return null;
      }
    }
    return null;
  }, [messages]);
  const hasCurrentPlan = Boolean(currentPlanSteps && currentPlanSteps.length > 0);
  const hadCurrentPlanRef = useRef(false);

  useEffect(() => {
    if (!hasCurrentPlan) {
      hadCurrentPlanRef.current = false;
      return;
    }

    if (!hadCurrentPlanRef.current) {
      setIsRightPanelOpen(true);
    }
    hadCurrentPlanRef.current = true;
  }, [hasCurrentPlan]);

  const chatEndRef = useRef<HTMLDivElement>(null);
  const headerMenuRef = useRef<HTMLDivElement>(null);
  const speechBufferRef = useRef<string>('');
  const isGeneratingRef = useRef<boolean>(false);
  const messagesRef = useRef<Message[]>(messages);
  const voiceInterruptionActiveRef = useRef<boolean>(false);

  useEffect(() => {
    if (!isHeaderMenuOpen) return;

    const closeOnPointerDown = (event: PointerEvent) => {
      if (event.target instanceof Node && !headerMenuRef.current?.contains(event.target)) {
        setIsHeaderMenuOpen(false);
      }
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setIsHeaderMenuOpen(false);
    };

    document.addEventListener('pointerdown', closeOnPointerDown);
    document.addEventListener('keydown', closeOnEscape);
    return () => {
      document.removeEventListener('pointerdown', closeOnPointerDown);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, [isHeaderMenuOpen]);

  const {
    isRecording,
    isRealtimeVoiceActive,
    isRealtimeListening,
    realtimeTranscript,
    audioLevel,
    speechRecognitionSupported,
    startRecording,
    stopRecording,
    startRealtimeVoice,
    stopRealtimeVoice,
    stopAudioPlayback,
    setAudioPlaybackEnabled,
    queueTextToSpeech
  } = useAudioServices();

  const handleToggleVoice = (checked: boolean) => {
    isVoiceEnabledRef.current = checked;
    setIsVoiceEnabled(checked);
    setAudioPlaybackEnabled(checked);
    speechBufferRef.current = '';
    try {
      window.localStorage.setItem(VOICE_ENABLED_KEY, String(checked));
    } catch {
      // O mute continua valendo na sessão mesmo sem acesso ao storage.
    }
  };

  useEffect(() => {
    isVoiceEnabledRef.current = isVoiceEnabled;
    setAudioPlaybackEnabled(isVoiceEnabled);
  }, [isVoiceEnabled, setAudioPlaybackEnabled]);
  
  // Custom Hooks for Backend Integration
  const {
    chats, currentChatId, setCurrentChatId, loadChats, loadChatMessages, saveMessageToDB, updateChatContext, deleteChat
  } = useChatHistory();
  const currentChatIdRef = useRef<number | null>(currentChatId);
  const streamDraftsRef = useRef<Map<number, ChunkData>>(new Map());

  useEffect(() => {
    currentChatIdRef.current = currentChatId;
  }, [currentChatId]);

  const { notifications, unreadCount: unreadNotificationCount, markRead: markNotificationRead, markAllRead: markAllNotificationsRead } = useNotifications();

  const loadMedia = useCallback(async (chatId: number | null = currentChatId) => {
    if (chatId === null) {
      setMedia([]);
      return;
    }
    try {
      const { data } = await api.get<GeneratedMedia[]>('/api/media', { params: { chatId } });
      if (currentChatIdRef.current === chatId) {
        setMedia(Array.isArray(data) ? data : []);
      }
    } catch (error) {
      console.error('Erro ao carregar mídias geradas', error);
      if (currentChatIdRef.current === chatId) {
        setMedia([]);
      }
    }
  }, [currentChatId]);

  useEffect(() => {
    loadMedia();
  }, [loadMedia]);

  useEffect(() => {
    const handleMediaCompleted = (event: Event) => {
      const mediaUrl = (event as CustomEvent<{ mediaUrl?: string }>).detail?.mediaUrl;
      if (!mediaUrl) return;
      void loadMedia();
    };
    window.addEventListener('avento:image-completed', handleMediaCompleted);
    window.addEventListener('avento:video-completed', handleMediaCompleted);
    return () => {
      window.removeEventListener('avento:image-completed', handleMediaCompleted);
      window.removeEventListener('avento:video-completed', handleMediaCompleted);
    };
  }, [loadMedia]);

  useEffect(() => {
    if (!selectedModelInfo?.heavy) {
      return;
    }

    const recommendedName = availableModels.find(model => model.recommended)?.name;
    setSnackbarMessage(selectedModelInfo.recommended
      ? 'Modelo maior selecionado. Feche apps pesados se o Mac ficar sem memória.'
      : `Modelo pesado selecionado.${recommendedName ? ` Para ferramentas, prefira ${recommendedName}.` : ''}`);
    const timer = window.setTimeout(() => setSnackbarMessage(null), 3200);
    return () => window.clearTimeout(timer);
  }, [selectedModelInfo?.name, selectedModelInfo?.heavy, selectedModelInfo?.recommended, availableModels]);

  useEffect(() => {
    if (!isFlux2ImageModel(selectedImageModel)) {
      return;
    }
    setSnackbarMessage(
      'FLUX.2 Klein pode suavizar prompts sensiveis. Para fotografia e maior fidelidade, selecione RealVisXL V5.'
    );
    const timer = window.setTimeout(() => setSnackbarMessage(null), 4200);
    return () => window.clearTimeout(timer);
  }, [selectedImageModel]);

  const {
    fileTree, projectPaths, setProjectPaths, removeProjectPath, clearFileTree, selectedFiles,
    homeWorkspaceRoot, clearHomeWorkspaceRoot,
    browseFolder, authorizeProjectPath, authorizeHomeFolder, loadProjectTree, toggleFileSelection, readSelectedFiles
  } = useFileSystem();

  const {
    servers: mcpServers,
    isLoading: isLoadingMcpServers,
    busyServerId: busyMcpServerId,
    error: mcpCatalogError,
    loadServers: loadMcpServers,
    connectServer: connectMcpServer,
    disconnectServer: disconnectMcpServer,
  } = useMcpCatalog(projectPaths, currentChatId);

  const showTemporaryNotice = useCallback((message: string) => {
    setSnackbarMessage(message);
    window.setTimeout(() => setSnackbarMessage(current => current === message ? null : current), 3200);
  }, []);

  // handleLoadChat sets currentChatId synchronously but only knows the correct
  // projectPaths after an async re-authorization step finishes a few lines later.
  // Without this guard, the persistence effect below fires in that window with the
  // previous chat's (or empty) projectPaths and overwrites the chat's saved path in
  // the database before the real value is set — which is why reloading the browser
  // or switching chats could silently wipe out an already-authorized project folder.
  const skipContextPersistRef = useRef(false);

  useEffect(() => {
    loadChats();
  }, [loadChats]);

  const loadRecentAgentTimeline = useCallback(async () => {
    setIsLoadingTimeline(true);
    try {
      const { data } = await api.get<AgentTimelineResponse>('/api/agent/timeline');
      const events = Array.isArray(data.events)
        ? data.events.map(timelineItemToActivityEvent).slice(0, 30)
        : [];
      setActivityEvents(events);
    } catch (error) {
      console.error('Erro ao carregar timeline do agente', error);
    } finally {
      setIsLoadingTimeline(false);
    }
  }, []);

  useEffect(() => {
    loadRecentAgentTimeline();
  }, [loadRecentAgentTimeline]);

  useEffect(() => {
    if (currentChatId === null || skipContextPersistRef.current) {
      return;
    }

    updateChatContext(currentChatId, projectPaths).catch(error => {
      console.error('Erro ao salvar contexto do chat', error);
    });
  }, [currentChatId, projectPaths, updateChatContext]);

  useEffect(() => {
    let isMounted = true;

    async function loadModels() {
      setIsLoadingModels(true);
      setModelLoadError(null);
      try {
        const [{ data }, { data: imageData }] = await Promise.all([
          api.get<AvailableModel[]>('/api/ai/models/details'),
          api.get<AvailableModel[]>('/api/ai/models/images'),
        ]);
        const models = Array.isArray(data)
          ? data.filter((model): model is AvailableModel => (
              model &&
              typeof model.name === 'string' &&
              model.name.trim().length > 0
            ))
          : [];

        if (!isMounted) return;
        setAvailableModels(models);
        const imageModels = Array.isArray(imageData)
          ? imageData.filter((model): model is AvailableModel => (
              model && typeof model.name === 'string' && model.name.trim().length > 0
            ))
          : [];
        setAvailableImageModels(imageModels);
        setSelectedImageModel(current => (
          current && imageModels.some(model => model.name === current)
            ? current
            : imageModels.find(model => model.recommended)?.name || imageModels[0]?.name || ''
        ));
        setSelectedModel(current => {
          if (current && models.some(model => model.name === current)) {
            return current;
          }
          return models.find(model => model.recommended)?.name ||
            models.find(model => !model.heavy)?.name ||
            models[0]?.name ||
            '';
        });
      } catch (err) {
        console.error('Erro ao buscar modelos do Ollama', err);
        if (!isMounted) return;
        setAvailableModels([]);
        setAvailableImageModels([]);
        setSelectedImageModel('');
        setSelectedModel('');
        setModelLoadError('Erro ao carregar modelos');
      } finally {
        if (isMounted) {
          setIsLoadingModels(false);
        }
      }
    }

    loadModels();
    return () => {
      isMounted = false;
    };
  }, []);

  const analyzeProject = useCallback(async (path: string): Promise<ProjectAnalysis | null> => {
    if (!path) return null;

    setIsAnalyzingProject(true);
    setProjectAnalysisError(null);
    try {
      const { data: analysis } = await api.post<ProjectAnalysis>('/api/projects/analyze', { path });
      setProjectAnalysis(analysis);
      return analysis;
    } catch (err) {
      console.error('Erro ao analisar projeto', err);
      setProjectAnalysisError('Não foi possível analisar o projeto agora.');
      return null;
    } finally {
      setIsAnalyzingProject(false);
    }
  }, []);

  const updateMcpStatus = useCallback((status: any) => {
    setMcpStatus({
      connectedServers: Array.isArray(status.connectedServers) ? status.connectedServers : [],
      warnings: Array.isArray(status.warnings) ? status.warnings : [],
      environment: status.environment,
    });

    const warnings: string[] = Array.isArray(status.warnings) ? status.warnings : [];
    const warningsKey = warnings.join('|');
    if (warnings.length > 0 && warningsKey !== lastMcpWarningsRef.current) {
      lastMcpWarningsRef.current = warningsKey;
      const summary = warnings.length === 1
        ? warnings[0]
        : `${warnings[0]} (+${warnings.length - 1} outro${warnings.length > 2 ? 's' : ''})`;
      setSnackbarMessage(`Ferramenta indisponível: ${summary}`);
      window.setTimeout(() => setSnackbarMessage(null), 4200);
    }
  }, []);

  useEffect(() => {
    const previousProjectPaths = previousProjectPathsRef.current;
    const currentProjectPaths = projectPaths || [];
    const removedProjectPaths = previousProjectPaths.filter(path => !currentProjectPaths.includes(path));
    if (removedProjectPaths.length > 0) {
      api.post('/api/rag/clear', { projectPaths: removedProjectPaths })
        .catch(error => console.error('Erro ao limpar contexto RAG', error));
    }
    previousProjectPathsRef.current = currentProjectPaths;

    if (!projectPaths || projectPaths.length === 0) {
      setProjectAnalysis(null);
      setProjectAnalysisError(null);
      setCommandResult(null);
      setCommandResults([]);
      setCommandError(null);

      let isCancelled = false;
      api.post('/api/mcp/connect', { projectPaths: [], chatId: currentChatId })
        .then(({ data: status }) => {
          if (!isCancelled) {
            updateMcpStatus(status);
            loadMcpServers();
          }
        })
        .catch(error => {
          console.error('Erro ao conectar MCP sem projeto', error);
          if (!isCancelled) {
            setMcpStatus(null);
          }
        });

      return () => {
        isCancelled = true;
      };
    }
    
    let isCancelled = false;

    async function prepareProjectContext() {
      try {
        const { data: status } = await api.post('/api/mcp/connect', { projectPaths, chatId: currentChatId });
        if (!isCancelled) {
          updateMcpStatus(status);
          loadMcpServers();
        }
      } catch (err) {
        console.error('Erro ao conectar MCP', err);
        if (!isCancelled) {
          setMcpStatus(null);
          setProjectAnalysisError('Não foi possível conectar as ferramentas ao projeto. Verifique se a pasta ainda existe.');
        }
      }

      api.post('/api/rag/index', { projectPaths })
        .catch(err => console.error('Erro ao acionar a indexação RAG', err));
    }

    prepareProjectContext();

    setIsAnalyzingProject(true);
    setProjectAnalysisError(null);
    setCommandResult(null);
    setCommandResults([]);
    setCommandError(null);
    analyzeProject(projectPaths[0]);

    return () => {
      isCancelled = true;
    };
  }, [analyzeProject, currentChatId, loadMcpServers, projectPaths, updateMcpStatus]);

  const handleChunkReceived = (chunkData: ChunkData, streamContext: ChatStreamContext) => {
    if (streamContext.chatId !== null) {
      streamDraftsRef.current.set(streamContext.chatId, chunkData);
    }
    if (streamContext.chatId !== currentChatIdRef.current) {
      return;
    }

    if (isVoiceEnabledRef.current && isRealtimeVoiceActive && !voiceInterruptionActiveRef.current) {
      speechBufferRef.current += chunkData.newText || '';
      const shouldSpeakSentence = /[.!?]\s*$/.test(speechBufferRef.current) && speechBufferRef.current.length > 30;
      if (shouldSpeakSentence || chunkData.isFinal) {
        const speechText = sanitizeSpeechText(speechBufferRef.current);
        speechBufferRef.current = '';
        if (speechText) {
          queueTextToSpeech(speechText);
        }
      }
    }

    setMessages(prev => {
      const generatedJobIds = imageJobIds(chunkData.content);
      const currentMessages = generatedJobIds.size > 0
        ? prev.filter(message => !isRestoredImageJobMessage(message, generatedJobIds))
        : prev;
      const lastMsg = currentMessages[currentMessages.length - 1];

      // Cria um objeto novo em vez de mutar lastMsg no lugar: MessageBubble é
      // memoizado por referência, então mutar o objeto existente faria a
      // bolha da mensagem parar de atualizar visualmente durante o streaming.
      if (lastMsg && lastMsg.role === 'assistant') {
        const updatedLastMsg: Message = {
          ...lastMsg,
          content: chunkData.content,
          thinking: chunkData.thinking || lastMsg.thinking,
          duration: chunkData.duration,
          tokens: chunkData.tokens.toString()
        };
        return [...currentMessages.slice(0, -1), updatedLastMsg];
      }

      return [...currentMessages, {
        role: 'assistant',
        content: chunkData.content,
        thinking: chunkData.thinking || undefined,
        duration: chunkData.duration,
        tokens: chunkData.tokens.toString()
      }];
    });
  };

  const stopPollingProcess = useCallback((processId: string) => {
    const timer = processPollTimersRef.current[processId];
    if (timer) {
      clearInterval(timer);
      delete processPollTimersRef.current[processId];
    }
  }, []);

  const pollProcessLogs = useCallback(async (processId: string) => {
    try {
      const { data } = await api.get(`/api/mcp/processes/${encodeURIComponent(processId)}/logs`, {
        params: { maxChars: 8000 }
      });
      const stillRunning = Boolean(data.running);
      setRunningProcesses(prev => {
        const existing = prev[processId];
        if (!existing) return prev;
        return {
          ...prev,
          [processId]: {
            ...existing,
            logs: typeof data.logs === 'string' ? data.logs : existing.logs,
            running: stillRunning,
            exitCode: stillRunning ? null : (typeof data.exitCode === 'number' ? data.exitCode : existing.exitCode)
          }
        };
      });
      if (!stillRunning) {
        stopPollingProcess(processId);
      }
    } catch (e) {
      console.error('Erro ao consultar logs do processo', processId, e);
    }
  }, [stopPollingProcess]);

  const startPollingProcess = useCallback((processId: string, command: string) => {
    setRunningProcesses(prev => ({
      ...prev,
      [processId]: prev[processId] || { processId, command, logs: '', running: true, exitCode: null }
    }));
    stopPollingProcess(processId);
    pollProcessLogs(processId);
    processPollTimersRef.current[processId] = setInterval(() => pollProcessLogs(processId), 2000);
  }, [pollProcessLogs, stopPollingProcess]);

  const handleStopProcess = useCallback(async (processId: string) => {
    stopPollingProcess(processId);
    try {
      const { data } = await api.post(`/api/mcp/processes/${encodeURIComponent(processId)}/stop`);
      setRunningProcesses(prev => {
        const existing = prev[processId];
        if (!existing) return prev;
        return {
          ...prev,
          [processId]: {
            ...existing,
            logs: typeof data.logs === 'string' ? data.logs : existing.logs,
            running: false,
            exitCode: 0
          }
        };
      });
    } catch (e) {
      console.error('Erro ao parar processo', processId, e);
    }
  }, [stopPollingProcess]);

  useEffect(() => {
    return () => {
      Object.values(processPollTimersRef.current).forEach(clearInterval);
    };
  }, []);

  const handleActivityEvent = (event: AgentActivityEvent, streamContext: ChatStreamContext) => {
    if (streamContext.chatId !== currentChatIdRef.current) {
      return;
    }
    setActivityEvents(prev => [event, ...prev].slice(0, 30));

    if (event.type === 'tool.completed' && event.toolName === 'terminal_start' && event.processId) {
      startPollingProcess(event.processId, event.command || '');
    }

    if (event.type === 'tool.completed' && event.toolName && FILESYSTEM_MUTATING_TOOLS.has(event.toolName) && projectPaths[0]) {
      loadProjectTree(projectPaths[0]);
    }

    if (event.type === 'tool.approval.required' && event.approvalId) {
      const approval = {
        approvalId: event.approvalId,
        toolName: event.toolName || 'unknown',
        toolArguments: event.toolArguments || {}
      };
      setPendingApproval(approval);
      setMessages(prev => {
        const next = [...prev];
        let index = next.length - 1;
        while (index >= 0 && next[index].role !== 'assistant') {
          index -= 1;
        }
        if (index < 0) {
          next.push({ role: 'assistant', content: '', approval });
          return next;
        }
        next[index] = {
          ...next[index],
          approval: {
            ...approval,
            status: 'pending'
          }
        };
        return next;
      });
    }

    if (event.type === 'tool.approval.accepted' && pendingApproval) {
      setMessages(prev => prev.map(message => (
        message.approval?.approvalId === pendingApproval.approvalId
          ? { ...message, approval: { ...message.approval, status: 'running' } }
          : message
      )));
    }

    if (['tool.completed', 'tool.failed', 'agent.round.completed', 'tool.rejected', 'tool.approval.already_completed'].includes(event.type)) {
      const finalStatus = event.type === 'tool.rejected' ? 'rejected' : 'completed';
      setMessages(prev => prev.map(message => (
        message.approval && (!pendingApproval || message.approval.approvalId === pendingApproval.approvalId)
          ? { ...message, approval: { ...message.approval, status: finalStatus } }
          : message
      )));
      setPendingApproval(null);
      setApprovalLoadingId(null);
    }
  };

  const handleResumedRunCompleted = useCallback(async (response: string, context: ChatStreamContext) => {
    if (context.chatId === null || !response.trim()) return;
    await saveMessageToDB('assistant', response, undefined, undefined, context.chatId);
    streamDraftsRef.current.delete(context.chatId);
    if (response.includes('[[avento-image-job:') || response.includes('/api/media/')) {
      await loadMedia(context.chatId);
    }
  }, [loadMedia, saveMessageToDB]);

  const { sendMessage, sendApproval, generatingChatIds, abortGeneration, resumeChat } = useChatStream(
    handleChunkReceived,
    handleActivityEvent,
    handleResumedRunCompleted,
  );
  const isGenerating = currentChatId !== null && generatingChatIds.has(currentChatId);

  useEffect(() => {
    isGeneratingRef.current = isGenerating;
  }, [isGenerating]);

  // Envia a proxima mensagem da fila assim que o Avento termina de responder.
  // Mensagens mandadas enquanto ele ainda esta gerando nao sao mais
  // descartadas (handleSend as enfileira em vez de ignorar).
  useEffect(() => {
    if (isGenerating || messageQueue.length === 0 || isProcessingQueueRef.current) {
      return;
    }
    const [next, ...rest] = messageQueue;
    isProcessingQueueRef.current = true;
    setMessageQueue(rest);
    handleSend(next.text, { force: true, images: next.images, documents: next.documents }).finally(() => {
      isProcessingQueueRef.current = false;
    });
  }, [isGenerating, messageQueue]);

  const removeFromQueue = useCallback((id: string) => {
    setMessageQueue(prev => prev.filter(item => item.id !== id));
  }, []);

  // A fila é só da conversa atual — trocar de chat ou começar uma nova não
  // pode deixar uma mensagem enfileirada vazar para dentro da conversa
  // seguinte (era exatamente isso que estava acontecendo antes).
  const discardMessageQueue = useCallback(() => {
    if (messageQueue.length > 0) {
      setSnackbarMessage(
        `${messageQueue.length} mensagem${messageQueue.length > 1 ? 's' : ''} na fila ${messageQueue.length > 1 ? 'foram descartadas' : 'foi descartada'} ao trocar de conversa.`
      );
      window.setTimeout(() => setSnackbarMessage(null), 4200);
    }
    setMessageQueue([]);
  }, [messageQueue]);

  useEffect(() => {
    messagesRef.current = messages;
  }, [messages]);

  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const handleApproveAction = useCallback(async (approvalId: string, comment: string) => {
    const approvalChatId = currentChatId;
    setApprovalLoadingId(approvalId);
    setMessages(prev => prev.map(message => (
      message.approval?.approvalId === approvalId
        ? { ...message, approval: { ...message.approval, status: 'running' as const } }
        : message
    )));
    const finalResponse = await sendApproval(approvalId, 'approve', comment, approvalChatId);
    if (finalResponse && approvalChatId) {
      await saveMessageToDB('assistant', finalResponse, undefined, undefined, approvalChatId);
      streamDraftsRef.current.delete(approvalChatId);
    }
    setApprovalLoadingId(null);
  }, [currentChatId, saveMessageToDB, sendApproval]);

  const handleRejectAction = useCallback(async (approvalId: string, comment: string) => {
    const approvalChatId = currentChatId;
    setApprovalLoadingId(approvalId);
    setMessages(prev => prev.map(message => (
      message.approval?.approvalId === approvalId
        ? { ...message, approval: { ...message.approval, status: 'rejected' as const } }
        : message
    )));
    const finalResponse = await sendApproval(approvalId, 'reject', comment, approvalChatId);
    if (finalResponse && approvalChatId) {
      await saveMessageToDB('assistant', finalResponse, undefined, undefined, approvalChatId);
      streamDraftsRef.current.delete(approvalChatId);
    }
    setPendingApproval(null);
    setApprovalLoadingId(null);
  }, [currentChatId, saveMessageToDB, sendApproval]);

  const startNewChat = useCallback(() => {
    discardMessageQueue();
    stopAudioPlayback();
    speechBufferRef.current = '';
    documentAttachmentSessionRef.current += 1;
    setImageAttachments([]);
    setDocumentAttachments([]);
    setIsAttachingDocuments(false);
    currentChatIdRef.current = null;
    setCurrentChatId(null);
    setSelectedChatTitle('Nova conversa');
    setSelectedMedia(null);
    setMessages([{ role: 'system', content: SYSTEM_PROMPT }]);
    setProjectPaths([]);
    clearFileTree();
    setProjectAnalysis(null);
    setProjectAnalysisError(null);
    setActivityEvents([]);
    setPendingApproval(null);
    setApprovalLoadingId(null);
    setCommandResult(null);
    setCommandResults([]);
    setCommandError(null);
    loadRecentAgentTimeline();
    if (isMobileOpen) setMobileOpen(false);
  }, [discardMessageQueue, loadRecentAgentTimeline, isMobileOpen, setCurrentChatId, setProjectPaths, clearFileTree, stopAudioPlayback]);

  const handleDeleteChat = useCallback(async (chat: { id: number }) => {
    if (generatingChatIds.has(chat.id)) {
      abortGeneration(chat.id);
      streamDraftsRef.current.delete(chat.id);
    }
    await deleteChat(chat.id);
    await loadMedia();
    if (currentChatId === chat.id) {
      startNewChat();
    }
  }, [abortGeneration, currentChatId, deleteChat, generatingChatIds, loadMedia, startNewChat]);

  // Identidade estável necessária para o React.memo de Sidebar/MessageBubble
  // funcionar — um handler novo a cada render invalidaria o memo mesmo com
  // as outras props iguais.
  const handleOpenMedia = useCallback((item: GeneratedMedia) => {
    setSelectedMedia(item);
    setActiveDiff(null);
    setIsRightPanelOpen(true);
  }, []);

  const handleOpenDiff = useCallback((idx: number, path: string) => {
    setActiveDiff({ path, messageIndex: idx });
    setIsRightPanelOpen(true);
  }, []);

  const handleLoadChat = useCallback(async (id: number, _title: string, paths: string[]) => {
    discardMessageQueue();
    stopAudioPlayback();
    speechBufferRef.current = '';
    documentAttachmentSessionRef.current += 1;
    setImageAttachments([]);
    setDocumentAttachments([]);
    setIsAttachingDocuments(false);
    setSelectedChatTitle(_title || 'Nova conversa');
    setSelectedMedia(null);
    skipContextPersistRef.current = true;
    try {
      currentChatIdRef.current = id;
      setCurrentChatId(id);
      setMessages([{ role: 'system', content: SYSTEM_PROMPT }]);
      const activeRunPromise = resumeChat(id);
      // Limpa primeiro; loadProjectTree (abaixo) repopula se o chat novo tiver
      // projeto. Sem isso, trocar para um chat sem projeto (ou com projeto
      // diferente, antes do fetch novo terminar) deixava a árvore/seleção de
      // arquivos do chat anterior visível na sidebar.
      clearFileTree();
      setActiveDiff(null);
      setActivityEvents([]);
      setPendingApproval(null);
      setApprovalLoadingId(null);
      setCommandResult(null);
      setCommandResults([]);
      setCommandError(null);
      loadRecentAgentTimeline();

      if (paths && paths.length > 0) {
        const validPaths = (await Promise.all(paths.map(path => authorizeProjectPath(path))))
          .filter((path): path is string => Boolean(path));

        setProjectAnalysis(null);
        if (validPaths.length > 0) {
          setProjectPaths(validPaths);
          setProjectAnalysisError(
            validPaths.length < paths.length
              ? 'Algumas pastas salvas nesse chat não existem mais e foram removidas do contexto.'
              : null
          );
          loadProjectTree(validPaths[0]);
        } else {
          setProjectPaths([]);
          setProjectAnalysisError('A pasta salva nesse chat não existe mais ou não pôde ser autorizada.');
        }
      } else {
        setProjectPaths([]);
        setProjectAnalysis(null);
        setProjectAnalysisError(null);
      }

      const draftAtLoadStart = streamDraftsRef.current.get(id);
      const [loadedMsgsResult, hasActiveRun, imageJobs] = await Promise.all([
        loadChatMessages(id),
        activeRunPromise,
        api.get<ChatImageJob[]>(`/api/images/chat/${id}`)
          .then(({ data }) => Array.isArray(data) ? data : [])
          .catch(error => {
            console.error('Erro ao restaurar jobs de imagem do chat', error);
            return [];
          }),
      ]);
      // Uma falha transitoria (backend reiniciando, rede) nao pode renderizar a conversa
      // como vazia — isso parece perda de dados. Tenta mais uma vez; persistindo a falha,
      // mostra um aviso explicito no lugar do historico.
      let recoveredMsgs = loadedMsgsResult;
      if (recoveredMsgs === null) {
        recoveredMsgs = await loadChatMessages(id);
      }
      const historyLoadFailed = recoveredMsgs === null;
      const loadedMsgs = recoveredMsgs ?? [];
      const streamDraft = streamDraftsRef.current.get(id) ?? draftAtLoadStart;
      const draftAlreadyPersisted = Boolean(streamDraft && loadedMsgs.some(message => (
        message.role === 'assistant' && message.content === streamDraft.content
      )));
      if (draftAlreadyPersisted) {
        streamDraftsRef.current.delete(id);
      }
      const persistedImageJobIds = imageJobIds(loadedMsgs.map(message => message.content).join('\n'));
      const restoredImageJobs: Message[] = imageJobs
        .filter(job => !persistedImageJobIds.has(job.id.toLowerCase()))
        .map(job => ({ role: 'assistant', content: imageJobMarker(job.id) }));
      const streamedAssistant: Message[] = streamDraft && !draftAlreadyPersisted
        ? [{
            role: 'assistant',
            content: streamDraft.content,
            thinking: streamDraft.thinking || undefined,
            duration: streamDraft.duration,
            tokens: streamDraft.tokens.toString(),
          }]
        : hasActiveRun
          ? [{ role: 'assistant', content: '' }]
        : [];
      if (currentChatIdRef.current === id) {
        const historyFailureNotice: Message[] = historyLoadFailed
          ? [{
              role: 'assistant',
              content:
                '> ⚠️ Não consegui carregar o histórico desta conversa agora. Suas mensagens continuam'
                + ' salvas — recarregue a página ou troque de chat e volte.',
            }]
          : [];
        setMessages([
          { role: 'system', content: SYSTEM_PROMPT },
          ...historyFailureNotice,
          ...loadedMsgs,
          ...restoredImageJobs,
          ...streamedAssistant,
        ]);
      }
      if (isMobileOpen) setMobileOpen(false);
    } finally {
      skipContextPersistRef.current = false;
    }
  }, [discardMessageQueue, clearFileTree, authorizeProjectPath, loadProjectTree, loadRecentAgentTimeline, loadChatMessages, isMobileOpen, resumeChat, stopAudioPlayback]);

  const handleAttachImages = async (files: FileList | null) => {
    if (!files || files.length === 0) return;

    const nextAttachments: ImageAttachment[] = [];
    const remainingSlots = MAX_IMAGE_ATTACHMENTS - imageAttachments.length;
    const selectedFiles = Array.from(files).slice(0, Math.max(0, remainingSlots));

    for (const file of selectedFiles) {
      if (!file.type.startsWith('image/')) continue;
      if (file.size > MAX_IMAGE_ATTACHMENT_BYTES) {
        setCommandError(`Imagem ignorada: ${file.name} passa de 6MB.`);
        continue;
      }

      const dataUrl = await new Promise<string>((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(String(reader.result || ''));
        reader.onerror = () => reject(reader.error);
        reader.readAsDataURL(file);
      });
      const base64 = dataUrl.includes(',') ? dataUrl.split(',')[1] : dataUrl;
      nextAttachments.push({
        id: `${file.name}-${file.size}-${file.lastModified}-${crypto.randomUUID()}`,
        name: file.name,
        mimeType: file.type,
        dataUrl,
        base64,
      });
    }

    if (nextAttachments.length > 0) {
      setImageAttachments(prev => [...prev, ...nextAttachments].slice(0, MAX_IMAGE_ATTACHMENTS));
    }
  };

  const handleAttachDocuments = async (files: FileList | null) => {
    if (!files || files.length === 0) return;
    const attachmentSession = documentAttachmentSessionRef.current;

    const remainingSlots = MAX_DOCUMENT_ATTACHMENTS - documentAttachments.length;
    const selectedFiles = Array.from(files).slice(0, Math.max(0, remainingSlots));
    if (selectedFiles.length === 0) {
      setSnackbarMessage(`Você pode anexar até ${MAX_DOCUMENT_ATTACHMENTS} documentos por mensagem.`);
      return;
    }

    setIsAttachingDocuments(true);
    const extracted: DocumentAttachment[] = [];
    const errors: string[] = [];
    try {
      for (const file of selectedFiles) {
        if (file.size > MAX_DOCUMENT_ATTACHMENT_BYTES) {
          errors.push(`${file.name} passa de 50 MB`);
          continue;
        }

        const formData = new FormData();
        formData.append('file', file);
        try {
          const { data } = await api.post<DocumentExtractionResult>('/api/documents/extract', formData);
          extracted.push({
            id: `${file.name}-${file.size}-${file.lastModified}-${crypto.randomUUID()}`,
            name: data.name,
            mimeType: data.mediaType,
            bytes: data.bytes,
            reader: data.reader,
            content: data.content,
            truncated: data.truncated,
          });
        } catch (error) {
          errors.push(`${file.name}: ${apiErrorMessage(error)}`);
        }
      }

      if (attachmentSession !== documentAttachmentSessionRef.current) return;
      if (extracted.length > 0) {
        setDocumentAttachments(previous => [...previous, ...extracted].slice(0, MAX_DOCUMENT_ATTACHMENTS));
      }
      if (errors.length > 0) {
        setSnackbarMessage(`Alguns documentos não foram anexados: ${errors.join('; ')}`);
      }
    } finally {
      if (attachmentSession === documentAttachmentSessionRef.current) {
        setIsAttachingDocuments(false);
      }
    }
  };

  const handlePoseReference = async (files: FileList | null) => {
    const file = files?.[0];
    if (!file) return;
    if (!file.type.startsWith('image/')) {
      setSnackbarMessage('A referência de pose precisa ser uma imagem.');
      return;
    }
    if (file.size > MAX_IMAGE_ATTACHMENT_BYTES) {
      setSnackbarMessage('A referência de pose precisa ter no máximo 6 MB.');
      return;
    }
    const dataUrl = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result || ''));
      reader.onerror = () => reject(reader.error);
      reader.readAsDataURL(file);
    });
    setPoseReference({ name: file.name, dataUrl });
  };

  const handleRemoveImageAttachment = (id: string) => {
    setImageAttachments(prev => prev.filter(attachment => attachment.id !== id));
  };

  const handleRemoveDocumentAttachment = (id: string) => {
    setDocumentAttachments(previous => previous.filter(attachment => attachment.id !== id));
  };

  const handleSend = async (
    text: string = inputValue,
    options: {
      force?: boolean;
      spokenLanguage?: string;
      images?: ImageAttachment[];
      documents?: DocumentAttachment[];
    } = {}
  ) => {
    const outgoingImages = options.images ?? imageAttachments;
    const outgoingDocuments = options.documents ?? documentAttachments;
    if (!text.trim() && outgoingImages.length === 0 && outgoingDocuments.length === 0) return;

    if (isGenerating && !options.force) {
      setMessageQueue(prev => [
        ...prev,
        {
          id: `queued_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
          text,
          images: outgoingImages,
          documents: outgoingDocuments,
        },
      ]);
      setInputValue('');
      setImageAttachments([]);
      setDocumentAttachments([]);
      return;
    }

    voiceInterruptionActiveRef.current = false;
    stopAudioPlayback();
    speechBufferRef.current = '';

    let effectiveProjectPaths = projectPaths;
    const shouldUseProjectContext = outgoingImages.length > 0 || outgoingDocuments.length > 0 || !isCasualMessage(text);
    if (effectiveProjectPaths.length === 0) {
      const pathFromMessage = extractLikelyAbsolutePath(text);
      if (pathFromMessage) {
        try {
          const authorizedPath = await authorizeProjectPath(pathFromMessage);
          if (authorizedPath) {
            effectiveProjectPaths = [authorizedPath];
            setProjectPaths([authorizedPath]);
            loadProjectTree(authorizedPath);
          } else {
            setProjectAnalysisError('Não consegui validar a pasta informada na mensagem. Confirme se o caminho existe.');
          }
        } catch (error) {
          console.error('Erro ao autorizar path informado no chat', error);
        }
      }
    }
    
    // Check files
    let displayMsg = text;
    if (!displayMsg.trim() && outgoingImages.length > 0 && outgoingDocuments.length > 0) {
      displayMsg = 'Analise estes anexos.';
    } else if (!displayMsg.trim() && outgoingImages.length > 0) {
      displayMsg = 'Analise esta imagem.';
    } else if (!displayMsg.trim() && outgoingDocuments.length > 0) {
      displayMsg = 'Analise este documento.';
    }
    if (outgoingImages.length > 0) {
      displayMsg += `\n\n> ${outgoingImages.length} imagem(ns) anexada(s) para leitura visual.`;
    }
    if (selectedFiles.size > 0 && shouldUseProjectContext) {
        displayMsg += `\n\n> 📎 **${selectedFiles.size}** arquivos do projeto anexados no contexto.`;
    } else if (shouldUseProjectContext && effectiveProjectPaths && effectiveProjectPaths.length > 0) {
        displayMsg += `\n\n[System Info: Projeto conectado: ${effectiveProjectPaths.join(', ')}]`;
    }

    const documentContext = buildDocumentContext(outgoingDocuments);
    const documentNames = outgoingDocuments.map(document => document.name.replace(/[\r\n]+/g, ' ')).join('\n');
    const userMsg: Message = {
      role: 'user',
      content: displayMsg,
      attachments: outgoingImages,
      documentContext,
      documentNames,
    };
    const assistantPlaceholder: Message = { role: 'assistant', content: '', tokens: '0', duration: '0.0' };
    
    setActivityEvents([]);
    setMessages(prev => [...prev, userMsg, assistantPlaceholder]);
    setInputValue('');
    setImageAttachments([]);
    setDocumentAttachments([]);

    // Save user message to DB
    const persistedUserText = text.trim()
      || (outgoingDocuments.length > 0 ? 'Documento anexado para análise.' : 'Imagem anexada para análise visual.');
    if (currentChatId === null) {
      setSelectedChatTitle(persistedUserText.substring(0, 30) || 'Nova conversa');
    }
    let activeChatId: number;
    try {
      activeChatId = await saveMessageToDB(
        'user',
        persistedUserText,
        persistedUserText.substring(0, 30),
        effectiveProjectPaths,
        currentChatId,
        documentContext,
        documentNames
      );
    } catch (error) {
      const message = apiErrorMessage(error);
      setMessages(previous => previous.map((item, index) => (
        index === previous.length - 1 && item.role === 'assistant' && !item.content
          ? { ...item, content: `Não consegui iniciar a conversa: ${message}` }
          : item
      )));
      showTemporaryNotice(`Falha ao salvar a mensagem: ${message}`);
      return;
    }

    let activeProjectAnalysis = projectAnalysis;
    if (shouldUseProjectContext && effectiveProjectPaths.length > 0 && (!activeProjectAnalysis || activeProjectAnalysis.rootPath !== effectiveProjectPaths[0])) {
      activeProjectAnalysis = await analyzeProject(effectiveProjectPaths[0]);
    }

    // Read files
    const fileContextText = shouldUseProjectContext ? await readSelectedFiles() : '';
    
    // RAG Search
    let ragContextText = "";
    if (shouldUseProjectContext && effectiveProjectPaths && effectiveProjectPaths.length > 0 && !text.toLowerCase().includes("directory_tree")) {
      try {
        const { data: ragChunks } = await api.post<any[]>('/api/rag/search', {
          query: text,
          projectPaths: effectiveProjectPaths,
        });
        if (Array.isArray(ragChunks) && ragChunks.length > 0) {
          ragContextText = "[RAG Context] O banco de dados vetorial encontrou os seguintes trechos de código relevantes no projeto:\n\n";
          ragChunks.forEach((chunk: any) => {
            ragContextText += `--- Arquivo: ${chunk.metadata?.filename || 'Desconhecido'} ---\n\`\`\`\n${chunk.content}\n\`\`\`\n\n`;
          });
        }
      } catch (e) {
        console.error("Erro na busca RAG", e);
      }
    }

    // Prepare prompt
    const promptContext = messagesRef.current
      .filter(m => m.role !== 'assistant' || m.content.trim().length > 0)
      .concat(userMsg)
      .map(m => {
        const content = m.documentContext
          ? `${m.documentContext}\n\n[Pedido do usuário]\n${m.content}`
          : m.content;
        const contextMessage: MessageContext = { role: m.role, content };
        if ('attachments' in m && Array.isArray(m.attachments) && m.attachments.length > 0) {
          contextMessage.images = m.attachments.map(attachment => attachment.base64);
        }
        return contextMessage;
      });
    if (options.spokenLanguage) {
      const last = promptContext[promptContext.length - 1];
      last.content = `[Voice Input] Detected language: ${options.spokenLanguage}. Reply in this language unless the user explicitly asks for another language.\n\n${last.content}`;
    }
    const workspaceContextText = shouldUseProjectContext
      ? buildWorkspaceContextText(effectiveProjectPaths, homeWorkspaceRoot)
      : '';
    const projectContextText = shouldUseProjectContext ? buildProjectContextText(activeProjectAnalysis, commandResults) : '';
    const environmentContextText = shouldUseProjectContext ? buildEnvironmentContextText(mcpStatus) : '';
    if (workspaceContextText || environmentContextText || fileContextText || ragContextText || projectContextText) {
      const last = promptContext[promptContext.length - 1];
      last.content = `${workspaceContextText ? `${workspaceContextText}\n\n` : ''}${environmentContextText ? `${environmentContextText}\n\n` : ''}${projectContextText ? `${projectContextText}\n\n` : ''}${fileContextText}${ragContextText}Com base no contexto local acima, responda ao seguinte pedido do usuário. Não diga que você não tem acesso ao sistema quando [Workspace Roots], [Local Environment] ou [Project Analysis] estiverem presentes; use esse contexto e seja específico:\n\n${last.content}`;
    }

    const hasVisualContext = promptContext.some(message => Array.isArray(message.images) && message.images.length > 0);
    let responseModel = selectedModel;
    if (hasVisualContext && !availableModels.find(model => model.name === selectedModel)?.vision) {
      const visionModel = availableModels.find(model => model.vision && model.preferredForVision) ||
        availableModels.find(model => model.vision && !model.heavy) ||
        availableModels.find(model => model.vision);
      if (visionModel) {
        responseModel = visionModel.name;
        setSelectedModel(visionModel.name);
        showTemporaryNotice(`Modelo visual ${visionModel.name} selecionado para analisar a imagem.`);
      }
    }

    const requestImageGenerationOptions: ImageGenerationOptions = outgoingImages.length > 0
      ? {
          ...imageGenerationOptions,
          referenceImageDataUrl: outgoingImages[outgoingImages.length - 1].dataUrl,
        }
      : imageGenerationOptions;
    const finalResponse = await sendMessage(
      promptContext,
      responseModel,
      effectiveProjectPaths,
      selectedImageModel,
      requestImageGenerationOptions,
      activeChatId
    );
    
    // Save AI message to DB
    if (finalResponse) {
      await saveMessageToDB('assistant', finalResponse, undefined, undefined, activeChatId);
      streamDraftsRef.current.delete(activeChatId);
      if (finalResponse.includes('/api/media/')) {
        await loadMedia(activeChatId);
      }
      if (currentChatIdRef.current === activeChatId && isVoiceEnabledRef.current && !isRealtimeVoiceActive) {
          const speechText = sanitizeSpeechText(finalResponse);
          if (speechText) {
            queueTextToSpeech(speechText);
          }
      }
    }
  };

  const toggleRecording = async () => {
    if (isRecording) {
      const text = await stopRecording();
      if (text) handleSend(text);
    } else {
      startRecording();
    }
  };

  const toggleRealtimeVoice = () => {
    if (isRealtimeVoiceActive) {
      stopRealtimeVoice();
      return;
    }

    const interruptCurrentResponse = () => {
      voiceInterruptionActiveRef.current = true;
      speechBufferRef.current = '';
      stopAudioPlayback();
      if (isGeneratingRef.current) {
        abortGeneration(currentChatIdRef.current);
      }
    };

    startRealtimeVoice((spokenTranscript) => {
      const spokenText = spokenTranscript.text;
      const sendSpokenText = () => {
        setInputValue(spokenText);
        handleSend(spokenText, { force: true, spokenLanguage: spokenTranscript.language });
      };

      if (isGeneratingRef.current) {
        interruptCurrentResponse();
        window.setTimeout(sendSpokenText, 250);
        return;
      }

      interruptCurrentResponse();
      setInputValue(spokenText);
      handleSend(spokenText, { force: true, spokenLanguage: spokenTranscript.language });
    }, interruptCurrentResponse);
  };

  const canRunScript = (script: ProjectScript) => {
    if (script.runner === 'npm') {
      return ['test', 'build', 'typecheck', 'lint', 'validate'].includes(script.name);
    }
    if (script.runner === 'maven') {
      return ['test', 'package', 'verify'].includes(script.name);
    }
    return false;
  };

  const runProjectScript = async (script: ProjectScript) => {
    if (!projectAnalysis || !canRunScript(script)) return;

    const commandKey = `${script.runner}-${script.name}-${script.path}`;
    setRunningCommandKey(commandKey);
    setCommandResult(null);
    setCommandError(null);
    setActivityEvents(prev => [{
      type: 'command.started',
      title: `Rodando ${script.runner}:${script.name}`,
      detail: `${script.command} em ${script.path}`,
      timestamp: new Date().toISOString()
    }, ...prev].slice(0, 30));

    try {
      const { data: result } = await api.post<ProjectCommandResult>('/api/projects/run', {
        path: script.path || projectAnalysis.rootPath,
        runner: script.runner,
        name: script.name
      });
      setCommandResult(result);
      setCommandResults(prev => [result, ...prev.filter(item => item.command !== result.command)].slice(0, 8));
      setActivityEvents(prev => [{
        type: result.exitCode === 0 && !result.timedOut ? 'command.completed' : 'command.failed',
        title: `${result.command} finalizou`,
        detail: result.timedOut ? 'Tempo limite atingido' : `Exit code ${result.exitCode} em ${result.durationSeconds}s`,
        timestamp: result.finishedAt
      }, ...prev].slice(0, 30));
    } catch (error) {
      const message = apiErrorMessage(error);
      setCommandError(message);
      setActivityEvents(prev => [{
        type: 'command.failed',
        title: `Falha ao rodar ${script.runner}:${script.name}`,
        detail: message,
        timestamp: new Date().toISOString()
      }, ...prev].slice(0, 30));
    } finally {
      setRunningCommandKey(null);
    }
  };

  const requestGuidedProjectReview = () => {
    handleSend('Faça uma análise guiada deste projeto com base no diagnóstico atual. Priorize riscos reais, próximos passos práticos, comandos de validação e onde você investigaria mais usando ferramentas.');
  };

  const requestValidationFailureReview = () => {
    handleSend(buildValidationFailurePrompt(commandResults));
  };

  const getValidationScripts = () => {
    if (!projectAnalysis) return [];

    const priorities = ['typecheck', 'lint', 'test', 'build', 'validate', 'package', 'verify'];
    const seen = new Set<string>();

    return projectAnalysis.scripts
      .filter(canRunScript)
      .sort((a, b) => {
        const aIndex = priorities.indexOf(a.name);
        const bIndex = priorities.indexOf(b.name);
        return (aIndex === -1 ? 99 : aIndex) - (bIndex === -1 ? 99 : bIndex);
      })
      .filter(script => {
        const key = `${script.runner}:${script.name}:${script.path}`;
        if (seen.has(key)) return false;
        seen.add(key);
        return true;
      });
  };

  const runValidationSuite = async () => {
    const scripts = getValidationScripts();
    if (scripts.length === 0 || runningCommandKey !== null) return;

    setCommandResult(null);
    setCommandResults([]);
    setCommandError(null);
    for (const script of scripts) {
      await runProjectScript(script);
    }
  };

  const renderAgentActivityPanel = () => (
    <>
      {currentPlanSteps && currentPlanSteps.length > 0 && (
        <AnalysisSection>
          <h4>Plano atual</h4>
          <ScriptList>
            {currentPlanSteps.map((step, index) => (
              <li key={index}>{index + 1}. {step}</li>
            ))}
          </ScriptList>
        </AnalysisSection>
      )}
      {Object.values(runningProcesses).length > 0 && (
        <AnalysisSection>
          <h4>Processos em execução</h4>
          <ProcessList>
            {Object.values(runningProcesses).map(process => (
              <ProcessCard key={process.processId}>
                <ProcessHeader>
                  <ProcessCommand>
                  <code>{process.command || process.processId}</code>
                    <ProcessStatus $running={process.running}>
                      {process.running ? 'rodando' : `finalizado · exit ${process.exitCode ?? '?'}`}
                    </ProcessStatus>
                  </ProcessCommand>
                  {process.running && (
                    <CommandButton type="button" onClick={() => handleStopProcess(process.processId)}>
                      Parar
                    </CommandButton>
                  )}
                </ProcessHeader>
                <CommandOutput>{process.logs || '(sem saída ainda)'}</CommandOutput>
              </ProcessCard>
            ))}
          </ProcessList>
        </AnalysisSection>
      )}
      {isLoadingTimeline && activityEvents.length === 0 && (
        <AnalysisSection>
          <h4>Atividade do agente</h4>
          <p>Carregando histórico recente...</p>
        </AnalysisSection>
      )}
      {activityEvents.length > 0 && (
        <AnalysisSection>
          <h4>Atividade do agente</h4>
          <ActivityList>
            {activityEvents.map((event, index) => (
              <ActivityItem key={`${event.timestamp}-${event.type}-${index}`} $type={event.type}>
                <div>
                  <strong>{event.title}</strong>
                  {event.detail && <span>{event.detail}</span>}
                </div>
              </ActivityItem>
            ))}
          </ActivityList>
        </AnalysisSection>
      )}
    </>
  );

  const renderModelSelectors = (inMenu = false) => (
    <>
      <label className={inMenu ? 'menu-model-control' : 'model-control'}>
        <select
          value={selectedModel}
          onChange={(event) => setSelectedModel(event.target.value)}
          className="model-select"
          disabled={isLoadingModels || availableModels.length === 0}
          aria-label="Modelo de texto"
          title="Modelo de texto"
        >
          {isLoadingModels ? (
            <option value="">Carregando modelos...</option>
          ) : modelLoadError ? (
            <option value="">{modelLoadError}</option>
          ) : availableModels.length === 0 ? (
            <option value="">Nenhum modelo disponível (Ollama/LM Studio)</option>
          ) : (
            availableModels.map(model => (
              <option key={model.name} value={model.name}>
                {model.name}
                {model.recommended ? ' · recomendado' : ''}
                {model.parameterSize ? ` · ${model.parameterSize}` : ''}
                {model.sizeLabel ? ` · ${model.sizeLabel}` : ''}
                {model.heavy ? ' · pesado' : ''}
              </option>
            ))
          )}
        </select>
      </label>
      <label className={inMenu ? 'menu-model-control' : 'model-control image-model-control'}>
        <select
          value={selectedImageModel}
          onChange={(event) => setSelectedImageModel(event.target.value)}
          className="model-select image-model-select"
          disabled={availableImageModels.length === 0}
          aria-label="Modelo de imagem"
          title="Modelo para geração de imagens"
        >
          {availableImageModels.length === 0 ? (
            <option value="">Nenhum modelo de imagem</option>
          ) : (
            availableImageModels.map(model => (
              <option key={model.name} value={model.name}>
                Imagem: {imageModelLabel(model.name)}{model.recommended ? ' · padrão' : ''}
              </option>
            ))
          )}
        </select>
      </label>
    </>
  );

  return (
    <AppLayout>
      
      <Sidebar 
        isMobileOpen={isMobileOpen} 
        chats={chats}
        currentChatId={currentChatId}
        onNewChat={startNewChat}
        onLoadChat={handleLoadChat}
        onDeleteChat={handleDeleteChat}
        notifications={notifications}
        unreadNotificationCount={unreadNotificationCount}
        onMarkNotificationRead={markNotificationRead}
        onMarkAllNotificationsRead={markAllNotificationsRead}
        projectPaths={projectPaths}
        removeProjectPath={removeProjectPath}
        homeWorkspaceRoot={homeWorkspaceRoot}
        clearHomeWorkspaceRoot={clearHomeWorkspaceRoot}
        browseFolder={browseFolder}
        authorizeHomeFolder={authorizeHomeFolder}
        loadProjectTree={loadProjectTree}
        fileTree={fileTree}
        selectedFiles={selectedFiles}
        toggleFileSelection={toggleFileSelection}
        media={visibleMedia}
        onOpenMedia={handleOpenMedia}
      />
      {isMobileOpen && <MobileOverlay onClick={() => setMobileOpen(false)} />}
      
      <MainContent>
        <Topbar>
          <HeaderLeft>
            <MobileOnlyIconButton onClick={() => setMobileOpen(!isMobileOpen)}>
              <List size={24} />
            </MobileOnlyIconButton>
            <h2 title={selectedChatTitle}>{selectedChatTitle}</h2>
          </HeaderLeft>
          <VoiceToggleWrapper>
            {renderModelSelectors()}
            <label className="desktop-header-control">
              <input 
                type="checkbox" 
                checked={isDarkMode} 
                onChange={toggleTheme} 
              />
              {isDarkMode ? <Moon size={20} weight="fill" /> : <Sun size={20} weight="fill" />} 
              <span>Modo Escuro</span>
            </label>
            <label className="desktop-header-control">
              <input 
                type="checkbox" 
                checked={isVoiceEnabled} 
                onChange={(e) => handleToggleVoice(e.target.checked)} 
              />
              {isVoiceEnabled ? <SpeakerHigh size={20} weight="fill" /> : <SpeakerSlash size={20} />}
              <span>Voz Habilitada</span>
            </label>
            <HeaderIconButton onClick={() => setIsRightPanelOpen(!isRightPanelOpen)} title="Tarefas e contexto">
              <Columns size={24} weight={isRightPanelOpen ? "fill" : "regular"} />
            </HeaderIconButton>
            <HeaderIconButton className="secondary-header-action" onClick={() => setIsSkillsManagerOpen(true)} title="Skills — procedimentos reutilizáveis">
              <Lightning size={22} />
            </HeaderIconButton>
            <HeaderIconButton
              onClick={() => {
                setIsMcpToolsManagerOpen(true);
                loadMcpServers();
              }}
              title={`Ferramentas locais · ${mcpServers.filter(server => server.connected).length} conectadas`}
            >
              <Plug size={22} weight={mcpServers.some(server => server.connected) ? 'fill' : 'regular'} />
            </HeaderIconButton>
            <HeaderIconButton className="secondary-header-action" as="a" href="/docs.html" target="_blank" rel="noreferrer" title="Documentação completa">
              <BookOpen size={22} />
            </HeaderIconButton>
            <HeaderCompactMenu ref={headerMenuRef}>
              <HeaderIconButton
                type="button"
                onClick={() => setIsHeaderMenuOpen(current => !current)}
                title="Configurações do header"
                aria-label="Configurações do header"
                aria-expanded={isHeaderMenuOpen}
              >
                <SlidersHorizontal size={21} weight={isHeaderMenuOpen ? 'fill' : 'regular'} />
              </HeaderIconButton>
              {isHeaderMenuOpen && (
                <HeaderMenuPanel role="dialog" aria-label="Configurações rápidas">
                  <HeaderMenuSection>
                    <h3 className="menu-heading">Modelos</h3>
                    {renderModelSelectors(true)}
                  </HeaderMenuSection>
                  <HeaderMenuSection>
                    <h3 className="menu-heading">Geração de imagem</h3>
                    <div className="image-quality-segments" role="group" aria-label="Qualidade da imagem">
                      {(['draft', 'balanced', 'quality'] as const).map(preset => (
                        <button
                          key={preset}
                          type="button"
                          className={imageQualityPreset === preset ? 'active' : ''}
                          aria-pressed={imageQualityPreset === preset}
                          onClick={() => setImageQualityPreset(preset)}
                        >
                          {preset === 'draft' ? 'Rápido' : preset === 'balanced' ? 'Equilibrado' : 'Qualidade'}
                        </button>
                      ))}
                    </div>
                    <div className="image-settings-grid">
                      <label>
                        <span>O que gerar?</span>
                        <select
                          value={imageSubjectType}
                          onChange={event => setImageSubjectType(event.target.value as ImageGenerationOptions['subjectType'])}
                        >
                          <option value="auto">Automático</option>
                          <option value="person">Pessoa</option>
                          <option value="object">Objeto / produto</option>
                          <option value="environment">Ambiente / cenário</option>
                          <option value="vehicle">Veículo</option>
                          <option value="animal">Animal</option>
                        </select>
                      </label>
                      <label>
                        <span>Proporção</span>
                        <select
                          value={imageAspectRatio}
                          onChange={event => setImageAspectRatio(event.target.value as ImageGenerationOptions['aspectRatio'])}
                        >
                          <option value="portrait">Retrato</option>
                          <option value="square">Quadrada</option>
                          <option value="landscape">Paisagem</option>
                        </select>
                      </label>
                      <label>
                        <span>Sujeitos</span>
                        <select value={imageSubjectCount} onChange={event => setImageSubjectCount(Number(event.target.value))}>
                          <option value={0}>Automático</option>
                          <option value={1}>1</option>
                          <option value={2}>2</option>
                          <option value={3}>3</option>
                          <option value={4}>4</option>
                        </select>
                      </label>
                      <label>
                        <span>CFG</span>
                        <input
                          type="number"
                          min={1}
                          max={12}
                          step={0.1}
                          value={imageCfgScale}
                          onChange={event => setImageCfgScale(Math.max(1, Math.min(12, Number(event.target.value) || 6)))}
                        />
                      </label>
                      <label>
                        <span>Detalhamento</span>
                        <select
                          value={imageDetailMode}
                          onChange={event => setImageDetailMode(event.target.value as ImageGenerationOptions['detailMode'])}
                        >
                          <option value="none">Desligado</option>
                          <option value="face">Rosto</option>
                          <option value="face-hands">Rosto e mãos</option>
                        </select>
                      </label>
                      <label title="Define como a imagem anexada orienta a geração">
                        <span>Uso da referência</span>
                        <select
                          value={imageReferenceMode}
                          onChange={event => setImageReferenceMode(event.target.value as ImageGenerationOptions['referenceMode'])}
                        >
                          <option value="composition">Composição</option>
                          <option value="identity">Identidade</option>
                          <option value="transform">Transformação</option>
                        </select>
                      </label>
                      {imageReferenceMode === 'composition' && (
                        <label title="Depth preserva volumes; Canny preserva contornos">
                          <span>Estrutura</span>
                          <select
                            value={imageStructureControl}
                            onChange={event => setImageStructureControl(event.target.value as ImageGenerationOptions['structureControl'])}
                          >
                            <option value="depth">Profundidade</option>
                            <option value="canny">Contornos</option>
                            <option value="none">Desligada</option>
                          </select>
                        </label>
                      )}
                      {adherenceValidationEnabled && (
                        <label title="Nova tentativa automática quando a revisão visual detectar divergências">
                          <span>Tentativas extras</span>
                          <select
                            value={maxAdherenceRetries}
                            onChange={event => setMaxAdherenceRetries(Number(event.target.value))}
                          >
                            <option value={0}>Nenhuma</option>
                            <option value={1}>1 tentativa</option>
                            <option value={2}>2 tentativas</option>
                          </select>
                        </label>
                      )}
                    </div>
                    <div className="image-option-toggles">
                      <label>
                        <input
                          type="checkbox"
                          checked={enhanceImagePrompt}
                          onChange={event => setEnhanceImagePrompt(event.target.checked)}
                        />
                        <span>Melhorar prompt</span>
                      </label>
                      <label>
                        <input
                          type="checkbox"
                          checked={imageRefinementEnabled}
                          onChange={event => setImageRefinementEnabled(event.target.checked)}
                        />
                        <span>Segundo passe</span>
                      </label>
                      <label>
                        <input
                          type="checkbox"
                          checked={lockImageSeed}
                          onChange={event => setLockImageSeed(event.target.checked)}
                        />
                        <span>Fixar seed</span>
                      </label>
                      <label title="Compara o resultado ao pedido antes de concluir">
                        <input
                          type="checkbox"
                          checked={adherenceValidationEnabled}
                          onChange={event => setAdherenceValidationEnabled(event.target.checked)}
                        />
                        <span>Validar resultado</span>
                      </label>
                    </div>
                    {imageRefinementEnabled && (
                      <label className="image-range-control">
                        <span>Força do segundo passe <output>{imageRefinementStrength.toFixed(2)}</output></span>
                        <input
                          type="range"
                          min={0.15}
                          max={0.55}
                          step={0.01}
                          value={imageRefinementStrength}
                          onChange={event => setImageRefinementStrength(Number(event.target.value))}
                        />
                      </label>
                    )}
                    {imageReferenceMode === 'composition' && imageStructureControl !== 'none' && (
                      <label className="image-range-control">
                        <span>Força da estrutura <output>{imageStructureStrength.toFixed(2)}</output></span>
                        <input
                          type="range"
                          min={0.2}
                          max={1.5}
                          step={0.05}
                          value={imageStructureStrength}
                          onChange={event => setImageStructureStrength(Number(event.target.value))}
                        />
                      </label>
                    )}
                    <label className="image-range-control">
                      <span>Fidelidade à imagem anexada <output>{imageReferenceStrength.toFixed(2)}</output></span>
                      <input
                        type="range"
                        min={0.1}
                        max={0.9}
                        step={0.05}
                        value={imageReferenceStrength}
                        onChange={event => setImageReferenceStrength(Number(event.target.value))}
                      />
                    </label>
                    {lockImageSeed && (
                      <label className="image-seed-control">
                        <span>Seed</span>
                        <input
                          type="number"
                          min={0}
                          step={1}
                          value={imageSeed}
                          onChange={event => setImageSeed(Number(event.target.value) || 0)}
                        />
                      </label>
                    )}
                    <div className="pose-reference-control">
                      <label className="pose-upload-button">
                        <ImageSquare size={18} />
                        <span>{poseReference ? poseReference.name : 'Pose de referência'}</span>
                        <input
                          type="file"
                          accept="image/png,image/jpeg,image/webp"
                          onChange={event => {
                            void handlePoseReference(event.target.files);
                            event.target.value = '';
                          }}
                        />
                      </label>
                      {poseReference && (
                        <button
                          type="button"
                          className="pose-remove-button"
                          onClick={() => setPoseReference(null)}
                          title="Remover pose de referência"
                          aria-label="Remover pose de referência"
                        >
                          <X size={16} />
                        </button>
                      )}
                    </div>
                    {poseReference && (
                      <label className="image-range-control">
                        <span>Fidelidade à pose <output>{poseStrength.toFixed(2)}</output></span>
                        <input
                          type="range"
                          min={0.2}
                          max={1.5}
                          step={0.05}
                          value={poseStrength}
                          onChange={event => setPoseStrength(Number(event.target.value))}
                        />
                      </label>
                    )}
                  </HeaderMenuSection>
                  <HeaderMenuSection>
                    <h3 className="menu-heading">Preferências</h3>
                    <div className="menu-toggle-row">
                      <label className="menu-toggle">
                        <input type="checkbox" checked={isDarkMode} onChange={toggleTheme} />
                        {isDarkMode ? <Moon size={18} weight="fill" /> : <Sun size={18} weight="fill" />}
                        <span>Tema</span>
                      </label>
                      <label className="menu-toggle">
                        <input
                          type="checkbox"
                          checked={isVoiceEnabled}
                          onChange={(event) => handleToggleVoice(event.target.checked)}
                        />
                        {isVoiceEnabled ? <SpeakerHigh size={18} weight="fill" /> : <SpeakerSlash size={18} />}
                        <span>Voz</span>
                      </label>
                    </div>
                  </HeaderMenuSection>
                  <HeaderMenuSection>
                    <h3 className="menu-heading">Ações</h3>
                    <HeaderMenuActions>
                      <button
                        type="button"
                        onClick={() => {
                          setIsHeaderMenuOpen(false);
                          setIsSkillsManagerOpen(true);
                        }}
                      >
                        <Lightning size={19} />
                        <span>Skills</span>
                      </button>
                      <a href="/docs.html" target="_blank" rel="noreferrer" onClick={() => setIsHeaderMenuOpen(false)}>
                        <BookOpen size={19} />
                        <span>Docs</span>
                      </a>
                      <button
                        type="button"
                        onClick={() => {
                          setIsHeaderMenuOpen(false);
                          logout();
                        }}
                      >
                        <SignOut size={19} />
                        <span>Sair</span>
                      </button>
                    </HeaderMenuActions>
                  </HeaderMenuSection>
                </HeaderMenuPanel>
              )}
            </HeaderCompactMenu>
            <HeaderIconButton
              className="secondary-header-action"
              type="button"
              onClick={logout}
              title="Sair"
              aria-label="Sair"
            >
              <SignOut size={22} />
            </HeaderIconButton>
          </VoiceToggleWrapper>
        </Topbar>
        
        <ChatContainer>
          {messages.length <= 1 ? (
            <WelcomeState>
              <h2>Como posso ajudar?</h2>
              <button 
                className="project-select-btn"
                onClick={async () => {
                  const path = await browseFolder();
                  if (path) loadProjectTree(path);
                }}
              >
                <Folder size={20} weight="fill" />
                Adicionar Pasta ao Projeto
              </button>
            </WelcomeState>
          ) : (
            <>
              {messages.map((msg, i) => (
                msg.role !== 'system' && (
                  <MessageBubble 
                    key={i} 
                    message={msg} 
                    messageIndex={i}
                    isStreaming={isGenerating && i === messages.length - 1 && msg.role === 'assistant'}
                    approvalLoadingId={approvalLoadingId}
                    onApproveApproval={handleApproveAction}
                    onRejectApproval={handleRejectAction}
                    onOpenDiff={handleOpenDiff}
                  />
                )
              ))}
              <div ref={chatEndRef} />
            </>
          )}
        </ChatContainer>

        <InputArea 
          inputValue={inputValue}
          setInputValue={setInputValue}
          handleSend={() => handleSend(inputValue)}
          onStop={() => abortGeneration(currentChatId)}
          isGenerating={isGenerating}
          isRecording={isRecording}
          isRealtimeVoiceActive={isRealtimeVoiceActive}
          isRealtimeListening={isRealtimeListening}
          realtimeTranscript={realtimeTranscript}
          audioLevel={audioLevel}
          speechRecognitionSupported={speechRecognitionSupported}
          imageAttachments={imageAttachments}
          documentAttachments={documentAttachments}
          isAttachingDocuments={isAttachingDocuments}
          toggleRecording={toggleRecording}
          toggleRealtimeVoice={toggleRealtimeVoice}
          onAttachImages={handleAttachImages}
          onAttachDocuments={handleAttachDocuments}
          onRemoveImageAttachment={handleRemoveImageAttachment}
          onRemoveDocumentAttachment={handleRemoveDocumentAttachment}
          messageQueue={messageQueue}
          onRemoveFromQueue={removeFromQueue}
          skills={skills}
        />
      </MainContent>

      {isRightPanelOpen && (
        <RightPanel>
          <RightPanelHeader>
            <h3>{activeDiff ? 'Modificando Código' : 'Tarefas e Contexto'}</h3>
            <CloseButton onClick={() => setIsRightPanelOpen(false)}>
              <X size={18} />
            </CloseButton>
          </RightPanelHeader>
          <PanelContent>
            {selectedMedia ? (
              <AnalysisSection>
                <h4>{selectedMedia.name}</h4>
                <img
                  src={selectedMedia.url}
                  alt={selectedMedia.name}
                  style={{ width: '100%', display: 'block', borderRadius: 8 }}
                />
                <p>Gerada em {new Date(selectedMedia.createdAt).toLocaleString('pt-BR')}</p>
              </AnalysisSection>
            ) : activeDiff ? (
              <FileDiffViewer 
                filePath={activeDiff.path} 
                newContent={extractFileEditContent(messages[activeDiff.messageIndex]?.content || '', activeDiff.path)} 
              />
            ) : isAnalyzingProject ? (
              <AnalysisSection>
                <h4>Analisando projeto</h4>
                <p>Lendo estrutura, detectando stack e procurando sinais de risco.</p>
              </AnalysisSection>
            ) : projectAnalysisError ? (
              <AnalysisSection>
                <h4>Analise indisponivel</h4>
                <p>{projectAnalysisError}</p>
              </AnalysisSection>
            ) : projectAnalysis ? (
              <>
                {renderAgentActivityPanel()}

                <AnalysisSection>
                  <h4>{projectAnalysis.projectName}</h4>
                  <p>{projectAnalysis.rootPath}</p>
                  <p>
                    {projectAnalysis.fileStats.totalFiles} arquivos analisados
                    {projectAnalysis.fileStats.truncated ? ' (amostra limitada)' : ''}
                  </p>
                  <CommandButton type="button" onClick={requestGuidedProjectReview} disabled={isGenerating}>
                    Pedir análise guiada
                  </CommandButton>
                  <CommandButton
                    type="button"
                    onClick={runValidationSuite}
                    disabled={runningCommandKey !== null || getValidationScripts().length === 0}
                  >
                    Rodar validações
                  </CommandButton>
                  <CommandButton
                    type="button"
                    onClick={requestValidationFailureReview}
                    disabled={isGenerating || commandResults.length === 0}
                  >
                    Analisar validações
                  </CommandButton>
                </AnalysisSection>

                <AnalysisSection>
                  <h4>Stack detectada</h4>
                  <ChipList>
                    {projectAnalysis.technologies.length > 0 ? (
                      projectAnalysis.technologies.map(tech => <Chip key={tech}>{tech}</Chip>)
                    ) : (
                      <p>Nenhuma stack conhecida detectada ainda.</p>
                    )}
                  </ChipList>
                </AnalysisSection>

                <AnalysisSection>
                  <h4>Entrypoints</h4>
                  <ScriptList>
                    {projectAnalysis.entrypoints.slice(0, 8).map(entrypoint => (
                      <li key={entrypoint}><code>{entrypoint}</code></li>
                    ))}
                    {projectAnalysis.entrypoints.length === 0 && <li>Nenhum entrypoint comum detectado.</li>}
                  </ScriptList>
                </AnalysisSection>

                <AnalysisSection>
                  <h4>Scripts</h4>
                  <ScriptList>
                    {projectAnalysis.scripts.slice(0, 8).map(script => (
                      <ScriptRow
                        key={`${script.runner}-${script.name}-${script.path}`}
                      >
                        <span>
                          <code>{script.runner}:{script.name}</code> - {script.command}
                          {script.path !== projectAnalysis.rootPath && (
                            <ScriptMeta>{script.path}</ScriptMeta>
                          )}
                        </span>
                        {canRunScript(script) && (
                          <CommandButton
                            type="button"
                            onClick={() => runProjectScript(script)}
                            disabled={runningCommandKey === `${script.runner}-${script.name}-${script.path}`}
                          >
                            {runningCommandKey === `${script.runner}-${script.name}-${script.path}` ? 'Rodando' : 'Rodar'}
                          </CommandButton>
                        )}
                      </ScriptRow>
                    ))}
                    {projectAnalysis.scripts.length === 0 && <li>Nenhum script comum detectado.</li>}
                  </ScriptList>
                </AnalysisSection>

                {(commandResults.length > 0 || commandResult || commandError) && (
                  <AnalysisSection>
                    <h4>Resultados de validação</h4>
                    {commandResults.length > 0 && (
                      <ScriptList>
                        {commandResults.map(result => (
                          <li key={`${result.command}-${result.finishedAt}`}>
                            <code>{result.command}</code> - exit {result.exitCode}
                            {result.timedOut ? ' (timeout)' : ` em ${result.durationSeconds}s`}
                            {(result.exitCode !== 0 || result.timedOut) && (
                              <AttentionText>
                                Requer atenção
                              </AttentionText>
                            )}
                          </li>
                        ))}
                      </ScriptList>
                    )}
                    {(commandResult || commandResults[0]) && (
                      <CommandOutput>{(commandResult || commandResults[0]).output || '(sem saída)'}</CommandOutput>
                    )}
                    {commandError && <p>{commandError}</p>}
                  </AnalysisSection>
                )}

                <AnalysisSection>
                  <h4>Achados</h4>
                  <FindingList>
                    {projectAnalysis.findings.slice(0, 8).map(finding => (
                      <FindingItem key={`${finding.severity}-${finding.title}`} $severity={finding.severity}>
                        <strong>{finding.title}</strong>
                        <span>{finding.detail}</span>
                      </FindingItem>
                    ))}
                    {projectAnalysis.findings.length === 0 && (
                      <FindingItem $severity="info">
                        <strong>Nenhum alerta inicial</strong>
                        <span>A analise estatica rapida nao encontrou riscos obvios.</span>
                      </FindingItem>
                    )}
                  </FindingList>
                </AnalysisSection>

                <AnalysisSection>
                  <h4>Proximos passos</h4>
                  <ScriptList>
                    {projectAnalysis.recommendations.map(recommendation => (
                      <li key={recommendation}>{recommendation}</li>
                    ))}
                  </ScriptList>
                </AnalysisSection>
              </>
            ) : (
              <>
                {activityEvents.length > 0 || Object.keys(runningProcesses).length > 0 || isLoadingTimeline
                  || (currentPlanSteps && currentPlanSteps.length > 0) ? (
                  renderAgentActivityPanel()
                ) : (
                  <EmptyPanelState>
                    Adicione uma pasta para ver a analise inicial do projeto.
                  </EmptyPanelState>
                )}
              </>
            )}
          </PanelContent>
        </RightPanel>
      )}
      {isSkillsManagerOpen && (
        <SkillsManager
          skills={skills}
          onClose={() => setIsSkillsManagerOpen(false)}
          onCreate={createSkill}
          onDelete={deleteSkill}
        />
      )}
      {isMcpToolsManagerOpen && (
        <McpToolsManager
          servers={mcpServers}
          isLoading={isLoadingMcpServers}
          busyServerId={busyMcpServerId}
          error={mcpCatalogError}
          onClose={() => setIsMcpToolsManagerOpen(false)}
          onRefresh={loadMcpServers}
          onConnect={connectMcpServer}
          onDisconnect={disconnectMcpServer}
          onNotify={showTemporaryNotice}
        />
      )}
      {snackbarMessage && (
        <Snackbar role="status" aria-live="polite">
          {snackbarMessage}
        </Snackbar>
      )}
    </AppLayout>
  );
}
