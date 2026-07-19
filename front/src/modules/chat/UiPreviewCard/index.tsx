import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  ArrowsIn,
  ArrowsOut,
  CaretDown,
  CaretRight,
  Check,
  Copy,
  CursorClick,
  Desktop,
  DeviceMobile,
  DeviceTablet,
  DownloadSimple,
  MonitorPlay,
} from '@phosphor-icons/react';
import {
  ActionButton,
  Card,
  CardHeader,
  DeviceButton,
  DeviceSelector,
  Modal,
  ModalBackdrop,
  ModalHeader,
  PreviewFrame,
  PreviewStage,
  PreviewThumbnail,
  PreviewViewport,
  Title,
  Toolbar,
} from './styles';

type ViewportName = 'desktop' | 'tablet' | 'mobile';

interface Viewport {
  name: ViewportName;
  label: string;
  width: number;
  height: number;
  icon: typeof Desktop;
}

const VIEWPORTS: Viewport[] = [
  { name: 'desktop', label: 'Desktop 1440 px', width: 1440, height: 900, icon: Desktop },
  { name: 'tablet', label: 'Tablet 768 px', width: 768, height: 900, icon: DeviceTablet },
  { name: 'mobile', label: 'Celular 390 px', width: 390, height: 844, icon: DeviceMobile },
];

interface UiPreviewCardProps {
  html: string;
}

function previewTitle(html: string): string {
  const match = html.match(/<title[^>]*>([\s\S]*?)<\/title>/i);
  return match?.[1]?.replace(/<[^>]+>/g, '').trim() || 'Prévia de interface';
}

function buildPreviewDocument(html: string, previewId: string): string {
  const policy = [
    "default-src 'none'",
    "img-src data: blob:",
    "media-src data: blob:",
    "font-src data:",
    "style-src 'unsafe-inline'",
    "script-src 'unsafe-inline'",
    "connect-src 'none'",
    "object-src 'none'",
    "frame-src 'none'",
    "base-uri 'none'",
    "form-action 'none'",
  ].join('; ');
  const security = `<meta http-equiv="Content-Security-Policy" content="${policy}">`;
  const bridge = `<script>(function(){var id=${JSON.stringify(previewId)};var send=function(type,message){parent.postMessage({source:'avento-ui-preview',previewId:id,type:type,message:message||''},'*')};window.addEventListener('error',function(event){send('error',event.message)});window.addEventListener('unhandledrejection',function(event){send('error',String(event.reason||'Erro assíncrono'))});window.addEventListener('DOMContentLoaded',function(){send('ready')})})();<\/script>`;
  const source = html.trim();

  const appendBridge = (documentSource: string) => {
    if (/<\/body>/i.test(documentSource)) {
      return documentSource.replace(/<\/body>/i, `${bridge}</body>`);
    }
    if (/<\/html>/i.test(documentSource)) {
      return documentSource.replace(/<\/html>/i, `${bridge}</html>`);
    }
    return `${documentSource}${bridge}`;
  };

  if (/<head(?:\s[^>]*)?>/i.test(source)) {
    return appendBridge(source.replace(/<head(\s[^>]*)?>/i, (head) => `${head}${security}`));
  }
  if (/<html(?:\s[^>]*)?>/i.test(source)) {
    return appendBridge(source.replace(/<html(\s[^>]*)?>/i, (root) => `${root}<head>${security}</head>`));
  }
  return `<!doctype html><html><head>${security}<meta name="viewport" content="width=device-width, initial-scale=1"></head><body>${source}${bridge}</body></html>`;
}

function PreviewCanvas({
  previewDocument,
  viewport,
  title,
  interactive,
  compact = false,
}: {
  previewDocument: string;
  viewport: Viewport;
  title: string;
  interactive: boolean;
  compact?: boolean;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [scale, setScale] = useState(1);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const updateScale = () => {
      const availableWidth = Math.max(0, container.clientWidth - 24);
      const availableHeight = compact ? 172 : viewport.height;
      setScale(Math.min(1, availableWidth / viewport.width, availableHeight / viewport.height));
    };
    updateScale();
    const observer = new ResizeObserver(updateScale);
    observer.observe(container);
    return () => observer.disconnect();
  }, [compact, viewport.height, viewport.width]);

  return (
    <PreviewViewport ref={containerRef} $compact={compact}>
      <PreviewStage $width={viewport.width} $height={viewport.height} $scale={scale}>
        <PreviewFrame
          key={interactive ? 'interactive' : 'static'}
          title={`${title} em ${viewport.label}`}
          sandbox={interactive ? 'allow-scripts' : ''}
          srcDoc={previewDocument}
          $width={viewport.width}
          $height={viewport.height}
          $scale={scale}
        />
      </PreviewStage>
    </PreviewViewport>
  );
}

