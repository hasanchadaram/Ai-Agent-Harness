# AI Notifications Android App

A native Android agentic companion that captures phone notifications, processes them using your local AI model over Tailscale, queries long-term memory, and takes native actions (alarms, smart briefings, urgent vibration/alerts).

---

## Quick Setup Guide

### 1. Enable USB Debugging on your Android Phone
You do **not** need an emulator. Testing on your physical phone is better because it has real notifications and Tailscale:
1. Go to **Settings > About Phone**.
2. Tap **Build Number** 7 times until you see *"You are now a developer!"*.
3. Go back to **Settings > System > Developer Options**.
4. Enable **USB Debugging**.
5. Connect your phone to your PC with a USB cable.
6. A popup will appear on your phone: tap **"Always allow from this computer"** -> **Allow**.

---

### 2. Open Project in Android Studio
1. Open Android Studio.
2. Select **Open** (or `File > Open...`).
3. Browse to and select: `c:\AI\Ai-Notifications\android-app`.
4. Wait for Gradle Sync to complete.
5. In the top toolbar, you will see your physical phone selected in the device dropdown.
6. Click the **Run** button (green play triangle `▶` or `Shift + F10`).

---

### 3. App First-Run Checklist
1. **Grant Notification Access**:
   - The app will display a red banner: *"Notification Access Required"*.
   - Tap **"Grant Access in Settings"** and toggle on **AI Notification Processor**.
2. **Configure Tailscale Endpoint**:
   - Tap the **Settings** icon (top right).
   - Enter your PC's Tailscale IP or domain:
     `http://<your-tailscale-pc-ip>:8080/v1`
   - Model name: `qwen3.5:4b`
   - API Key: `llama`
   - Tap **Save**.
3. **Test Connection**:
   - Tap **"Test Connection"** on the dashboard. It will ping your model at `/models` and confirm connectivity.
4. **Test the Agent Loop**:
   - Tap the **Meeting Alarm** chip -> Watch the agent analyze the meeting and schedule an alarm.
   - Tap the **Urgent Alert** chip -> Watch the agent trigger high-priority vibration and a heads-up alert.
   - Tap the **Memory & Brief** chip -> Tap the **Brain** icon (top right) to see the new fact saved into `memory.md`!
