# ElevenLabs React Components — Not Usable in Kotlin-Native Compose

## The npm Packages

Two ElevenLabs packages exist on npm:

- **`@elevenlabs/react`** (v1.12.0) — Provides React hooks and components for ElevenLabs conversation AI and text-to-speech. It does **not** provide a local music-player widget.
- **`@elevenlabs/react-native`** (v1.2.18) — React Native wrapper for ElevenLabs conversation/transcription SDK. Again, **not** a music-player component.

These packages are:
1. **React/React Native only** — cannot be imported or used in a Kotlin-native Android app.
2. **Focused on conversation AI** — they expose `useConversation`, `useVoice`, and `useAudioStream` hooks for real-time AI voice interactions, not offline music playback UI.
3. **Not available as Android AARs** — they are JS packages with native bridge modules, not Compose-compatible widgets.

## MVP Approach

The MVP implements an **ElevenLabs-inspired** dark audio player surface entirely in **Jetpack Compose**:

- Dark gradient background with accent color highlights.
- Semi-transparent glassmorphic control bar.
- Circular play/pause button with animated progress ring.
- Waveform-style progress indicator.
- Track info card with art, title, and creator.

All of this is hand-written Compose code in `ElevenLabsStylePlayer.kt`. No `@elevenlabs/*` npm dependency is or can be used.

## Future Possibilities

If ElevenLabs ever publishes:
- A standalone **Android native SDK** (AAR) with Compose theming.
- A pure **media player UI component** (not conversation-only).

…then this app could potentially adopt it. As of mid-2026, no such package exists.

## Design References

- **ElevenLabs Web App** (elevenlabs.io) — dark theme, rounded glass panels, purple/indigo accents, waveform visualisation.
- **Material 3 Dark Theme** — the Compose `MaterialTheme` dark colour scheme with custom palette.
