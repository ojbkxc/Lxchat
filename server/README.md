# LxChat public server APIs

These dependency-free receivers implement LxChat's two public submission APIs:

* [`rating/`](rating/) accepts `POST /api/rating` and writes valid submissions to SQLite. It exposes no read or administration API.
* [`crash/`](crash/) accepts opt-in crash reports, applies a field allowlist, omits client IP addresses, and writes records to local JSONL storage.

Dashboard code, private read APIs, administration routes, runtime databases, reports, logs, certificates, credentials, host addresses, and production virtual-host configuration are not included.
