"""
FastAPI ASR Server for Qwen3-ASR + Forced Aligner.

Features:
- Full OpenAI-compatible chat completion endpoint (/v1/chat/completions)
  compatible with Android DictationClient.kt and Whisper clients.
- Optional chat timestamps via return_time_stamps=true; audio transcription includes available timestamps.
- Streaming window endpoint (/v1/streaming/window) for sliding-window background transcription.
- Model discovery (/v1/models).
"""

import argparse
import base64
import binascii
import io
import re
import threading
import time
from typing import Any, Dict, List, Optional
import numpy as np
import soundfile as sf
import torch
import uvicorn
from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from qwen_asr import Qwen3ASRModel
from starlette.concurrency import run_in_threadpool

from asr_streaming import AlignmentError, attach_punctuation, partition_window
from speech_detection import transcribe_speech, vad_session

app = FastAPI(title="Qwen3-ASR with Forced Aligner API Server")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Global model holders
loaded_models: Dict[str, Qwen3ASRModel] = {}
default_model_name = "Qwen/Qwen3-ASR-1.7B"
ALIGNER_MODEL = "Qwen/Qwen3-ForcedAligner-0.6B"
inference_lock = threading.RLock()


def _get_or_load_model(model_name: Optional[str] = None) -> Qwen3ASRModel:
    global loaded_models, default_model_name
    name = model_name or default_model_name
    
    # Normalize common alias
    if "1.7b" in name.lower() or "1.7B" in name:
        name = "Qwen/Qwen3-ASR-1.7B"
    elif "0.6b" in name.lower() or "0.6B" in name:
        name = "Qwen/Qwen3-ASR-0.6B"

    if name not in loaded_models:
        print(f"[Server] Loading {name} with aligner {ALIGNER_MODEL} on cuda:0...")
        loaded_models[name] = Qwen3ASRModel.from_pretrained(
            name,
            dtype=torch.bfloat16,
            device_map="cuda:0",
            forced_aligner=ALIGNER_MODEL,
            forced_aligner_kwargs=dict(
                dtype=torch.bfloat16,
                device_map="cuda:0",
            ),
            max_inference_batch_size=16,
            max_new_tokens=512,
        )
        print(f"[Server] {name} ready!")
    return loaded_models[name]


def get_or_load_model(model_name: Optional[str] = None) -> Qwen3ASRModel:
    with inference_lock:
        return _get_or_load_model(model_name)


def transcribe_audio(wav, sr, model_name=None, language=None, return_time_stamps=True):
    # Transformer generation and lazy model loading share GPU state. Serialize
    # them while allowing FastAPI's event loop to serve health/model requests.
    def generate(**kwargs):
        with inference_lock:
            return get_or_load_model(model_name).transcribe(**kwargs)
    return transcribe_speech(wav, sr, generate, language, return_time_stamps)


def decode_audio(audio_bytes):
    try:
        wav, sr = sf.read(io.BytesIO(audio_bytes), dtype="float32", always_2d=True)
    except (ValueError, RuntimeError) as exc:
        raise HTTPException(status_code=400, detail="Invalid or unsupported audio") from exc
    if not len(wav) or not np.isfinite(wav).all():
        raise HTTPException(status_code=400, detail="Audio must contain finite samples")
    return wav.mean(axis=1), sr


# Pydantic schemas for OpenAI API compatibility
class AudioContent(BaseModel):
    data: str  # Base64 encoded audio
    format: str = "wav"


class MessageContentItem(BaseModel):
    type: str
    text: Optional[str] = None
    input_audio: Optional[AudioContent] = None


class ChatMessage(BaseModel):
    role: str
    content: Any  # Can be str or List[MessageContentItem] or List[Dict]


class ChatCompletionRequest(BaseModel):
    model: str = "Qwen/Qwen3-ASR-1.7B"
    messages: List[ChatMessage]
    temperature: Optional[float] = 0.0
    max_tokens: Optional[int] = 4096
    stream: Optional[bool] = False
    language: Optional[str] = None
    # Mobile streaming reconciles text and does not consume forced alignment.
    # Alignment failure must not turn a usable transcript into HTTP 500.
    return_time_stamps: bool = False


class SlidingWindowRequest(BaseModel):
    model: Optional[str] = None
    audio_base64: str
    window_start_sec: float = Field(default=0.0, ge=0, allow_inf_nan=False)
    overlap_sec: float = Field(default=1.5, ge=0, allow_inf_nan=False)
    last_committed_time_sec: float = Field(default=0.0, ge=0, allow_inf_nan=False)
    prev_text: Optional[str] = ""
    is_final: bool = False
    language: Optional[str] = "English"


@app.get("/v1/models")
async def list_models():
    return {
        "object": "list",
        "data": [
            {
                "id": "Qwen/Qwen3-ASR-0.6B",
                "object": "model",
                "created": int(time.time()),
                "owned_by": "alibaba-qwen",
            },
            {
                "id": "Qwen/Qwen3-ASR-1.7B",
                "object": "model",
                "created": int(time.time()),
                "owned_by": "alibaba-qwen",
            },
        ],
    }


