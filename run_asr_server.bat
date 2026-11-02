@echo off
setlocal
echo Starting Qwen3-ASR (1.7B) + Forced Aligner Server...
echo Model: Qwen/Qwen3-ASR-1.7B (High accuracy)
echo Port: 8000
echo.

"C:\AI\Audio\Qwen3-ASR\.venv\Scripts\python.exe" -u "c:\AI\Ai-Notifications\asr_server.py" --host 0.0.0.0 --port 8000 --model Qwen/Qwen3-ASR-1.7B
pause

