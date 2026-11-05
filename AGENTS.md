# Development

Prefer codebase-memory-mcp for code discovery; index this repository before structural queries.

All source is independently written. Keep reference APKs, decompiled source, local credentials, user books and signing keys outside Git. Preserve the dark reading room design and D-pad accessibility. Changes to reader or network behavior need tests that exercise real edge cases.

For this spudhero-owned repository use repository-local Git identity `spudhero <318930237+spudhero@users.noreply.github.com>`. Verify author and committer before publication.

Build: `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` with JDK 17 and Android SDK 36.
