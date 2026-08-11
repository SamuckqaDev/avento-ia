#!/usr/bin/env python3
"""Servidor HTTP local, mínimo e privado para a voz neural do Avento."""

from __future__ import annotations

import io
import json
import os
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np
import soundfile as sf
from kokoro import KPipeline


class KokoroService:
    def __init__(self) -> None:
        self.pipeline = None

    def speak(self, text: str, voice: str) -> bytes:
        if self.pipeline is None:
            # Kokoro baixa o modelo aberto apenas na primeira síntese e depois o reutiliza em memória.
            self.pipeline = KPipeline(lang_code="p")
        chunks = list(self.pipeline(text, voice=voice))
        audio = np.concatenate([chunk.audio for chunk in chunks])
        output = io.BytesIO()
        sf.write(output, audio, 24000, format="WAV")
        return output.getvalue()


SERVICE = KokoroService()


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        if self.path != "/health":
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"status":"ok","engine":"kokoro"}')

    def do_POST(self) -> None:
        if self.path != "/tts":
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            payload = json.loads(self.rfile.read(length))
            text = str(payload.get("text", "")).strip()
            voice = str(payload.get("voice", "pf_dora")).strip()
            if not text:
                self.send_error(HTTPStatus.BAD_REQUEST, "text is required")
                return
            audio = SERVICE.speak(text, voice)
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "audio/wav")
            self.send_header("Content-Length", str(len(audio)))
            self.end_headers()
            self.wfile.write(audio)
        except Exception as error:  # Não expõe texto nem detalhes internos pela rede.
            self.log_error("Kokoro synthesis failed: %s", type(error).__name__)
            self.send_error(HTTPStatus.SERVICE_UNAVAILABLE, "neural speech is unavailable")

    def log_message(self, format: str, *args: object) -> None:
        print("Kokoro TTS: " + format % args, flush=True)


if __name__ == "__main__":
    host = os.environ.get("AVENTO_KOKORO_HOST", "127.0.0.1")
    port = int(os.environ.get("AVENTO_KOKORO_PORT", "8880"))
    print(f"Kokoro TTS listening at http://{host}:{port}", flush=True)
    ThreadingHTTPServer((host, port), Handler).serve_forever()
