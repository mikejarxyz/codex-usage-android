# Security and publication

The app keeps managed authentication in Android private app storage. Display
cache and redacted diagnostics are stored separately. Android backups are disabled;
the widget cache does not contain tokens. The app has no developer-operated server.

Before publishing or updating the source, run:

```text
python scripts/audit-publication.py
```

The check uses Git's candidate/tracked file list and ignore rules. It detects
common credential patterns, private keys, credential assignments, sensitive
filenames, local network addresses, and private host paths. It reports only file
locations and categories, never credential values. It also tests required ignore
rules. This is a publication check, not a penetration test or a guarantee that
every possible secret format is recognizable. It does not audit historical Git
commits or perform a vulnerability review of every native dependency.

Never commit account sessions, auth exports, signing keys/passwords, `.env` files,
local diagnostics, or generated build output. Do not force-add ignored files.
Enable GitHub secret scanning/push protection and private vulnerability reporting
when the public repository is created. Keep release signing material separate
from the repository and back it up securely.

Do not post tokens, auth files, browser callback URLs, pairing codes, or personal
account payloads in issues. Use GitHub's private vulnerability reporting when
available; otherwise contact the maintainer privately before sharing sensitive
details. Public issues can describe the affected component and redacted behavior.

Production release hardening, dependency-advisory review, and broader device
testing remain necessary. The project is currently beta.
