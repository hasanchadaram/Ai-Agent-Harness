import base64
import requests

SERVER_URL = "http://127.0.0.1:8000"
AUDIO_FILE = r"C:\AI\Ai-Notifications\android-app\app\build\dictation-smoke.wav"

def test_models():
    print("Testing GET /v1/models...")
    r = requests.get(f"{SERVER_URL}/v1/models")
    print(r.status_code, r.json())

def test_chat_completions():
    print("\nTesting POST /v1/chat/completions (Android app format)...")
    with open(AUDIO_FILE, "rb") as f:
        audio_b64 = base64.b64encode(f.read()).decode()

    payload = {
        "model": "Qwen/Qwen3-ASR-0.6B",
        "messages": [
            {
                "role": "user",
                "content": [
                    {
                        "type": "input_audio",
                        "input_audio": {
                            "data": audio_b64,
                            "format": "wav",
                        },
                    }
                ],
            }
        ],
        "temperature": 0,
        "max_tokens": 4096,
        "stream": False,
    }

    r = requests.post(f"{SERVER_URL}/v1/chat/completions", json=payload)
    print("Status:", r.status_code)
    data = r.json()
    content = data["choices"][0]["message"]["content"]
    print("Response Content:", content)
    # Extract as Android app does:
    extracted = content.split("<asr_text>", 1)[-1].split("<|im_end|>", 1)[0].strip()
    print("Extracted Transcript:", extracted)
    print("Word Timestamps returned:", len(data.get("timestamps", [])))
    if data.get("timestamps"):
        print("First 3 words:", data["timestamps"][:3])

def test_streaming_window():
    print("\nTesting POST /v1/streaming/window...")
    with open(AUDIO_FILE, "rb") as f:
        audio_b64 = base64.b64encode(f.read()).decode()

    payload = {
        "model": "Qwen/Qwen3-ASR-0.6B",
        "audio_base64": audio_b64,
        "window_start_sec": 0.0,
        "overlap_sec": 1.5,
        "last_committed_time_sec": 0.0,
        "is_final": False,
        "language": "English",
    }
    r = requests.post(f"{SERVER_URL}/v1/streaming/window", json=payload)
    print("Status:", r.status_code)
    data = r.json()
    print("Committed words:", len(data["committed_words"]))
    print("Preview words:", len(data["preview_words"]))
    print("Next start time (s):", data["next_start_sec"])

if __name__ == "__main__":
    test_models()
    test_chat_completions()
    test_streaming_window()

