# Website deployment

## Report

The static site is built and deployed on the Cloudflare side. The repository had no
publishable-site contract, so a build that publishes selected files instead of the
`website` directory leaves `website/legal/` unexposed and breaks the Play Store policy
URLs, with nothing to catch it.

## Change

- `docs/WEBSITE_DEPLOYMENT.md` records the Cloudflare project settings (root directory
  the repository root, empty build command, output directory `website`, production
  branch `main`), the cache table, the review checks and dashboard rollback. It states
  explicitly that there is no GitHub Actions deploy job and no deployment secret in the
  repository.
- `website/_headers` sets the cache policy (HTML revalidated, CSS one hour, images a
  week) and security headers including a `default-src 'none'` CSP.
- `scripts/check-published-site.py` fails when a page is not linked from the index or
  when `_headers` names an unpublished path. It runs in
  `Validate documentation site`, which validates and does not deploy.
- `scripts/check-live-site.py` is the read-only post-deploy check the owner runs
  against the Cloudflare URL; it verifies `/`, `/legal/` and the four policy pages and
  prints each `Cache-Control`.

## Verification

- `python3 scripts/check-documentation-links.py` — 6 pages checked, no errors.
- `python3 scripts/check-published-site.py` — 6 pages reachable, headers reference only
  published paths, no errors.
- Not verified: the live deployment, the `_headers` result and the Cloudflare build
  output directory, because those live in the owner's Cloudflare account. The live check
  script has not been run against a real URL.

## Remaining

- Owner confirms the Cloudflare build publishes the `website` directory itself, so
  `legal/` is included, then runs `scripts/check-live-site.py` against the site.
- Custom domain and the Play Store privacy-policy URL still need the owner.
