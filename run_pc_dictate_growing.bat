@echo off
"C:\AI\Audio\Qwen3-ASR\.venv\Scripts\python.exe" -u "%~dp0pc_dictate_v2.py" --streaming-strategy growing %*