@app.post("/v1/chat/completions")
async def chat_completions(req: ChatCompletionRequest):
    """
    OpenAI-compatible chat completion endpoint.
    Extracts input_audio and returns transcription formatted as:
    <asr_text>{transcript}<|im_end|>
    plus optional word timestamps when return_time_stamps=true.
    """
    audio_bytes = None

    # Search for audio in messages
    for msg in req.messages:
        if isinstance(msg.content, list):
            for item in msg.content:
                if isinstance(item, dict):
                    if item.get("type") == "input_audio" and "input_audio" in item:
                        audio_bytes = base64.b64decode(item["input_audio"]["data"])
                        break
                elif hasattr(item, "type") and item.type == "input_audio" and item.input_audio:
                    audio_bytes = base64.b64decode(item.input_audio.data)
                    break
        elif isinstance(msg.content, dict) and msg.content.get("type") == "input_audio":
            audio_bytes = base64.b64decode(msg.content["input_audio"]["data"])
            break

    if not audio_bytes:
        raise HTTPException(status_code=400, detail="No input_audio found in messages.")

    wav, sr = decode_audio(audio_bytes)
    t0 = time.time()
    results = await run_in_threadpool(transcribe_audio, wav, sr, req.model,
                                     [req.language] if req.language else None, req.return_time_stamps)
    dt = time.time() - t0

    res = results[0]
    raw_text = res.text.strip()
    formatted_content = f"<asr_text>{raw_text}<|im_end|>"

    words_out = []
    if res.time_stamps:
        for w in res.time_stamps:
            words_out.append({
                "word": w.text,
                "start": round(w.start_time, 3),
                "end": round(w.end_time, 3),
            })
        try:
            words_out = attach_punctuation(raw_text, words_out)
        except AlignmentError:
            words_out = []  # Optional metadata; preserve the complete ASR text.

    return {
        "id": f"chatcmpl-qwen3-{int(time.time() * 1000)}",
        "object": "chat.completion",
        "created": int(time.time()),
        "model": req.model,
        "choices": [
            {
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": formatted_content,
                },
                "finish_reason": "stop",
            }
        ],
        "usage": {
            "prompt_tokens": int(len(wav) / sr * 50),
            "completion_tokens": len(words_out),
            "total_tokens": int(len(wav) / sr * 50) + len(words_out),
        },
        "timestamps": words_out,
        "latency_sec": round(dt, 3),
    }


@app.post("/v1/audio/transcriptions")
async def audio_transcriptions(
    file: UploadFile = File(...),
    model: str = Form("Qwen/Qwen3-ASR-0.6B"),
    language: Optional[str] = Form(None),
    response_format: Optional[str] = Form("json"),
    timestamp_granularities: Optional[List[str]] = Form(None),
):
    """
    Standard OpenAI Audio Transcriptions endpoint.
    """
    data = await file.read()
    wav, sr = decode_audio(data)
    results = await run_in_threadpool(transcribe_audio, wav, sr, model,
                                      [language] if language else None)
    res = results[0]

    words = []
    if res.time_stamps:
        for w in res.time_stamps:
            words.append({
                "word": w.text,
                "start": round(w.start_time, 3),
                "end": round(w.end_time, 3),
            })
        try:
            words = attach_punctuation(res.text, words)
        except AlignmentError:
            words = []

    if response_format in ["verbose_json", "json"]:
        return {
            "text": res.text.strip(),
            "language": res.language,
            "duration": round(len(wav) / sr, 3),
            "words": words,
        }
    return {"text": res.text.strip()}


@app.post("/v1/streaming/window")
async def sliding_window_endpoint(req: SlidingWindowRequest):
    """
    Optimized endpoint for sliding window background transcription.
    Takes current window audio and returns:
    - committed_words (safe to finalize)
    - preview_words (tentative tail)
    - next_recommended_start_sec (for window slide anchoring)
    """
    try:
        audio_bytes = base64.b64decode(req.audio_base64, validate=True)
    except (ValueError, binascii.Error) as exc:
        raise HTTPException(status_code=400, detail="Invalid base64 audio") from exc
    wav, sr = decode_audio(audio_bytes)
    duration = len(wav) / sr
    if duration > 30:
        raise HTTPException(status_code=400, detail="Streaming windows must be at most 30 seconds")
    if not req.is_final and req.overlap_sec >= duration:
        raise HTTPException(status_code=400, detail="Overlap must be shorter than a nonfinal window")
    if req.last_committed_time_sec > req.window_start_sec + duration + .02:
        raise HTTPException(status_code=400, detail="Commit time is beyond the window")
    # Exact silence needs no generation. This intentionally avoids an amplitude
    # threshold that could reject quiet but valid speech.
    if not np.any(wav):
        return partition_window("", [], req.window_start_sec, duration,
                                req.overlap_sec, req.last_committed_time_sec, req.is_final)
    # Pad very short releases for the acoustic encoder without moving real times.
    model_audio = np.pad(wav, (0, max(0, int(.3 * sr) - len(wav))))
    results = await run_in_threadpool(transcribe_audio, model_audio, sr, req.model,
                                      [req.language] if req.language else None)
    res = results[0]
    items = [{"word": item.text, "start": item.start_time, "end": item.end_time}
             for item in (res.time_stamps or [])]
    try:
        return partition_window(res.text, items, req.window_start_sec, duration,
                                req.overlap_sec, req.last_committed_time_sec, req.is_final)
    except AlignmentError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc



def main():
    parser = argparse.ArgumentParser(description="Qwen3-ASR Server")
    parser.add_argument("--host", default="0.0.0.0", help="Host address (0.0.0.0 for Tailscale / LAN)")
    parser.add_argument("--port", type=int, default=8000, help="Port to listen on (default: 8000)")
    parser.add_argument("--model", default="Qwen/Qwen3-ASR-0.6B", help="Preload model name")
    args = parser.parse_args()

    global default_model_name
    default_model_name = args.model

    print(f"Starting Qwen3-ASR server on {args.host}:{args.port}...")
    print(f"Preloading model: {args.model}...")
    vad_session()
    get_or_load_model(args.model)

    uvicorn.run(app, host=args.host, port=args.port)


if __name__ == "__main__":
    main()

