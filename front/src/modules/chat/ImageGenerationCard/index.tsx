import { useCallback, useEffect, useRef, useState } from 'react';
import axios from 'axios';
import { CaretDown, Check, Copy, ImageSquare, SpinnerGap, StopCircle, WarningCircle } from '@phosphor-icons/react';
import { api, apiErrorMessage } from '../../../services/apiClient';
import {
  Actions,
  CancelButton,
  Card,
  Detail,
  ErrorText,
  Header,
  Media,
  MediaAction,
  MediaToggle,
  ProgressBar,
  ProgressFill,
  StatusIcon,
  TimeGrid,
} from '../VideoGenerationCard/styles';

interface ImageJob {
  id: string;
  status: 'queued' | 'preparing' | 'generating' | 'saving' | 'completed' | 'failed' | 'cancelled';
  stage: string;
  progress: number;
  estimatedRemainingSeconds: number | null;
  elapsedSeconds: number;
  prompt: string;
  model: string;
  size: string;
  mediaUrl: string;
  error: string;
}

const TERMINAL_STATUSES = new Set<ImageJob['status']>(['completed', 'failed', 'cancelled']);

function storedMediaOpen(jobId: string): boolean {
  try {
    return window.sessionStorage.getItem(`avento:image-disclosure:${jobId}`) === 'open';
  } catch {
    return false;
  }
}

function formatDuration(seconds: number | null): string {
  if (seconds === null || !Number.isFinite(seconds)) return 'Calculando';
  const safeSeconds = Math.max(0, Math.round(seconds));
  const minutes = Math.floor(safeSeconds / 60);
  const remainingSeconds = safeSeconds % 60;
  return minutes > 0 ? `${minutes}min ${remainingSeconds}s` : `${remainingSeconds}s`;
}

