import { useCallback, useEffect, useRef, useState } from 'react';
import { CaretDown, Check, Copy, FilmSlate, SpinnerGap, StopCircle, WarningCircle } from '@phosphor-icons/react';
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
} from './styles';

interface VideoJob {
  id: string;
  status: 'queued' | 'submitting' | 'generating' | 'saving' | 'completed' | 'failed' | 'cancelled';
  stage: string;
  progress: number;
  estimatedRemainingSeconds: number | null;
  elapsedSeconds: number;
  width: number;
  height: number;
  frames: number;
  fps: number;
  prompt: string;
  sourceImageUrl: string;
  mediaUrl: string;
  error: string;
}

const TERMINAL_STATUSES = new Set<VideoJob['status']>(['completed', 'failed', 'cancelled']);

function storedMediaOpen(jobId: string): boolean {
  try {
    return window.sessionStorage.getItem(`avento:video-disclosure:${jobId}`) === 'open';
  } catch {
    return false;
  }
}

function formatDuration(seconds: number | null): string {
  if (seconds === null || !Number.isFinite(seconds)) return 'Calculando';
  const safeSeconds = Math.max(0, Math.round(seconds));
  const hours = Math.floor(safeSeconds / 3600);
  const minutes = Math.floor((safeSeconds % 3600) / 60);
  const remainingSeconds = safeSeconds % 60;
  if (hours > 0) return `${hours}h ${minutes}min`;
  if (minutes > 0) return `${minutes}min ${remainingSeconds}s`;
  return `${remainingSeconds}s`;
}

export function VideoGenerationCard({ jobId }: { jobId: string }) {
  const [job, setJob] = useState<VideoJob | null>(null);
  const [error, setError] = useState('');
  const [cancelling, setCancelling] = useState(false);
  const [copied, setCopied] = useState(false);
  const [mediaOpen, setMediaOpen] = useState(() => storedMediaOpen(jobId));
  const mountedRef = useRef(true);
  const announcedMediaRef = useRef('');

  const loadJob = useCallback(async () => {
    try {
      const { data } = await api.get<VideoJob>(`/api/videos/${jobId}`);
      if (mountedRef.current) {
        setJob(data);
        setError('');
      }
      return data;
    } catch (requestError) {
      if (mountedRef.current) setError(apiErrorMessage(requestError));
      return null;
    }
  }, [jobId]);

  useEffect(() => {
    mountedRef.current = true;
    let timeoutId: number | undefined;

    const poll = async () => {
      const current = await loadJob();
      if (mountedRef.current && current && !TERMINAL_STATUSES.has(current.status)) {
        timeoutId = window.setTimeout(poll, 2_000);
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
    window.dispatchEvent(new CustomEvent('avento:video-completed', { detail: { mediaUrl: job.mediaUrl } }));
  }, [job?.mediaUrl]);

  const cancel = async () => {
    setCancelling(true);
    try {
      const { data } = await api.post<VideoJob>(`/api/videos/${jobId}/cancel`);
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
      const response = await fetch(job.mediaUrl);
      const blob = await response.blob();
      if (navigator.clipboard?.write && typeof ClipboardItem !== 'undefined') {
        await navigator.clipboard.write([new ClipboardItem({ [blob.type || 'image/webp']: blob })]);
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
        window.sessionStorage.setItem(`avento:video-disclosure:${jobId}`, next ? 'open' : 'closed');
      } catch {
        // O estado local continua funcionando quando o storage não está disponível.
      }
      return next;
    });
  };

  const active = job && !TERMINAL_STATUSES.has(job.status);
  const animatingImage = Boolean(job?.sourceImageUrl);
  const title = job?.status === 'completed'
    ? animatingImage ? 'Animação pronta' : 'Vídeo pronto'
    : job?.status === 'failed'
      ? 'Falha ao gerar vídeo'
      : job?.status === 'cancelled'
        ? 'Vídeo cancelado'
      : animatingImage ? 'Animando imagem' : 'Gerando vídeo';

  return (
    <Card aria-live="polite">
      <Header>
        <StatusIcon $status={job?.status || 'queued'}>
          {active ? <SpinnerGap size={18} /> : job?.status === 'failed' ? <WarningCircle size={18} /> : <FilmSlate size={18} />}
        </StatusIcon>
        <div>
          <strong>{title}</strong>
          <span>{job?.stage || 'Carregando o estado'}</span>
        </div>
        {(active || job?.mediaUrl) && (
          <Actions>
            {job?.mediaUrl && (
              <>
                <MediaAction type="button" onClick={copyMedia} title="Copiar vídeo" aria-label="Copiar vídeo">
                  {copied ? <Check size={17} weight="bold" /> : <Copy size={17} />}
                </MediaAction>
                <MediaToggle
                  type="button"
                  $open={mediaOpen}
                  onClick={toggleMedia}
                  title={mediaOpen ? 'Minimizar mídia' : 'Expandir mídia'}
                  aria-label={mediaOpen ? 'Minimizar vídeo' : 'Expandir vídeo'}
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
          <img src={job.mediaUrl} alt="Vídeo gerado pelo Avento" />
        </Media>
      )}

      {(error || job?.error) && <ErrorText>{error || job?.error}</ErrorText>}
    </Card>
  );
}
