# Documentation site deployment

The Clockrove help site is static HTML and CSS in `website/`. There is no build step,
no bundler, no runtime Worker code, and no backend. Deployment is configured on the
Cloudflare side from this repository; **there is no GitHub Actions deploy job and no
deploy secret in this repository.**

Because Cloudflare publishes whatever the configured build output directory contains,
the rule that matters is simple: **the output directory must be `website` itself**, not
a hand-picked set of files. Then `website/legal/` is published at `/legal/` and the four
policy pages keep the stable URLs the Play Store listing uses:

- `https://<site>/legal/privacy.html` — Play Store privacy policy URL
- `https://<site>/legal/data-use.html`
- `https://<site>/legal/terms.html`
- `https://<site>/legal/license.html`
- `https://<site>/legal/` — hub linked from the Help site header and footer

A deployment that only serves `/` and `styles.css` means the Cloudflare build is
publishing selected files rather than the `website` directory.

## Cloudflare settings

Set these once in the Cloudflare project (Pages, or a Worker with static assets built
from this repository):

| Setting | Value |
| --- | --- |
| Repository | `centerionware/Clockrove`, production branch `main` |
| Root directory | repository root |
| Build command | *(empty — nothing to build)* |
| Build output directory | `website` |
| Production branch | `main` |

Two consequences of the empty build:
- Nothing is minified or fingerprinted, so the cache policy below keeps browser reuse
  short enough that a corrected legal page is visible immediately.
- The repository's Python checks are not part of the deploy. They run in
  `Validate documentation site` on pull requests, which is validation, not CI/CD for
  publishing.

For a Worker with static assets, the same directory is declared in the project's
configuration on the Cloudflare side; keep the repository free of a duplicate
`wrangler.jsonc` that could disagree with the dashboard.

## Caching and headers

`website/_headers` sets the cache policy and security headers for the published site:

| Path | Cache policy | Why |
| --- | --- | --- |
| `/*` (HTML) | `public, max-age=0, must-revalidate` | A corrected policy or guide is visible immediately; revalidation uses the ETag and costs one conditional request. |
| `/*.css` | `public, max-age=3600, must-revalidate` | One hour of browser reuse without a day of stale styling, because filenames are not fingerprinted. |
| `*.svg`, `*.png`, `*.webp` | `public, max-age=604800` | Guide screenshots and icons rarely change; replace them under a new filename when they do. |

The same file sets a strict `Content-Security-Policy` (`default-src 'none'` with only
same-origin styles and images, so a future inline script stays blocked),
`X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, `Permissions-Policy`,
`Cross-Origin-Opener-Policy` and HSTS.

Cloudflare's edge caches the published assets and revalidates against the uploaded copy,
so a reader behind the CDN does not need a cache purge after a deploy. If the project is
configured as a Worker with static assets and the `_headers` file is not applied, set the
same two `Cache-Control` values in a Cloudflare Cache Rule for the site host and keep the
repository file as the record of the intended policy.

## Checking a deployment

Run the read-only check from any machine after a Cloudflare build finishes:

```sh
python3 scripts/check-live-site.py https://clockrove.pages.dev
```

It requests `/`, `/legal/` and the four policy pages, prints each `Cache-Control`
header, and fails if a page is missing or the privacy page does not serve its text.
Use a private window at mobile width for the visual check, and confirm the Play Store
privacy-policy URL only after this passes.

## Reviewing a change before merging

`Validate documentation site` runs on pull requests that touch `website/`:

- `scripts/check-documentation-links.py` — every local link, fragment and image.
- `scripts/check-published-site.py` — every page is linked from the index (so a page
  cannot be uploaded but unreachable), and `_headers` names only published paths.

## Rollback

The Cloudflare project keeps prior deployments. Choose the last good deployment in the
dashboard and roll back; the next build from `main` publishes the site again. No
repository change or secret rotation is involved.

## Still open

The first publish, the custom domain, and the store-listing URL update are owner
actions in the Cloudflare and Play dashboards. They stay open in the release checklist.
