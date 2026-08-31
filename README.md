# AG

AG is a privacy-focused Telegram client designed for secure communication, flexible controls, and convenient everyday use.

The public Google Play distribution keeps a separate technical Android application ID so it can be installed and maintained independently from private AG build.

## Core goals

- privacy-first communication controls;
- optional AG message encryption, including per-chat keys;
- practical chat, translation, media, interface, and accessibility tools;
- clear local controls without requiring a separate server;
- a public distribution without the private startup allow-list used by internal builds.

## AG encryption

Encryption can be enabled separately for supported chats. A chat may use the AG system key or a separately configured 256-bit key. Custom chat keys are wrapped locally through Android Keystore and are not stored as plaintext preferences.

The protected system-key contact always uses the built-in system key provider and cannot be assigned a custom chat key.

## Terms of Service

In this project, **ToS** means **Terms of Service**. It is a legal publication document for the Google Play version and is not an alternative application name or brand. The public application remains AG.

## Build

1. Clone with submodules (`--recursive`), or run `git submodule update --init` in an existing clone. The native libraries (`dav1d`, `ffmpeg`, `libvpx`) under `TMessagesProj/jni/third_party/` are required for native builds.
2. Obtain `TELEGRAM_APP_ID` and `TELEGRAM_APP_HASH` from the Telegram developer portal.
3. Put the credentials in `local.properties`.
4. Provide your own signing keystore and Google services configuration when building a production release.
5. Build the release variant:

```bash
./gradlew TMessagesProj:assembleRelease
```

## Open-source credits

AG is based on Telegram for Android and includes code or ideas adapted from several open-source Telegram clients and projects.

Special thanks to the contributors of:

- Cherrygram;
- exteraGram;
- AyuGram;
- OctoGram.

Their names remain in this credits section, applicable licenses, and preserved source history. They are not presented as the product identity of AG.

## License

Review the repository license files and retained upstream notices before redistributing modified builds. Source attribution and license notices must remain intact.
