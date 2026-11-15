# Security policy

> 🇫🇷 [Lire en français](SECURITY.fr.md)

## Supported versions

| Version | Supported |
|---------|-----------|
| `main` (HEAD) | ✓ |
| the latest release | ✓ security fixes |
| Older tags | ✗ no support |

The project is in fast pre-public dev cycles — there is no
stabilisation branch yet. All security fixes land on `main` and are
tagged in the next release.

## Reporting a vulnerability

**Do not open a public issue** for a security problem. Contact the
maintainer privately:

- GitHub Security Advisory (preferred): "Security" tab of the repo
  → "Report a vulnerability". The report stays private until the fix
  is published.
- Fallback: email to `contact@torchonsetserviettes.app`.

Include in the report:

- A description of the problem
- Steps to reproduce (ideally with a minimal PoC)
- Estimated impact (unauthorised read/write, DoS, RCE…)
- Any known affected version

## In-scope

- Authentication or authorisation bypass (JWT, household scoping,
  household roles: only an `admin` may change household settings or
  activate premium)
- Injection (SQL, command, header, template)
- Cross-household data leak (a user A seeing data from a household B
  they don't belong to)
- Privilege escalation (a `member` performing an `admin`-only action, or
  becoming `admin` without legitimacy)
- Android-side vulnerabilities (intent injection, secret storage,
  etc.)
- Vulnerabilities in production dependencies

## Out-of-scope

- Voluntary self-DoS (sending 10 000 requests with your own token)
- Rate limiting on authenticated routes, which has none at this stage (the routes reachable
  without a token are limited per IP: bypassing that limit IS in scope)
- Vulnerabilities in a third-party self-hosted instance (configure
  Caddy / Tailscale yourself, that's not the application code)
- UX/UI suggestions disguised as security

## Response process

This is a solo project: there is no numeric response-time guarantee.

1. Acknowledgement of the report within a few days.
2. Investigation and reproduction.
3. Fix developed on a private branch, then released on a best-effort basis:
   the more severe the flaw, the sooner.
4. Coordinated disclosure: you can publish your finding once the fix
   is tagged and deployed, with credit in the changelog if you wish.

## No bug bounty

The project has no bug bounty program and is not in a financial
position to offer one. Credit (changelog + public thank you) is the
only consideration offered.
