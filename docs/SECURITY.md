# SECURITY — MedPlay ⏯️

This repository is public.

## Never commit

- API keys, tokens, passwords or private keys;
- Android signing keystores/certificates;
- real .env files;
- personal/private files, screenshots, logs or PII;
- user recordings or captured screen/audio;
- private app packages;
- copyrighted third-party decompiled source or proprietary assets.

## Reverse-engineering / reference-analysis rules

- Use REA/JADX to study behavior, APIs, architecture and interoperability where lawful.
- Do not publish proprietary decompiled source from Medal or other third-party apps.
- Do not copy protected assets, branding or code into MedPlay.
- Keep third-party APKs out of the public repository unless the owner has the right to redistribute them.
- Prefer workflow inputs/artifacts with limited retention rather than committed APK binaries.

## Recording privacy

MedPlay may capture sensitive screen/audio content.

Design requirements:
- recording must require explicit Android MediaProjection consent;
- show clear active-capture state;
- keep captured data local by default;
- do not upload recordings without explicit user action;
- avoid logging filenames/content that may reveal private information;
- provide predictable deletion/cleanup behavior.

## Cost rule

No paid/facturable service may be enabled without explicit owner approval.


## Player microphone privacy

- Player microphone capture is opt-in and defaults OFF.
- RECORD_AUDIO must be granted before the microphone foreground capture path is started.
- Microphone samples remain local and follow the same bounded rolling-retention model as other captured media.
- MedPlay does not upload microphone audio.
- If Android silences MedPlay because another app has microphone priority, MedPlay records silence for that interval and surfaces the state to the user rather than attempting to bypass Android capture policy.
