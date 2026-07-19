import React, { memo, useId, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {
  Wrapper,
  BubbleContainer,
  Header,
  Content,
  FooterInfo,
  FileEditCard,
  FileEditInfo,
  DiffButton,
  ImageAttachmentGrid,
  ImageAttachmentPreview,
  HeaderActions,
  CopyAction,
  CodeBlock,
  MediaPreview,
  MediaDisclosure,
  MediaDisclosureBody,
  MediaDisclosureHeader,
  MediaDisclosureToggle,
  DocumentAttachmentList,
  DocumentAttachmentBadge,
  SkillInvocationBadge,
  TableScroll,
  StyledTable,
  StyledTh,
  StyledTd,
} from './styles';
import { TypingIndicator } from './TypingIndicator';
import { ApprovalCard } from '../ApprovalCard';
import { ThinkingBlock } from './ThinkingBlock';
import { ImageGenerationCard } from '../ImageGenerationCard';
import { VideoGenerationCard } from '../VideoGenerationCard';
import { UiPreviewCard } from '../UiPreviewCard';
import { DocumentCard } from '../DocumentCard';

import { CaretDown, Check, Copy, FileCode, FileText, CaretRight, ImageSquare, Lightning } from '@phosphor-icons/react';

export interface Message {
  role: 'user' | 'assistant' | 'system';
  content: string;
  attachments?: ImageAttachment[];
  documentContext?: string;
  documentNames?: string;
  thinking?: string;
  duration?: string;
  tokens?: string;
  approval?: ApprovalRequest;
}

export interface ImageAttachment {
  id: string;
  name: string;
  mimeType: string;
  dataUrl: string;
  base64: string;
}

export interface DocumentAttachment {
  id: string;
  name: string;
  mimeType: string;
  bytes: number;
  reader: string;
  content: string;
  truncated: boolean;
}

export interface ApprovalRequest {
  approvalId: string;
  toolName: string;
  toolArguments: Record<string, unknown>;
  status?: 'pending' | 'running' | 'completed' | 'rejected';
}

interface MessageBubbleProps {
  message: Message;
  messageIndex: number;
  isStreaming?: boolean;
  onOpenDiff?: (index: number, path: string) => void;
  onApproveApproval?: (approvalId: string, comment: string) => void;
  onRejectApproval?: (approvalId: string, comment: string) => void;
  approvalLoadingId?: string | null;
}

function textFromNode(node: React.ReactNode): string {
  if (node === null || node === undefined || typeof node === 'boolean') return '';
  if (typeof node === 'string' || typeof node === 'number') return String(node);
  if (Array.isArray(node)) return node.map(textFromNode).join('');
  if (React.isValidElement<{ children?: React.ReactNode }>(node)) return textFromNode(node.props.children);
  return '';
}

// O bloco ```plan``` some da renderização (vira o painel "Tarefas e
// Contexto"), então o content bruto da mensagem pode ser não-vazio mesmo com
// o balão parecendo em branco — sem isso, o indicador de "pensando" nunca
// aparecia enquanto o único conteúdo gerado até agora era um plano.
function hasVisibleContent(content: string): boolean {
  if (/\[\[avento-image-job:[0-9a-f-]{36}\]\]/i.test(content)) return true;
  if (/\[\[avento-video-job:[0-9a-f-]{36}\]\]/i.test(content)) return true;
  let stripped = content.replace(/```plan\n[\s\S]*?```/g, '');
  const openIdx = stripped.lastIndexOf('```plan');
  if (openIdx !== -1) {
    stripped = stripped.slice(0, openIdx);
  }
  return stripped.trim().length > 0;
}

function hasUiPreview(content: string): boolean {
  return /```ui-preview(?:\s|\n)/i.test(content);
}

function extractMediaJobs(content: string): { markdown: string; imageJobIds: string[]; videoJobIds: string[]; documentNames: string[] } {
  const imageJobIds: string[] = [];
  const videoJobIds: string[] = [];
  const documentNames: string[] = [];
  let markdown = content.replace(/\[\[avento-image-job:([0-9a-f-]{36})\]\]/gi, (_, jobId: string) => {
    imageJobIds.push(jobId);
    return '';
  });
  markdown = markdown.replace(/\[\[avento-video-job:([0-9a-f-]{36})\]\]/gi, (_, jobId: string) => {
    videoJobIds.push(jobId);
    return '';
  });
  markdown = markdown.replace(/\[\[avento-doc:(.+?)\]\]/gi, (_, name: string) => {
    documentNames.push(name);
    return '';
  });
  return {
    markdown,
    imageJobIds: [...new Set(imageJobIds)],
    videoJobIds: [...new Set(videoJobIds)],
    documentNames: [...new Set(documentNames)],
  };
}

// Mensagem de usuário no formato /nome argumento é uma invocação de skill — ganha um chip
// destacado no chat em vez de parecer texto comum (mesmo padrão que o backend reconhece em
// AgentService.resolveSkillInvocation).
function matchSkillInvocation(content: string): { name: string; argument: string } | null {
  const match = content.trim().match(/^\/(\S+)(?:\s+([\s\S]*))?$/);
  if (!match) return null;
  return { name: match[1], argument: (match[2] || '').trim() };
}

function CopyButton({ text, label }: { text: string; label: string }) {
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    if (!text) return;
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1400);
    } catch (error) {
      console.error('Não foi possível copiar o conteúdo', error);
    }
  };

  return (
    <CopyAction type="button" onClick={copy} aria-label={copied ? 'Copiado' : label} title={copied ? 'Copiado' : label}>
      {copied ? <Check size={16} weight="bold" /> : <Copy size={16} />}
    </CopyAction>
  );
}

function CopyImageButton({ src }: { src: string }) {
  const [copied, setCopied] = useState(false);

  const copyImage = async () => {
    try {
      const response = await fetch(src);
      const blob = await response.blob();
      if (navigator.clipboard?.write && typeof ClipboardItem !== 'undefined') {
        await navigator.clipboard.write([new ClipboardItem({ [blob.type || 'image/png']: blob })]);
      } else if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(src);
      } else {
        throw new Error('Clipboard indisponível neste navegador');
      }
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1400);
    } catch (error) {
      console.error('Não foi possível copiar a imagem', error);
    }
  };

  return (
    <CopyAction type="button" onClick={copyImage} aria-label={copied ? 'Imagem copiada' : 'Copiar imagem'} title={copied ? 'Imagem copiada' : 'Copiar imagem'}>
      {copied ? <Check size={16} weight="bold" /> : <ImageSquare size={16} />}
    </CopyAction>
  );
}

function storedMediaOpen(key: string): boolean {
  try {
    return window.sessionStorage.getItem(key) === 'open';
  } catch {
    return false;
  }
}

function CollapsibleGeneratedImage({ src, alt }: { src: string; alt: string }) {
  const storageKey = `avento:media-disclosure:${src}`;
  const [open, setOpen] = useState(() => storedMediaOpen(storageKey));
  const [dimensions, setDimensions] = useState('');
  const bodyId = useId();

  const toggle = () => {
    setOpen(current => {
      const next = !current;
      try {
        window.sessionStorage.setItem(storageKey, next ? 'open' : 'closed');
      } catch {
        // O estado local continua funcionando quando o storage não está disponível.
      }
      return next;
    });
  };

  return (
    <MediaDisclosure>
      <MediaDisclosureHeader>
        <MediaDisclosureToggle
          type="button"
          $open={open}
          onClick={toggle}
          aria-expanded={open}
          aria-controls={bodyId}
          title={open ? 'Minimizar mídia' : 'Expandir mídia'}
        >
          <ImageSquare size={17} weight="duotone" />
          <span>
            <strong>{alt}</strong>
            <small>{dimensions || 'Imagem gerada'}</small>
          </span>
          <CaretDown size={15} className="caret" />
        </MediaDisclosureToggle>
        <CopyImageButton src={src} />
      </MediaDisclosureHeader>
      <MediaDisclosureBody id={bodyId} $open={open}>
        <img
          src={src}
          alt={alt}
          onLoad={(event) => setDimensions(`${event.currentTarget.naturalWidth} × ${event.currentTarget.naturalHeight}`)}
        />
      </MediaDisclosureBody>
    </MediaDisclosure>
  );
}

// memo evita re-renderizar cada bolha da conversa quando o Home re-renderiza
// por um motivo que nao tem nada a ver com mensagens (nivel de audio do
// microfone, fila de mensagens, etc). Só funciona porque handleChunkReceived
// (em Home) atualiza a ultima mensagem imutavelmente — ver comentário lá.
function MessageBubbleComponent({
  message,
  messageIndex,
  isStreaming = false,
  onOpenDiff,
  onApproveApproval,
  onRejectApproval,
  approvalLoadingId
}: MessageBubbleProps) {
  const isUser = message.role === 'user';
  const skillInvocation = isUser ? matchSkillInvocation(message.content) : null;
  const mediaContent = extractMediaJobs(message.content);
  const containsUiPreview = !isUser && hasUiPreview(mediaContent.markdown);
  const documentNames = (message.documentNames || '').split('\n').map(name => name.trim()).filter(Boolean);

  return (
    <Wrapper $isUser={isUser}>
      <BubbleContainer $isUser={isUser} $hasWideArtifact={containsUiPreview}>
        {!isUser && (
          <Header>
            <span className="ai-icon">A</span>
            <span className="ai-name">Avento</span>
            {message.content && <HeaderActions><CopyButton text={mediaContent.markdown.trim()} label="Copiar resposta" /></HeaderActions>}
          </Header>
        )}
        
        <Content>
          {documentNames.length > 0 && (
            <DocumentAttachmentList aria-label="Documentos anexados">
              {documentNames.map((name, index) => (
                <DocumentAttachmentBadge key={`${name}-${index}`} title={name}>
                  <FileText size={15} weight="duotone" />
                  <span>{name}</span>
                </DocumentAttachmentBadge>
              ))}
            </DocumentAttachmentList>
          )}
          {message.attachments && message.attachments.length > 0 && (
            <ImageAttachmentGrid>
              {message.attachments.map((attachment) => (
                <MediaPreview as={ImageAttachmentPreview} key={attachment.id}>
                  <img src={attachment.dataUrl} alt={attachment.name} />
                  <CopyImageButton src={attachment.dataUrl} />
                  <span>{attachment.name}</span>
                </MediaPreview>
              ))}
            </ImageAttachmentGrid>
          )}
          {message.thinking && !isUser && (
            <ThinkingBlock thinking={message.thinking} isStreaming={isStreaming} />
          )}
          {isUser && skillInvocation ? (
            <SkillInvocationBadge title="Skill do Avento">
              <strong>
                <Lightning size={13} weight="fill" />
                /{skillInvocation.name}
              </strong>
              {skillInvocation.argument && <span>{skillInvocation.argument}</span>}
            </SkillInvocationBadge>
          ) : !isUser && !hasVisibleContent(message.content) && !message.thinking ? (
            <TypingIndicator />
          ) : (
            <ReactMarkdown
              remarkPlugins={[remarkGfm]}
              components={{
                table({ children }) {
                  return <TableScroll><StyledTable>{children}</StyledTable></TableScroll>;
                },
                th({ children }) { return <StyledTh>{children}</StyledTh>; },
                td({ children }) { return <StyledTd>{children}</StyledTd>; },
                pre({ children, ...props }) {
                  // Bloco ```plan``` não deve aparecer no chat — os passos vão
                  // pro painel "Tarefas e Contexto" (ver extractPlanSteps em
                  // Home/index.tsx). Sem esse retorno cedo, ainda apareceria
                  // uma caixa de código vazia com botão de copiar.
                  const childClassName = React.isValidElement<{ className?: string }>(children)
                    ? children.props.className
                    : undefined;
                  if (childClassName?.includes('language-plan')) {
                    return null;
                  }

                  const code = textFromNode(children).replace(/\n$/, '');
                  if (childClassName?.includes('language-ui-preview')) {
                    return <UiPreviewCard html={code} />;
                  }
                  return (
                    <CodeBlock>
                      <CopyButton text={code} label="Copiar código" />
                      <pre {...props}>{children}</pre>
                    </CodeBlock>
                  );
                },
                img({ src, alt }) {
                  if (!src) return null;
                  return <CollapsibleGeneratedImage src={src} alt={alt || 'Imagem gerada'} />;
                },
                code(props) {
                  const {children, className, node, ...rest} = props;
                  const match = /language-(\w+-?\w*)/.exec(className || '');
                  
                  if (match && match[1] === 'file-edit') {
                    const contentStr = String(children).replace(/\n$/, '');
                    const lines = contentStr.split('\n');
                    
                    let filePath = '';
                    if (lines[0] && lines[0].startsWith('path: ')) {
                       filePath = lines[0].replace('path: ', '').trim();
                    }
                    
                    if (filePath) {
                      const fileName = filePath.split('/').pop() || filePath;
                      const separatorIndex = lines.indexOf('---');
                      const suggestedCode = separatorIndex >= 0
                        ? lines.slice(separatorIndex + 1).join('\n')
                        : contentStr;
                      return (
                        <FileEditCard>
                          <FileEditInfo>
                            <FileCode size={20} />
                            <span>Alteração sugerida em: {fileName}</span>
                          </FileEditInfo>
                          <DiffButton
                            onClick={() => onOpenDiff && onOpenDiff(messageIndex, filePath)}
                          >
                            Ver Diff <CaretRight weight="bold" />
                          </DiffButton>
                          <CopyButton text={suggestedCode} label="Copiar código sugerido" />
                        </FileEditCard>
                      );
                    }
                  }
                  
                  return <code {...rest} className={className}>{children}</code>;
                }
              }}
            >
              {mediaContent.markdown}
            </ReactMarkdown>
          )}
          {!isUser && mediaContent.imageJobIds.map(jobId => <ImageGenerationCard key={jobId} jobId={jobId} />)}
          {!isUser && mediaContent.videoJobIds.map(jobId => <VideoGenerationCard key={jobId} jobId={jobId} />)}
          {!isUser && mediaContent.documentNames.map(name => <DocumentCard key={name} filename={name} />)}
          {/* Cobre o intervalo entre chamadas de ferramenta: já existe texto
              visível na resposta, mas o agente ainda está trabalhando (esperando
              resultado de tool call, montando a próxima rodada) — sem isso, a
              única pista de "ainda ativo" era o texto ter parado de crescer. */}
          {!isUser && isStreaming && hasVisibleContent(message.content) && (
            <TypingIndicator />
          )}
          {!isUser && message.approval && (!message.approval.status || ['pending', 'running'].includes(message.approval.status)) && (
            <ApprovalCard
              toolName={message.approval.toolName}
              toolArguments={message.approval.toolArguments}
              isLoading={approvalLoadingId === message.approval.approvalId || message.approval.status === 'running'}
              onApprove={(comment) => onApproveApproval?.(message.approval!.approvalId, comment)}
              onReject={(comment) => onRejectApproval?.(message.approval!.approvalId, comment)}
            />
          )}
        </Content>

        {!isUser && message.tokens && (
          <FooterInfo>
            <small>
              ⏱️ {message.duration || '0.0'}s | 📦 {message.tokens} tokens
            </small>
          </FooterInfo>
        )}
      </BubbleContainer>
    </Wrapper>
  );
}

export const MessageBubble = memo(MessageBubbleComponent);
