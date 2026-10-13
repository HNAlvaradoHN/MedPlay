# ARCHITECTURE — SendGram MVP

Status: VALIDATED_FOR_PROTOTYPE
Protocol version: 1

## Goal

Validate the smallest useful product flow:

1. User installs SendGram.
2. User enters their own Telegram `api_id` and `api_hash`.
3. User authenticates their own Telegram account.
4. SendGram exposes Telegram destinations inside Android's Storage Access Framework.
5. From a browser or another app that uses Android's save/create-document flow, the user chooses SendGram as the storage provider.
6. The client app writes the file through SendGram's `DocumentsProvider`.
7. After the write finishes, SendGram uploads that file to the selected Telegram destination.

The MVP is complete only when this flow is proven on a real Android device.

## Chosen stack

- Android native.
- Kotlin.
- Android Storage Access Framework via a custom `DocumentsProvider`.
- Telegram TDLib for user-account authentication and message/file sending.
- Local app-private temporary storage for files being written before upload.
- No backend/server for the MVP.

## Why DocumentsProvider

Android officially supports custom document providers that appear inside the system document picker.

A provider that supports file creation must:
- expose a root with `FLAG_SUPPORTS_CREATE`;
- expose directories with `FLAG_DIR_SUPPORTS_CREATE`;
- implement `createDocument()`;
- implement `openDocument()`.

The client app then writes to the returned `ParcelFileDescriptor`.

Android's own documentation explicitly shows attaching an `OnCloseListener` for write mode and using that close event as the point to update a cloud server. That maps directly to SendGram's required behavior: client writes locally, then SendGram uploads after the descriptor closes.

## Write pipeline

MVP write path:

`ACTION_CREATE_DOCUMENT -> SendGram root/destination -> createDocument() -> temporary local file -> openDocument("w") -> client writes -> descriptor closes -> enqueue Telegram upload -> success/failure state -> cleanup temporary file`

Important: the file is not considered uploaded merely because `createDocument()` succeeded. The upload starts only after the client has finished writing.

## Telegram integration

Use TDLib because SendGram must act as the authenticated Telegram user, not as a bot.

TDLib handles:
- authorization state;
- networking;
- session/local database;
- chat access;
- sending files/messages.

Required authorization states may include:
- phone number;
- login code;
- email/email code when required;
- 2FA password;
- final `authorizationStateReady`.

For file sending, TDLib supports `sendMessage` with file-oriented input message content and `inputFileLocal`.

## Credentials model

Each user supplies their own:
- `api_id`;
- `api_hash`.

SendGram does not ship the developer's Telegram API credentials.

Rules:
- never commit real credentials;
- never upload credentials to a SendGram server;
- no SendGram backend is required for MVP;
- keep TDLib session data in app-private storage;
- credential/session storage design must use Android-protected local storage where practical;
- logs must never include credentials, phone numbers, login codes, 2FA passwords, invite links, message contents, or local TDLib session data.

## DocumentsProvider model

Initial virtual hierarchy:

```
SendGram
├── Saved Messages
├── Favorite destination 1
├── Favorite destination 2
└── Favorite destination 3
```

For MVP, only configured/favorite writable destinations need to appear. Do not expose the entire Telegram chat list through the provider until the core path is stable.

A root/destination must only advertise create capability when:
- Telegram session is authorized;
- destination exists;
- current user can send there.

## Temporary files

MVP uses an app-private temporary file because the external client writes through a file descriptor and TDLib can upload a local file after that write completes.

Lifecycle:
1. create pending record;
2. create temporary file in app-private storage;
3. return writable file descriptor;
4. detect close;
5. enqueue upload;
6. wait for Telegram result;
7. mark sent or failed;
8. delete temp file after confirmed success, or retain only as long as needed for retry;
9. clean stale failed/cancelled files automatically.

No user file content may be committed, logged, or copied to public storage by default.

## Background work

The MVP should not assume an upload completes before the client app returns.

A foreground/background-safe Android job mechanism will be selected during implementation based on actual upload constraints. The first prototype may keep upload handling minimal, but must not falsely report success before Telegram accepts/completes the send operation.

## Scope of first functional prototype

Include:
- app launch;
- API ID/API Hash entry;
- Telegram authorization;
- one destination: Saved Messages;
- DocumentsProvider root visible in Android picker;
- create/write one arbitrary document;
- upload after close;
- visible upload result/error;
- temporary-file cleanup.

Exclude initially:
- full Telegram chat browser;
- multiple accounts;
- folders inside Telegram;
- syncing remote message history as a filesystem;
- file rename/move/delete against Telegram;
- automatic download/readback of every Telegram file;
- media gallery features;
- cloud server;
- analytics;
- paid services.

## Main technical risks

1. Some browsers/apps may use Android save flows differently; real-device compatibility must be tested.
2. Large files can stress local temporary storage and Android background execution.
3. Telegram destination permissions can change.
4. TDLib Android packaging/native binaries may affect APK size and build complexity.
5. "Saved" in the Android picker and "uploaded to Telegram" are two distinct states; UX must not confuse them.
6. User-supplied Telegram API credentials reduce developer credential exposure, but users still need clear setup guidance.

## Definition of prototype success

The architecture is considered physically validated only when, on a real Android device:

1. SendGram appears in the Android document picker as a writable location.
2. A browser/app can save a file into SendGram.
3. The file is received completely.
4. SendGram detects completion of the write.
5. TDLib uploads it to Saved Messages.
6. The uploaded Telegram file is verified.
7. Temporary local data is cleaned safely.
8. No secret/private data appears in Git or logs.

Until then the status remains IMPLEMENTED_PENDIENTE_VALIDACIÓN when code exists.

## Primary sources checked

- Android custom DocumentsProvider:
  https://developer.android.com/guide/topics/providers/create-document-provider
- Android common intents / provider integration:
  https://developer.android.com/guide/components/intents-common
- Telegram TDLib:
  https://core.telegram.org/tdlib
- TDLib getting started:
  https://core.telegram.org/tdlib/getting-started
- Telegram API ID creation:
  https://core.telegram.org/api/obtaining_api_id
