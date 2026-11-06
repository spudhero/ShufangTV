# Development

Prefer codebase-memory-mcp for code discovery; index this repository before structural queries.

Application source is independently written; third_party/libmobi retains its upstream source and LGPL notices. Keep reference APKs, decompiled source, local credentials, user books and signing keys outside Git. Preserve the dark reading room design and D-pad accessibility. Changes to reader or network behavior need tests that exercise real edge cases.

For this spudhero-owned repository use repository-local Git identity `spudhero <318930237+spudhero@users.noreply.github.com>`. Verify author and committer before publication.

Build: `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` with JDK 17, Android SDK 36, NDK 27.2.12479018 and CMake 3.22.1. Native Kindle parsing and PDFium viewport rendering also need the ReaderDeviceTest instrumentation suite on an Android target; Robolectric does not replace those checks. Preserve the matching development signing key when updating an existing TV installation.

Independent speech uses pinned sherpa-onnx and a separately downloaded Chinese model; retain Apache/MIT attribution. Speech changes also need SpeechDeviceTest on a real target with the voice pack installed. Verify actual playback-head progress and cancelled inference ownership, not only synthesis callbacks.
