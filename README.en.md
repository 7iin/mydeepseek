# myDeepSeek

> A self-hosted-flavoured Android client for DeepSeek. Native Jetpack Compose, ~4 MB release APK after R8.

**[中文](README.md) | English**

![Version](https://img.shields.io/badge/version-1.2.0-blue)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

## What this is

myDeepSeek is a personal fork of [SimpleChat](https://github.com/Pokanshuu/Simple-Chat).
The foundation is unchanged — light, fast, offline-first, no account system — with three
targeted changes and a few real bug fixes (see [CODE_REVIEW.md](CODE_REVIEW.md)):

| Change | Detail |
|---|---|
| **Images above the bubble** | Sent images render as fixed 104dp square previews **above** the message bubble (up to three per row, `Crop`), tap to view full screen, long-press for the message menu. Image-only messages no longer draw an empty bubble. |
| **History grouped by month** | The drawer goes from Today / Yesterday / Past 7 days / Earlier to **Pinned + one group per month** (`November 2026`). The old buckets ended up stuffing ~90% of a long history into "Earlier". |
| **WebDAV sync** | Settings → Cloud sync. Reads and writes one full backup on your own drive (Nutstore, Nextcloud, Synology…), merging per conversation by `updatedAt` (last-write-wins). Optional sync on launch, manual sync, connection test. Password is stored as Keystore-encrypted ciphertext. |
| **Fix: sent images were never written to disk** | Picked photos were inlined as base64 into `messages.attachments`; one 3 MB photo made a 4 MB row, past Android's 2 MB `CursorWindow` limit, so that conversation became unreadable and the image was silently dropped by the fallback path. Now the send path offloads like the import path always did. |
| **Fix: deleting a message leaked image files** | Files exclusively owned by deleted messages (and their refs) are now reclaimed. |
| **Fix: Keystore decryption on the main thread** | The settings flow now reads on IO. |
| **Fix: wrong pager start page for stored images** | Stored attachments carry an empty `data`, so two same-named images matched each other; `path` is now part of the comparison. |

## Features kept from upstream

- Any OpenAI-compatible endpoint; the official DeepSeek endpoint can use the **Anthropic-compatible** channel (web search, link reading, inline citations, server-side titles).
- SSE streaming with live Markdown, collapsible reasoning, "continue generation".
- Native math rendering and offline Mermaid diagrams.
- Message **tree** with branches and multiple variants; per-conversation fork.
- CJK-weighted token estimate, context compaction, attachments (camera / gallery / file, no extra permissions).
- JSON backup, ZIP archive with per-chat Markdown, Markdown export; imports from Chatbox and DeepSeek exports.
- Dark mode, accent colours, font scale, Chinese/English UI.

## Build

Requirements: JDK 17, Android SDK platform 37 / build-tools 37.0.0.

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest
./gradlew assembleRelease    # needs signing config, see below
```

## GitHub Actions

[`.github/workflows/android.yml`](.github/workflows/android.yml) runs on push to `main`, pull
requests, `v*` tags and manual dispatch: JDK 17 + Android SDK → restore signing key → unit tests →
`assembleRelease` → upload the APK. Pushing a tag creates a GitHub Release with the APK attached.

## Release signing

Android only installs an update when package name **and** signing key match. Using a debug key for
your first public build means users must uninstall (and lose all data) when you switch. Decide the
key once and back it up.

```bash
keytool -genkeypair -v -keystore mydeepseek-release.jks \
  -alias mydeepseek -keyalg RSA -keysize 4096 -validity 10000
```

Base64-encode it (`base64 -i … | tr -d '\n'` on macOS, `[Convert]::ToBase64String([IO.File]::ReadAllBytes("…"))`
in PowerShell). Keep the `.jks` outside the repo and back it up; the generated `.b64` is the same key in
text form, so never commit it and delete it locally once it is in the secret. Then add four repository secrets:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | full base64 of the `.jks`, single line |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `mydeepseek` |
| `KEY_PASSWORD` | key password |

Without those secrets the workflow still succeeds and produces a debug-signed release APK (with a
warning), so forks build out of the box. For local release builds, create `keystore.properties`
(git-ignored):

```properties
storeFile=keystore/mydeepseek-release.jks
storePassword=…
keyAlias=mydeepseek
keyPassword=…
```

Verify with `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`.

## Cloud sync (WebDAV)

Settings → Cloud sync. Four fields: server URL, account, app password, remote file
(default `myDeepSeek/backup.json`; missing parent folders are created).

- **Nutstore**: server `https://dav.jianguoyun.com/dav/`, account = your email, password = a
  *third-party app password* (never your login password).
- **Nextcloud**: `https://<host>/remote.php/dav/files/<user>/`
- Only `PUT` / `GET` / `MKCOL` / `PROPFIND` with Basic auth are used.
- **HTTPS only**: cleartext HTTP is blocked since Android 9 and `usesCleartextTraffic` is not
  enabled. For a LAN server (Synology, Raspberry Pi) put a TLS certificate or a reverse proxy in front.

How it works: download the remote JSON (absent = empty) → take the newer side **per conversation
and per preset** → upload the merged full snapshot.

Known limits:

- **Deletions do not propagate.** A conversation deleted on device A comes back from device B's
  next sync. Fixing it needs tombstones; not worth it for personal use.
- **Whole-file transfer.** Every sync rewrites the entire backup, images included (base64 adds ~33%).
- **The remote file is plaintext JSON.** It contains your chat text and images — use a drive you trust,
  or leave auto-sync off.

## Privacy

API keys and the WebDAV password are AES-256-GCM encrypted with keys inside the Android Keystore and
never leave the device; requests go straight to the endpoint you configure; sync happens only between
your device and your drive. Schema changes use explicit Room migrations — no destructive fallbacks.

## License

MIT. Forked from [Pokanshuu/Simple-Chat](https://github.com/Pokanshuu/Simple-Chat); this is a
personal branch, not an official release of the upstream project.
