# Website deployment

## Report

The static site had no deployment configuration in the repository, so the published
site did not reliably include `website/legal/` and nothing checked that the Play
Store policy URLs answered.

## Change

- `.github/workflows/deploy-website.yml` uploads `website/` to Cloudflare Pages on
  every push to `main` that changes the site, using a pinned `wrangler@4.167.0` on
  pinned Node 22.12.0. It runs the link and deployment checks first, then requests
  `/`, `/legal/` and the four policy pages from the live URL and fails if any is not
  reachable or the privacy page does not serve its text.
- `website/_headers` sets the cache policy (HTML revalidated, CSS one hour, images a
  week) and security headers including a `default-src 'none'` CSP.
- `scripts/check-website-deploy.py` fails when a page is not linked from the index,
  when `_headers` names an unpublished path, or when the workflow stops pinning
  Wrangler. It runs in the `Validate documentation site` workflow.
- `docs/WEBSITE_DEPLOYMENT.md` records the one-time owner setup, the cache table,
  review steps and rollback.

## Verification

- `python3 scripts/check-documentation-links.py` — 6 pages checked, no errors.
- `python3 scripts/check-website-deploy.py` — 6 pages reachable, headers and workflow
  pinned, no errors.
- Not verified: an actual Pages upload and the live-URL check, because the owner's
  Cloudflare project, token and account ID are not available to CI. The job reports a
  notice instead of failing while they are missing.

## Remaining

- Owner creates the Pages project on production branch `main`, adds
  `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`, then attaches the custom domain
  and points the Play Store privacy-policy URL at it.