export function UiPreviewCard({ html }: UiPreviewCardProps) {
  const reactId = useId();
  const previewId = useMemo(() => `preview-${reactId.replace(/[^a-z0-9_-]/gi, '')}`, [reactId]);
  const title = useMemo(() => previewTitle(html), [html]);
  const previewDocument = useMemo(() => buildPreviewDocument(html, previewId), [html, previewId]);
  const [viewportName, setViewportName] = useState<ViewportName>('desktop');
  const [expanded, setExpanded] = useState(false);
  const [collapsed, setCollapsed] = useState(true);
  const [interactive, setInteractive] = useState(false);
  const [copied, setCopied] = useState(false);
  const [runtimeError, setRuntimeError] = useState('');
  const viewport = VIEWPORTS.find(item => item.name === viewportName) || VIEWPORTS[0];

  useEffect(() => {
    const receivePreviewEvent = (event: MessageEvent) => {
      if (event.data?.source !== 'avento-ui-preview' || event.data?.previewId !== previewId) return;
      if (event.data.type === 'ready') setRuntimeError('');
      if (event.data.type === 'error') setRuntimeError(String(event.data.message || 'Erro na prévia'));
    };
    window.addEventListener('message', receivePreviewEvent);
    return () => window.removeEventListener('message', receivePreviewEvent);
  }, [previewId]);

  useEffect(() => {
    if (!expanded) return;
    const previousOverflow = documentBodyOverflow();
    document.body.style.overflow = 'hidden';
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setExpanded(false);
    };
    window.addEventListener('keydown', closeOnEscape);
    return () => {
      document.body.style.overflow = previousOverflow;
      window.removeEventListener('keydown', closeOnEscape);
    };
  }, [expanded]);

  const copySource = async () => {
    try {
      await navigator.clipboard.writeText(html);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1400);
    } catch {
      setRuntimeError('O navegador não permitiu copiar o HTML');
    }
  };

  const downloadSource = () => {
    const blob = new Blob([html], { type: 'text/html;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `${title.toLowerCase().replace(/[^a-z0-9]+/gi, '-').replace(/^-|-$/g, '') || 'avento-preview'}.html`;
    anchor.click();
    URL.revokeObjectURL(url);
  };

  const controls = (
    <Toolbar>
      <DeviceSelector aria-label="Tamanho da prévia">
        {VIEWPORTS.map(item => {
          const Icon = item.icon;
          return (
            <DeviceButton
              key={item.name}
              type="button"
              $active={viewportName === item.name}
              onClick={() => setViewportName(item.name)}
              aria-label={item.label}
              title={item.label}
              aria-pressed={viewportName === item.name}
            >
              <Icon size={18} weight={viewportName === item.name ? 'fill' : 'regular'} />
            </DeviceButton>
          );
        })}
      </DeviceSelector>
      <ActionButton type="button" onClick={copySource} aria-label={copied ? 'Código copiado' : 'Copiar HTML'} title={copied ? 'Código copiado' : 'Copiar HTML'}>
        {copied ? <Check size={18} weight="bold" /> : <Copy size={18} />}
      </ActionButton>
      <ActionButton type="button" onClick={downloadSource} aria-label="Salvar HTML" title="Salvar HTML">
        <DownloadSimple size={18} />
      </ActionButton>
      <ActionButton
        type="button"
        $active={interactive}
        onClick={() => setInteractive(current => !current)}
        aria-label={interactive ? 'Desativar interações' : 'Ativar interações'}
        title={interactive ? 'Desativar interações' : 'Ativar interações'}
        aria-pressed={interactive}
      >
        <CursorClick size={18} weight={interactive ? 'fill' : 'regular'} />
      </ActionButton>
      <ActionButton type="button" onClick={() => setExpanded(true)} aria-label="Expandir prévia" title="Expandir prévia">
        <ArrowsOut size={18} />
      </ActionButton>
    </Toolbar>
  );

  return (
    <>
      <Card>
        <CardHeader>
          <Title>
            <MonitorPlay size={19} weight="duotone" />
            <span>
              <strong>{title}</strong>
              <small>{runtimeError || `${viewport.label} · HTML local interativo`}</small>
            </span>
          </Title>
          {controls}
          <ActionButton
            type="button"
            onClick={() => setCollapsed(current => !current)}
            aria-label={collapsed ? 'Expandir mockup no chat' : 'Recolher mockup'}
            title={collapsed ? 'Expandir mockup no chat' : 'Recolher mockup'}
            aria-expanded={!collapsed}
          >
            {collapsed ? <CaretRight size={18} /> : <CaretDown size={18} />}
          </ActionButton>
        </CardHeader>
        {collapsed ? (
          <PreviewThumbnail
            role="button"
            tabIndex={0}
            aria-label={`Abrir miniatura: ${title}`}
            onClick={() => setCollapsed(false)}
            onKeyDown={event => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                setCollapsed(false);
              }
            }}
          >
            <PreviewCanvas
              previewDocument={previewDocument}
              viewport={viewport}
              title={title}
              interactive={false}
              compact
            />
          </PreviewThumbnail>
        ) : (
          <PreviewCanvas previewDocument={previewDocument} viewport={viewport} title={title} interactive={interactive} />
        )}
      </Card>

      {expanded && createPortal(
        <ModalBackdrop role="presentation" onMouseDown={() => setExpanded(false)}>
          <Modal role="dialog" aria-modal="true" aria-label={`Prévia expandida: ${title}`} onMouseDown={event => event.stopPropagation()}>
            <ModalHeader>
              <Title>
                <MonitorPlay size={20} weight="duotone" />
                <span><strong>{title}</strong><small>{viewport.label}</small></span>
              </Title>
              {controls}
              <ActionButton autoFocus type="button" onClick={() => setExpanded(false)} aria-label="Fechar prévia" title="Fechar prévia">
                <ArrowsIn size={18} />
              </ActionButton>
            </ModalHeader>
            <PreviewCanvas previewDocument={previewDocument} viewport={viewport} title={title} interactive={interactive} />
          </Modal>
        </ModalBackdrop>,
        document.body,
      )}
    </>
  );
}

function documentBodyOverflow(): string {
  return document.body.style.overflow;
}
