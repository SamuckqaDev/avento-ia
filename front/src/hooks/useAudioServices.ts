import { useState, useRef, useCallback } from 'react';
import { api } from '../services/apiClient';

type RealtimeTranscriptPayload = {
  text: string;
  language?: string;
};

const PORTUGUESE_MARKERS = new Set([
  'bom', 'dia', 'boa', 'noite', 'voce', 'você', 'preciso', 'quero', 'cara', 'mano',
  'pra', 'para', 'isso', 'aqui', 'meu', 'minha', 'como', 'que', 'nao', 'não',
  'portugues', 'português', 'responda', 'fale', 'projeto'
]);

const ENGLISH_MARKERS = new Set([
  'i', 'me', 'my', 'you', 'your', 'we', 'need', 'want', 'please', 'can', 'could',
  'would', 'what', 'how', 'this', 'that', 'the', 'a', 'an', 'is', 'are', 'do',
  'make', 'fix', 'explain', 'answer', 'english'
]);

const SPANISH_MARKERS = new Set([
  'hola', 'buenos', 'dias', 'días', 'necesito', 'quiero', 'puedes', 'como',
  'cómo', 'que', 'qué', 'esto', 'proyecto', 'responde', 'espanol', 'español'
]);

function preferredSpeechLanguage(): string {
  const languages = navigator.languages?.length ? navigator.languages : [navigator.language];
  const preferred = languages.find(language => language?.toLowerCase().startsWith('pt')) || languages[0];
  return preferred || 'pt-BR';
}

function scoreLanguage(text: string, markers: Set<string>): number {
  const words = text
    .toLowerCase()
    .normalize('NFC')
    .replace(/[^\p{L}\p{N}]+/gu, ' ')
    .trim()
    .split(/\s+/)
    .filter(Boolean);

  return words.reduce((score, word) => score + (markers.has(word) ? 1 : 0), 0);
}

function detectSpeechLanguage(text: string, fallback = 'pt'): string {
  const normalized = text.trim();
  if (!normalized) return fallback;

  if (/[ãõçáéíóúâêôà]/i.test(normalized)) return 'pt';
  if (/[ñ¿¡]/i.test(normalized)) return 'es';

  const scores = [
    { language: 'pt', score: scoreLanguage(normalized, PORTUGUESE_MARKERS) },
    { language: 'en', score: scoreLanguage(normalized, ENGLISH_MARKERS) },
    { language: 'es', score: scoreLanguage(normalized, SPANISH_MARKERS) }
  ].sort((a, b) => b.score - a.score);

  return scores[0].score > 0 ? scores[0].language : fallback;
}

function splitSpeechChunks(text: string, maxChars = 280): string[] {
  const sentences = text
    .replace(/\r/g, '')
    .split(/(?<=[.!?])\s+|\n+/)
    .map(sentence => sentence.trim())
    .filter(Boolean);
  const chunks: string[] = [];
  let current = '';
  for (const sentence of sentences) {
    if (!current) {
      current = sentence;
      continue;
    }
    if (`${current} ${sentence}`.length <= maxChars) {
      current = `${current} ${sentence}`;
    } else {
      chunks.push(current);
      current = sentence;
    }
  }
  if (current) chunks.push(current);
  return chunks.length > 0 ? chunks : [text.trim()];
}

