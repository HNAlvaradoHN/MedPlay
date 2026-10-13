# SECURITY — SendGram

This repository is public.

## Never commit

- Telegram api_id/api_hash
- Telegram phone numbers, login codes, 2FA passwords or session databases
- bot tokens or third-party API keys
- passwords, secrets, private keys, certificates or signing keystores
- real .env files
- personal/private files, screenshots, logs or PII
- user content or production dumps

## Development rules

- Use placeholders/fake data in examples.
- Keep local secrets outside Git.
- Add secret-bearing/local files to .gitignore before they are created when possible.
- Do not print credentials or private content in CI logs.
- Do not upload TDLib session directories or Android signing material.
- No paid/facturable service may be enabled without explicit owner approval.

## Incident rule

If a secret enters public Git history, treat it as compromised: stop further exposure, notify the owner, revoke/rotate the secret, remove it from current files, inspect artifacts/history, and rewrite public history only with explicit authorization.
