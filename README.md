<p align="center">
  <img src="assets/logo/logo_primary.png" width="160" height="160" alt="AI Notifications Logo" style="border-radius: 28px;" />
</p>

<h1 align="center">AI Notifications & Dictation Companion</h1>

<p align="center">
  <em>An autonomous notification AI agent harness with long-term memory, MCP tool calling, companion chat, and private local voice dictation.</em>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%20%7C%20Python-blue?style=flat-square" alt="Platform" />
  <img src="https://img.shields.io/badge/AI_Engine-Local%20%2F%20Cloud%20(OpenAI%20Compatible)-indigo?style=flat-square" alt="AI Engine" />
  <img src="https://img.shields.io/badge/Network-Tailscale%20Mesh-teal?style=flat-square" alt="Network" />
  <img src="https://img.shields.io/badge/Voice-Private%20Dictation-emerald?style=flat-square" alt="Voice" />
</p>

---

## 🎨 Identity & Logo Concept

The **AI Notifications & Dictation** logo captures the unified workflow between voice and notifications:
1. **The Voice Microphone**: Direct acoustic input for private voice dictation and hands-free control.
2. **The Audio Waveform Bridge**: An intelligent soundwave pulse symbolizing real-time AI transcription streaming directly to your local PC over Tailscale.
3. **The Notification Bell**: The alert and smart action harness that listens to incoming messages, schedules alarms, triggers urgent alerts, and logs briefings.

A refined palette of deep charcoal obsidian (`#1F2228`), slate steel blue (`#485D75`), soft sage teal (`#7C998B`), and chalk white was chosen for modern aesthetic clarity—avoiding aggressive neon styling in favor of timeless minimalism.

---

## 🚀 Key Capabilities

- **⚡ Real-Time Notification Processing**: Reads and filters incoming phone notifications via `AiNotificationListenerService`.
- **🧠 Persistent Long-Term Memory**: Injects facts from `memory.md` into prompts across notification triggers and companion conversations.
- **🎙️ Private Voice Dictation**: Speech-to-text streamed securely over Tailscale to your PC running local transcription models, auto-pasted directly into text fields.
- **🛠️ MCP Server & Native Tools**: Supports automated Android alarms, urgent alert vibrations, smart briefings, and remote MCP tool calling.
- **💬 Companion Chat**: Interactive conversational interface to converse with the agent, test scenarios, or manually update memories.

---

## 📂 Assets & Resources

- `assets/logo/logo.png` (or `logo_primary.png`) - Official high-resolution squircle app icon
- `android-app/app/src/main/res/mipmap-*/` - Native Android launcher icons (adaptive & round)
- `android-app/app/src/main/res/drawable/ic_app_logo.png` - In-app branded drawable

---

## 📱 Getting Started with the Android App

See [`android-app/README.md`](android-app/README.md) for full setup instructions, Tailscale configuration, and developer mode guidance.
