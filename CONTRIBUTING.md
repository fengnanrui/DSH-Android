# Contributing

Issues and pull requests are welcome.

Before submitting a change:

1. Do not include API keys, credentials, session exports, user workspaces, or device identifiers.
2. Keep Android 9 / API 28 runtime compatibility.
3. Preserve same-origin permission checks and never bypass TLS errors.
4. Run `./gradlew testDebugUnitTest lintDebug assembleDebug`.
5. Update `docs/TESTING.md` when behavior or host capabilities change.

Upstream changes can break the developer-preview Harness Web UI. Verify mobile CSS selectors and connection behavior against a real server whenever the pinned assumptions change.
