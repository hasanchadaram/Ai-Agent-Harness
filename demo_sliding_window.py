"""
Demonstration of sliding-window background transcription with word-level timestamps.
Simulates real-time streaming audio from microphone/app.
"""

import time
from sliding_window_transcriber import SlidingWindowTranscriber

AUDIO_FILE = r"C:\AI\Ai-Notifications\android-app\app\build\dictation-smoke.wav"

def main():
    print("=" * 70)
    print("SLIDING WINDOW BACKGROUND TRANSCRIPTION DEMO (Qwen3-ASR + Aligner)")
    print("=" * 70)

    transcriber = SlidingWindowTranscriber(
        asr_model_name="Qwen/Qwen3-ASR-1.7B",
        aligner_model_name="Qwen/Qwen3-ForcedAligner-0.6B",
        window_size_sec=5.0,  # 5 second window
        overlap_sec=1.5,      # 1.5 second overlap
        language="English",
    )

    print(f"\nSimulating real-time streaming of: {AUDIO_FILE}")
    print("-" * 70)

    total_start = time.time()
    for update in transcriber.process_file_stream(AUDIO_FILE, chunk_sec=0.5):
        committed_str = " ".join(w.text for w in update.committed_words)
        preview_str = " ".join(w.text for w in update.preview_words)
        
        status = "[FINAL]" if update.is_final else f"[WINDOW #{update.window_index}]"
        print(f"\n{status} Range: {update.window_start:.2f}s -> {update.window_end:.2f}s")
        print(f"  * Committed ({len(update.committed_words)} words): {committed_str}")
        if preview_str:
            print(f"  * Preview   ({len(update.preview_words)} words): {preview_str}")
        print(f"  * Assembled Total: \"{update.total_text}\"")

    print("-" * 70)
    print(f"Completed in {time.time() - total_start:.2f}s")
    print(f"\nFINAL VERIFIED WORD TIMESTAMPS:")
    for w in transcriber.committed_words:
        print(f"  [{w.start_time:6.3f}s -> {w.end_time:6.3f}s]  {w.text}")

if __name__ == "__main__":
    main()

