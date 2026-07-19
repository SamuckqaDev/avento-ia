---
name: voice-pipeline-maintenance
description: Diagnose, implement, or improve Avento local voice input and output. Use for microphone capture, Whisper.cpp transcription, language detection, Piper synthesis, voice selection, pronunciation, sentence chunking, playback queues, interruption, native libraries, and natural speech behavior.
---

# Voice Pipeline Maintenance

1. Trace audio capture, upload format, FFmpeg conversion, Whisper invocation, transcript language, TTS preparation, Piper output, and browser playback.
2. Verify binaries, models, executable permissions, architecture, dynamic library paths, sample rate, and temporary-file cleanup.
3. Preserve detected language unless the user explicitly requests another response language; select a matching voice instead of reading foreign text with the wrong phonemes.
4. Remove Markdown syntax, code blocks, URLs, emoji descriptions, and non-speech metadata before synthesis without altering meaning.
5. Chunk at natural sentence boundaries, stream audio progressively when supported, and maintain one cancellable playback queue per chat.
6. Stop immediately when the user presses stop, starts recording, changes chat, logs out, or begins a newer response.
7. Keep voice tuning configurable by language and voice; do not fake naturalness with random speed changes.
8. Test Portuguese and English transcription, pronunciation, interruption, repeated play, and error recovery.

Logs may include timings and file identifiers, never raw private audio or authentication data.