export function useAudioServices() {
  const [isRecording, setIsRecording] = useState<boolean>(false);
  const [isRealtimeVoiceActive, setIsRealtimeVoiceActive] = useState<boolean>(false);
  const [isRealtimeListening, setIsRealtimeListening] = useState<boolean>(false);
  const [realtimeTranscript, setRealtimeTranscript] = useState<string>('');
  // Nivel de audio (0 a 1) atualizado enquanto o microfone esta capturando,
  // seja no modo de gravacao por clique ou no modo de voz em tempo real —
  // da confirmacao visual real de que o som esta chegando, nao so um estado
  // "gravando" estatico.
  const [audioLevel, setAudioLevel] = useState<number>(0);
  const audioLevelContextRef = useRef<AudioContext | null>(null);
  const audioLevelFrameRef = useRef<number | null>(null);
  const lastAudioLevelUpdateRef = useRef<number>(0);
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const audioChunksRef = useRef<BlobPart[]>([]);
  const realtimeSocketRef = useRef<WebSocket | null>(null);
  const realtimeStreamRef = useRef<MediaStream | null>(null);
  const realtimeRecorderRef = useRef<MediaRecorder | null>(null);
  const realtimeAudioContextRef = useRef<AudioContext | null>(null);
  const realtimeAnalyserFrameRef = useRef<number | null>(null);
  const realtimeAudioChunksRef = useRef<BlobPart[]>([]);
  const speechDetectedRef = useRef<boolean>(false);
  const silenceStartedAtRef = useRef<number | null>(null);
  const isStoppingUtteranceRef = useRef<boolean>(false);
  const realtimeVoiceActiveRef = useRef<boolean>(false);
  const onRealtimeFinalTranscriptRef = useRef<((payload: RealtimeTranscriptPayload) => void) | null>(null);
  const onRealtimeUserSpeechStartRef = useRef<(() => void) | null>(null);
  const realtimeRecorderIdRef = useRef<number>(0);
  const lastBargeInAtRef = useRef<number>(0);

  // --- TTS (Audio Queue) ---
  const audioQueueRef = useRef<string[]>([]);
  const isPlayingRef = useRef<boolean>(false);
  const isAudioPlaybackActiveRef = useRef<boolean>(false);
  const audioPlaybackEnabledRef = useRef<boolean>(true);
  const currentAudioRef = useRef<HTMLAudioElement | null>(null);
  const ttsGenerationRef = useRef<number>(0);
  const ttsRequestControllersRef = useRef<Set<AbortController>>(new Set());

  const playNextAudio = useCallback(() => {
    if (!audioPlaybackEnabledRef.current) {
      audioQueueRef.current.forEach(URL.revokeObjectURL);
      audioQueueRef.current = [];
      isPlayingRef.current = false;
      isAudioPlaybackActiveRef.current = false;
      currentAudioRef.current = null;
      return;
    }
    if (audioQueueRef.current.length === 0) {
      isPlayingRef.current = false;
      isAudioPlaybackActiveRef.current = false;
      currentAudioRef.current = null;
      return;
    }

    isPlayingRef.current = true;
    isAudioPlaybackActiveRef.current = true;
    const audioUrl = audioQueueRef.current.shift()!;
    const audio = new Audio(audioUrl);
    currentAudioRef.current = audio;
    
    audio.onended = () => {
      URL.revokeObjectURL(audioUrl);
      playNextAudio();
    };

    audio.onerror = (e) => {
      console.error("Erro na reprodução de áudio", e);
      URL.revokeObjectURL(audioUrl);
      playNextAudio();
    };

    audio.play().catch(e => {
      console.error("Auto-play prevention or error", e);
      playNextAudio();
    });
  }, []);

  const stopAudioPlayback = useCallback(() => {
    ttsGenerationRef.current += 1;
    ttsRequestControllersRef.current.forEach(controller => controller.abort());
    ttsRequestControllersRef.current.clear();
    audioQueueRef.current.forEach(URL.revokeObjectURL);
    audioQueueRef.current = [];
    if (currentAudioRef.current) {
      const currentAudioUrl = currentAudioRef.current.src;
      currentAudioRef.current.onended = null;
      currentAudioRef.current.onerror = null;
      currentAudioRef.current.pause();
      currentAudioRef.current.currentTime = 0;
      currentAudioRef.current = null;
      if (currentAudioUrl.startsWith('blob:')) {
        URL.revokeObjectURL(currentAudioUrl);
      }
    }
    isPlayingRef.current = false;
    isAudioPlaybackActiveRef.current = false;
  }, []);

  const setAudioPlaybackEnabled = useCallback((enabled: boolean) => {
    if (audioPlaybackEnabledRef.current === enabled) {
      return;
    }
    audioPlaybackEnabledRef.current = enabled;
    if (!enabled) {
      stopAudioPlayback();
    }
  }, [stopAudioPlayback]);

  const queueTextToSpeech = useCallback(async (text: string) => {
    if (!audioPlaybackEnabledRef.current || !text || text.trim() === '') return;
    const generation = ttsGenerationRef.current;
    const language = detectSpeechLanguage(text);
    try {
      for (const chunk of splitSpeechChunks(text)) {
        if (!audioPlaybackEnabledRef.current || generation !== ttsGenerationRef.current) return;
        const controller = new AbortController();
        ttsRequestControllersRef.current.add(controller);
        const response = await api.post<Blob>('/api/voice/tts', { text: chunk, language }, {
          responseType: 'blob',
          signal: controller.signal,
        }).finally(() => ttsRequestControllersRef.current.delete(controller));
        if (!audioPlaybackEnabledRef.current || generation !== ttsGenerationRef.current) return;
        const blob = response.data;
        if (!blob?.size) continue;
        audioQueueRef.current.push(URL.createObjectURL(blob));
        if (!isPlayingRef.current) {
          playNextAudio();
        }
      }
    } catch (error) {
      if (audioPlaybackEnabledRef.current && generation === ttsGenerationRef.current) {
        console.error("TTS Queue Error", error);
      }
    }
  }, [playNextAudio]);

  // Atualiza audioLevel no maximo a cada ~80ms (throttle) para nao forcar um
  // re-render do Home inteiro a 60fps enquanto o microfone esta aberto.
  const reportAudioLevel = useCallback((volume: number) => {
    const now = Date.now();
    if (now - lastAudioLevelUpdateRef.current > 80) {
      lastAudioLevelUpdateRef.current = now;
      setAudioLevel(Math.min(1, volume * 4));
    }
  }, []);

  const stopAudioLevelMonitor = useCallback(() => {
    if (audioLevelFrameRef.current !== null) {
      window.cancelAnimationFrame(audioLevelFrameRef.current);
      audioLevelFrameRef.current = null;
    }
    audioLevelContextRef.current?.close().catch(() => {});
    audioLevelContextRef.current = null;
    setAudioLevel(0);
  }, []);

  const startAudioLevelMonitor = useCallback((stream: MediaStream) => {
    const AudioContextCtor = window.AudioContext || (window as Window & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!AudioContextCtor) return;

    const audioContext = new AudioContextCtor();
    audioLevelContextRef.current = audioContext;
    const source = audioContext.createMediaStreamSource(stream);
    const analyser = audioContext.createAnalyser();
    analyser.fftSize = 1024;
    source.connect(analyser);
    const data = new Uint8Array(analyser.fftSize);

    const tick = () => {
      analyser.getByteTimeDomainData(data);
      let sum = 0;
      for (const value of data) {
        const normalized = (value - 128) / 128;
        sum += normalized * normalized;
      }
      reportAudioLevel(Math.sqrt(sum / data.length));
      audioLevelFrameRef.current = window.requestAnimationFrame(tick);
    };

    audioLevelFrameRef.current = window.requestAnimationFrame(tick);
  }, [reportAudioLevel]);

  // --- STT (Mic Recording) ---
  const startRecording = useCallback(async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      mediaRecorderRef.current = new MediaRecorder(stream);
      audioChunksRef.current = [];

      mediaRecorderRef.current.ondataavailable = event => {
        audioChunksRef.current.push(event.data);
      };

      mediaRecorderRef.current.start();
      setIsRecording(true);
      // O medidor de nível é só um indicador visual — se falhar por qualquer
      // motivo (AudioContext bloqueado, navegador restrito etc.), não pode
      // derrubar a gravação/transcrição real, que já está rodando nesse ponto.
      try {
        startAudioLevelMonitor(stream);
      } catch (levelError) {
        console.error("Erro ao iniciar medidor de nível de áudio (gravação continua normal)", levelError);
      }
    } catch (err) {
      console.error("Erro ao acessar microfone", err);
      alert("Permissão de microfone negada ou indisponível.");
    }
  }, [startAudioLevelMonitor]);

  const stopRecording = useCallback((): Promise<string | null> => {
    return new Promise((resolve, reject) => {
      stopAudioLevelMonitor();
      if (!mediaRecorderRef.current) {
        resolve(null);
        return;
      }

      mediaRecorderRef.current.onstop = async () => {
        const audioBlob = new Blob(audioChunksRef.current, { type: 'audio/webm' });
        const formData = new FormData();
        formData.append("audio", audioBlob, "recording.webm");
        formData.append("preferredLanguage", preferredSpeechLanguage());

        try {
          // Sem responseType 'text': o endpoint devolve BaseResponse<String>, entao deixamos o
          // Axios parsear o JSON e o interceptor desembrulhar `data`. Com 'text', a string crua
          // pulava o desembrulho e o envelope inteiro ({"status":...,"data":"..."}) ia pro chat.
          const { data: text } = await api.post<string>('/api/voice/transcribe', formData);
          resolve(text);
        } catch (error) {
          console.error("Transcribe error", error);
          reject(error);
        }
      };

      mediaRecorderRef.current.stop();
      setIsRecording(false);
    });
  }, []);

  // --- Realtime voice recognition ---
  const speechRecognitionSupported = typeof window !== 'undefined' && Boolean(
    navigator.mediaDevices && typeof WebSocket !== 'undefined'
  );

  const stopRealtimeVoice = useCallback(() => {
    realtimeVoiceActiveRef.current = false;
    setIsRealtimeVoiceActive(false);
    setIsRealtimeListening(false);
    setRealtimeTranscript('');
    setAudioLevel(0);
    if (realtimeAnalyserFrameRef.current !== null) {
      window.cancelAnimationFrame(realtimeAnalyserFrameRef.current);
      realtimeAnalyserFrameRef.current = null;
    }
    if (realtimeRecorderRef.current && realtimeRecorderRef.current.state !== 'inactive') {
      realtimeRecorderRef.current.stop();
    }
    realtimeRecorderRef.current = null;
    if (realtimeSocketRef.current) {
      realtimeSocketRef.current.close();
      realtimeSocketRef.current = null;
    }
    if (realtimeAudioContextRef.current) {
      realtimeAudioContextRef.current.close().catch(() => undefined);
      realtimeAudioContextRef.current = null;
    }
    realtimeStreamRef.current?.getTracks().forEach(track => track.stop());
    realtimeStreamRef.current = null;
    realtimeAudioChunksRef.current = [];
    speechDetectedRef.current = false;
    silenceStartedAtRef.current = null;
    isStoppingUtteranceRef.current = false;
    realtimeRecorderIdRef.current += 1;
  }, []);

  const startWebSocketRealtimeVoice = useCallback(async (
    onFinalTranscript: (payload: RealtimeTranscriptPayload) => void,
    onUserSpeechStart?: () => void
  ) => {
    if (!navigator.mediaDevices?.getUserMedia || typeof WebSocket === 'undefined') {
      return false;
    }

    try {
      stopAudioPlayback();
      onRealtimeFinalTranscriptRef.current = onFinalTranscript;
      onRealtimeUserSpeechStartRef.current = onUserSpeechStart || null;
      realtimeVoiceActiveRef.current = true;
      setIsRealtimeVoiceActive(true);
      setIsRealtimeListening(true);
      setRealtimeTranscript('Conectando voz local...');

      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true
        }
      });
      realtimeStreamRef.current = stream;

      const socketProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const socket = new WebSocket(`${socketProtocol}//${window.location.host}/ws/voice`);
      realtimeSocketRef.current = socket;
      const speechThreshold = 0.02;
      const bargeInThreshold = 0.022;
      const silenceMs = 850;

      const startRecorder = () => {
        if (!realtimeVoiceActiveRef.current || !realtimeStreamRef.current || !realtimeSocketRef.current || realtimeSocketRef.current.readyState !== WebSocket.OPEN) {
          return;
        }

        speechDetectedRef.current = false;
        silenceStartedAtRef.current = null;
        isStoppingUtteranceRef.current = false;
        realtimeAudioChunksRef.current = [];
        const mimeType = MediaRecorder.isTypeSupported('audio/webm;codecs=opus') ? 'audio/webm;codecs=opus' : 'audio/webm';
        const recorder = new MediaRecorder(realtimeStreamRef.current, { mimeType });
        const recorderId = realtimeRecorderIdRef.current + 1;
        realtimeRecorderIdRef.current = recorderId;
        realtimeRecorderRef.current = recorder;

        recorder.ondataavailable = (event) => {
          if (
            recorderId !== realtimeRecorderIdRef.current ||
            (isAudioPlaybackActiveRef.current && !speechDetectedRef.current) ||
            !event.data.size
          ) {
            return;
          }
          realtimeAudioChunksRef.current.push(event.data);
        };

        recorder.onstop = async () => {
          if (recorderId !== realtimeRecorderIdRef.current) {
            return;
          }
          const chunks = realtimeAudioChunksRef.current;
          realtimeAudioChunksRef.current = [];

          try {
            if (realtimeSocketRef.current?.readyState === WebSocket.OPEN && speechDetectedRef.current && chunks.length > 0) {
              const audioBlob = new Blob(chunks, { type: recorder.mimeType || mimeType });
              realtimeSocketRef.current.send(await audioBlob.arrayBuffer());
              realtimeSocketRef.current.send(JSON.stringify({
                type: 'flush',
                preferredLanguage: preferredSpeechLanguage()
              }));
              setRealtimeTranscript('Transcrevendo...');
            }
          } catch (error) {
            console.error('Erro ao enviar frase de voz', error);
            setRealtimeTranscript('Erro ao enviar áudio para transcrição.');
          } finally {
            if (realtimeVoiceActiveRef.current) {
              window.setTimeout(startRecorder, 180);
            }
          }
        };

        recorder.start(250);
        setRealtimeTranscript('Ouvindo...');
      };

      const handleUserBargeIn = () => {
        const now = Date.now();
        if (now - lastBargeInAtRef.current < 900) {
          return;
        }
        lastBargeInAtRef.current = now;
        stopAudioPlayback();
        onRealtimeUserSpeechStartRef.current?.();
        speechDetectedRef.current = true;
        silenceStartedAtRef.current = null;
        isStoppingUtteranceRef.current = false;
        setIsRealtimeListening(true);
        setRealtimeTranscript('Pode falar, interrompi a resposta.');
      };

      socket.onopen = () => {
        socket.send(JSON.stringify({ type: 'reset' }));
        startRecorder();
      };

      socket.onmessage = (event) => {
        try {
          const payload = JSON.parse(event.data);
          if (payload.type === 'transcript.final' && payload.text) {
            setRealtimeTranscript(payload.text);
            onRealtimeFinalTranscriptRef.current?.({
              text: payload.text,
              language: typeof payload.language === 'string' ? payload.language : undefined
            });
          } else if (payload.type === 'transcribing') {
            setRealtimeTranscript('Transcrevendo...');
          } else if (payload.type === 'error') {
            setRealtimeTranscript(payload.message || 'Erro no WebSocket de voz.');
          }
        } catch (error) {
          console.error('Erro interpretando mensagem de voz', error);
        }
      };

      socket.onerror = () => {
        setRealtimeTranscript('Erro no WebSocket de voz.');
      };

      socket.onclose = () => {
        if (realtimeVoiceActiveRef.current) {
          setIsRealtimeListening(false);
          setRealtimeTranscript('Conexão de voz encerrada.');
        }
      };

      const AudioContextCtor = window.AudioContext || (window as Window & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
      if (AudioContextCtor) {
        const audioContext = new AudioContextCtor();
        realtimeAudioContextRef.current = audioContext;
        const source = audioContext.createMediaStreamSource(stream);
        const analyser = audioContext.createAnalyser();
        analyser.fftSize = 1024;
        source.connect(analyser);
        const data = new Uint8Array(analyser.fftSize);
        const monitor = () => {
          if (!realtimeVoiceActiveRef.current) {
            return;
          }

          analyser.getByteTimeDomainData(data);
          let sum = 0;
          for (const value of data) {
            const normalized = (value - 128) / 128;
            sum += normalized * normalized;
          }
          const volume = Math.sqrt(sum / data.length);
          reportAudioLevel(volume);
          const now = Date.now();

          if (isAudioPlaybackActiveRef.current) {
            if (volume > bargeInThreshold) {
              handleUserBargeIn();
            }

            realtimeAnalyserFrameRef.current = window.requestAnimationFrame(monitor);
            return;
          }

          if (volume > speechThreshold) {
            if (!speechDetectedRef.current) {
              onRealtimeUserSpeechStartRef.current?.();
            }
            speechDetectedRef.current = true;
            silenceStartedAtRef.current = null;
            setIsRealtimeListening(true);
          } else if (speechDetectedRef.current) {
            if (silenceStartedAtRef.current === null) {
              silenceStartedAtRef.current = now;
            }
            const recorder = realtimeRecorderRef.current;
            if (!isStoppingUtteranceRef.current && recorder && recorder.state === 'recording' && now - silenceStartedAtRef.current > silenceMs) {
              isStoppingUtteranceRef.current = true;
              recorder.stop();
            }
          }

          realtimeAnalyserFrameRef.current = window.requestAnimationFrame(monitor);
        };

        realtimeAnalyserFrameRef.current = window.requestAnimationFrame(monitor);
      }

      return true;
    } catch (error) {
      console.error('Erro ao iniciar voz via WebSocket', error);
      stopRealtimeVoice();
      return false;
    }
  }, [stopAudioPlayback, stopRealtimeVoice, reportAudioLevel]);

  const startRealtimeVoice = useCallback((
    onFinalTranscript: (payload: RealtimeTranscriptPayload) => void,
    onUserSpeechStart?: () => void
  ) => {
    startWebSocketRealtimeVoice(onFinalTranscript, onUserSpeechStart).then(started => {
      if (started) return;
      setRealtimeTranscript('Voz local indisponível. Verifique o backend e o WebSocket /ws/voice.');
    });
  }, [startWebSocketRealtimeVoice]);

  return {
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
  };
}
