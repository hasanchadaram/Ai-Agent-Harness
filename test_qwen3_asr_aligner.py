import time
import torch
import soundfile as sf
from qwen_asr import Qwen3ASRModel

ASR_MODEL = "Qwen/Qwen3-ASR-0.6B"
ALIGNER_MODEL = "Qwen/Qwen3-ForcedAligner-0.6B"
AUDIO_FILE = r"C:\AI\Ai-Notifications\android-app\app\build\dictation-smoke.wav"

def main():
    print(f"Device: {torch.cuda.get_device_name(0)}")
    print(f"Loading ASR model ({ASR_MODEL}) and Forced Aligner ({ALIGNER_MODEL})...")
    t0 = time.time()
    
    model = Qwen3ASRModel.from_pretrained(
        ASR_MODEL,
        dtype=torch.bfloat16,
        device_map="cuda:0",
        forced_aligner=ALIGNER_MODEL,
        forced_aligner_kwargs=dict(
            dtype=torch.bfloat16,
            device_map="cuda:0",
        ),
        max_inference_batch_size=32,
        max_new_tokens=256,
    )
    t_load = time.time() - t0
    print(f"Models loaded in {t_load:.2f}s")
    
    # Check audio file
    wav, sr = sf.read(AUDIO_FILE)
    duration = len(wav) / sr
    print(f"\nTranscribing audio file: {AUDIO_FILE} (Sample Rate: {sr} Hz, Duration: {duration:.2f}s)")
    
    t_start = time.time()
    results = model.transcribe(
        audio=AUDIO_FILE,
        language="English",
        return_time_stamps=True,
    )
    t_infer = time.time() - t_start
    print(f"Inference completed in {t_infer:.2f}s (Speedup: {duration / t_infer:.1f}x real-time)\n")
    
    for i, r in enumerate(results):
        print("=" * 60)
        print(f"Detected Language: {r.language}")
        print(f"Full Transcript : {r.text}")
        print("=" * 60)
        if r.time_stamps is not None:
            print(f"Word Timestamps ({len(r.time_stamps)} items):")
            for item in r.time_stamps:
                print(f"  [{item.start_time:6.3f}s -> {item.end_time:6.3f}s]  {item.text}")
        else:
            print("No timestamps returned!")

if __name__ == "__main__":
    main()

