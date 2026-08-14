# Security policy

Report security issues privately to the repository owner through GitHub's private vulnerability reporting when available. Do not include real API keys, credentials, private session content, or proprietary workspace files.

DSH Android's trust boundary is the configured Harness origin:

- invalid TLS certificates are rejected;
- cross-origin web navigation is delegated to the system browser;
- camera, microphone, and location requests are granted only to the configured origin and only after Android permission approval;
- embedded URL credentials are rejected;
- cleartext non-loopback HTTP requires an explicit warning acknowledgement.

API keys and model credentials are managed by Harness on the server. The Android client does not implement its own credential store.