export function ImageGenerationCard({ jobId }: { jobId: string }) {
  const [job, setJob] = useState<ImageJob | null>(null);
  const [error, setError] = useState('');
  const [cancelling, setCancelling] = useState(false);
  const [copied, setCopied] = useState(false);
  const [mediaOpen, setMediaOpen] = useState(() => storedMediaOpen(jobId));
  const mountedRef = useRef(true);
  const consecutiveFailuresRef = useRef(0);
  const announcedMediaRef = useRef('');

  const loadJob = useCallback(async () => {
    try {
      const { data } = await api.get<ImageJob>(`/api/images/${jobId}`);
      if (mountedRef.current) {
        setJob(data);
        setError('');
        consecutiveFailuresRef.current = 0;
      }
      return { job: data, retry: false };
    } catch (requestError) {
      const notFound = axios.isAxiosError(requestError) && requestError.response?.status === 404;
      consecutiveFailuresRef.current += 1;
      if (mountedRef.current) {
        setError(notFound
          ? 'Esta geração não existe mais ou foi removida com o chat.'
          : `Conexão interrompida. Tentando novamente (${consecutiveFailuresRef.current})...`);
      }
      return { job: null, retry: !notFound };
    }
  }, [jobId]);

  useEffect(() => {
    mountedRef.current = true;
    let timeoutId: number | undefined;

    const poll = async () => {
      const result = await loadJob();
      const shouldPollJob = result.job && !TERMINAL_STATUSES.has(result.job.status);
      if (mountedRef.current && (shouldPollJob || result.retry)) {
        const retryDelay = result.retry
          ? Math.min(15_000, 2_000 * 2 ** Math.min(3, consecutiveFailuresRef.current - 1))
          : 2_000;
        timeoutId = window.setTimeout(poll, retryDelay);
      }
    };

    void poll();
    return () => {
      mountedRef.current = false;
      if (timeoutId) window.clearTimeout(timeoutId);
    };
  }, [loadJob]);

  useEffect(() => {
    if (!job?.mediaUrl || announcedMediaRef.current === job.mediaUrl) return;
    announcedMediaRef.current = job.mediaUrl;
    window.dispatchEvent(new CustomEvent('avento:image-completed', { detail: { mediaUrl: job.mediaUrl } }));
  }, [job?.mediaUrl]);

  const cancel = async () => {
    setCancelling(true);
    try {
      const { data } = await api.post<ImageJob>(`/api/images/${jobId}/cancel`);
      setJob(data);
      setError('');
    } catch (requestError) {
      setError(apiErrorMessage(requestError));
    } finally {
      setCancelling(false);
    }
  };

  const copyMedia = async () => {
    if (!job?.mediaUrl) return;
    try {
      const { data } = await api.get<Blob>(job.mediaUrl, { responseType: 'blob' });
      if (navigator.clipboard?.write && typeof ClipboardItem !== 'undefined') {
        await navigator.clipboard.write([new ClipboardItem({ [data.type || 'image/png']: data })]);
      } else {
        await navigator.clipboard.writeText(job.mediaUrl);
      }
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1_400);
    } catch (copyError) {
      setError(apiErrorMessage(copyError));
    }
  };

  const toggleMedia = () => {
    setMediaOpen(current => {
      const next = !current;
      try {
        window.sessionStorage.setItem(`avento:image-disclosure:${jobId}`, next ? 'open' : 'closed');
      } catch {
        // O estado local continua funcionando quando o storage não está disponível.
      }
      return next;
    });
  };

  const active = Boolean(job && !TERMINAL_STATUSES.has(job.status));
  const title = job?.status === 'completed'
    ? 'Imagem pronta'
    : job?.status === 'failed'
      ? 'Falha ao gerar imagem'
      : job?.status === 'cancelled'
        ? 'Imagem cancelada'
        : 'Gerando imagem';

  return (
    <Card aria-live="polite">
      <Header>
        <StatusIcon $status={job?.status || 'queued'}>
          {active ? <SpinnerGap size={18} /> : job?.status === 'failed' ? <WarningCircle size={18} /> : <ImageSquare size={18} />}
        </StatusIcon>
        <div>
          <strong>{title}</strong>
          <span>{job?.stage || (error ? 'Reconectando ao job' : 'Carregando o estado')}</span>
        </div>
        {(active || job?.mediaUrl) && (
          <Actions>
            {job?.mediaUrl && (
              <>
                <MediaAction type="button" onClick={copyMedia} title="Copiar imagem" aria-label="Copiar imagem">
                  {copied ? <Check size={17} weight="bold" /> : <Copy size={17} />}
                </MediaAction>
                <MediaToggle
                  type="button"
                  $open={mediaOpen}
                  onClick={toggleMedia}
                  title={mediaOpen ? 'Minimizar mídia' : 'Expandir mídia'}
                  aria-label={mediaOpen ? 'Minimizar imagem' : 'Expandir imagem'}
                  aria-expanded={mediaOpen}
                >
                  <CaretDown size={17} />
                </MediaToggle>
              </>
            )}
            {active && (
              <CancelButton type="button" onClick={cancel} disabled={cancelling} title="Cancelar geração" aria-label="Cancelar geração">
                <StopCircle size={18} />
              </CancelButton>
            )}
          </Actions>
        )}
      </Header>

      {job && job.status !== 'completed' && (
        <>
          <ProgressBar role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={job.progress}>
            <ProgressFill $progress={job.progress} />
          </ProgressBar>
          <TimeGrid>
            <Detail><span>Progresso estimado</span><strong>{job.progress}%</strong></Detail>
            <Detail><span>Decorrido</span><strong>{formatDuration(job.elapsedSeconds)}</strong></Detail>
            <Detail><span>Estimativa</span><strong>{job.status === 'failed' || job.status === 'cancelled' ? 'Encerrado' : formatDuration(job.estimatedRemainingSeconds)}</strong></Detail>
          </TimeGrid>
        </>
      )}

      {job?.mediaUrl && (
        <Media $open={mediaOpen}>
          <img src={job.mediaUrl} alt="Imagem gerada pelo Avento" />
        </Media>
      )}

      {(error || job?.error) && <ErrorText>{error || job?.error}</ErrorText>}
    </Card>
  );
}
