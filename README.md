# SwarmBuilder

An Android app that turns a **text prompt** into a fully compiled Android APK and pushes the project to GitHub — powered entirely by **free AI and LLMs** running in a cooperative swarm.

---

## How it works

```
User prompt
     │
     ▼
┌─────────────────────────────────────────────────────┐
│                    Swarm Pipeline                    │
│                                                     │
│  ① Architect  ──▶  App spec + file list             │
│  ② Coder      ──▶  Kotlin / XML source files        │
│  ③ Reviewer   ──▶  Code review + bug fixes          │
│  ④ Builder    ──▶  ./gradlew assembleDebug           │
│  ⑤ Publisher  ──▶  GitHub repo + Release APK        │
└─────────────────────────────────────────────────────┘
     │
     ▼
  ✅ APK (install directly) + GitHub repo URL
```

Each agent is backed by a **free LLM provider**:

| Provider | Free Tier | Notes |
|---|---|---|
| [Groq](https://console.groq.com) | ✅ Llama 3 70B | Fastest |
| [Hugging Face](https://huggingface.co/settings/tokens) | ✅ Inference API | Many models |
| [OpenRouter](https://openrouter.ai) | ✅ Free models | Mistral 7B etc. |
| [Ollama](https://ollama.com) | ✅ Local | 100% offline |

---

## Getting started

### 1. Clone & open in Android Studio

```bash
git clone https://github.com/<you>/Builder.git
```

Open the project root in **Android Studio Hedgehog (2023.1.1)** or newer.

### 2. Build & run

```
Run ▶ on a physical device or emulator (API 26+)
```

### 3. Configure API keys

Tap the **Settings** (⚙) icon and enter at least one free API key:

- **Groq** — sign up at [console.groq.com](https://console.groq.com) → free key in seconds.
- **Hugging Face** — sign up at [huggingface.co](https://huggingface.co) → Settings → Access Tokens → New token (read).
- **OpenRouter** — sign up at [openrouter.ai](https://openrouter.ai) → free credits included.
- **Ollama** (optional) — install [Ollama](https://ollama.com) on your machine and forward the port to the device.

For **GitHub push**, add a [Personal Access Token](https://github.com/settings/tokens) with `repo` scope and your username.

### 4. Build an app

1. Type a description in the text box, e.g. *"A weather app with 5-day forecast and dark mode"*
2. Tap **🚀 Build My App**
3. Watch the swarm agents collaborate in real-time
4. Tap **Install APK** to sideload, or **Open on GitHub** to view the pushed repo

---

## Project structure

```
app/src/main/java/com/swarmbuilder/app/
├── SwarmBuilderApp.kt          # Application class & settings persistence
├── models/
│   └── Models.kt               # Data classes (AppSpec, SourceFile, BuildResult…)
├── swarm/
│   ├── LlmClient.kt            # HTTP client for Groq / HF / OpenRouter / Ollama
│   └── SwarmOrchestrator.kt    # Multi-agent pipeline (Architect → Coder → Reviewer)
├── codegen/
│   └── ProjectWriter.kt        # Writes generated files to disk
├── build/
│   └── ApkBuilder.kt           # Runs `./gradlew assembleDebug` in a subprocess
├── github/
│   └── GitHubPublisher.kt      # Creates repo, uploads files, creates release + APK
└── ui/
    ├── MainActivity.kt         # Prompt input screen
    ├── BuildActivity.kt        # Live log + result screen
    ├── BuildViewModel.kt       # Orchestrates the pipeline in a coroutine
    ├── SettingsActivity.kt     # API key management
    └── LogAdapter.kt           # RecyclerView adapter for swarm logs
```

---

## Requirements

- Android 8.0 (API 26) or higher
- Internet access for LLM API calls
- For on-device APK compilation: Android SDK tools must be present (via Termux or similar). Most users will rely on a CI backend — the app surfaces all build logs either way.

---

## Security notes

- **API keys** are stored in Android Keystore-backed encrypted preferences and
  device backups are disabled (`allowBackup=false`).
- **Cleartext HTTP is allowed only to loopback** (`localhost`, `127.0.0.1`,
  `10.0.2.2`) for local LLM servers; all cloud traffic is HTTPS. A LAN HTTP
  endpoint requires an HTTPS proxy.
- **Generated projects are code**: the app writes LLM-produced sources to disk
  (traversal-guarded so files can't escape the project dir) and runs
  `./gradlew assembleDebug` on them — build scripts execute with the app's
  privileges by design. Builds are debug-only, time-boxed (30 min per build,
  90 min end-to-end), and the generated APK is signed with the debug key.
  Only run prompts you're comfortable having executed, and review unfamiliar
  `build.gradle` output before re-running.
- **GitHub publishing** uploads only the source tree — never `local.properties`,
  `build/` outputs, `.gradle/` caches or license/SDK paths — and skips files
  above the GitHub Contents API size limit.

---

## License

MIT
