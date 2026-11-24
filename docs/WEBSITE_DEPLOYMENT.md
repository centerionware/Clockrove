# Documentation site deployment

The Clockrove help site is static HTML and CSS in `website/`. It has no build step,
no runtime Worker, and no backend. Cloudflare Pages uploads the directory as-is, so
`website/legal/` is published at `/legal/` and the four policy pages keep the stable
URLs the Play Store listing uses:

- `https://<site>/legal/privacy.html` — Play Store privacy policy URL
- `https://<site>/legal/data-use.html`
- `https://<site>/legal/terms.html`
- `https://<site>/legal/license.html`
- `https://<site>/legal/` — hub linked from the Help site header and footer

## One-time owner setup

Both credentials are supplied manually by the owner; nothing in this repository
contains them, and pull requests from forks never deploy.

1. Create the Pages project on a direct-upload project with `main` as its production
   branch:

   ```sh
   npx wrangler@4.167.0 pages project create clockrove --production-branch=main
   ```

2. Create a Cloudflare API token with the **Cloudflare Pages: Edit** permission for
   the account that owns the project.
3. Add repository secrets under **Settings → Secrets and variables → Actions**:
   - `CLOUDFLARE_API_TOKEN`
   - `CLOUDFLARE_ACCOUNT_ID`
4. Optionally add repository variables to change defaults:
   - `CLOUDFLARE_PAGES_PROJECT` (default `clockrove`)
   - `CLOCKROVE_SITE_URL` (default `https://clockrove.pages.dev`; set it to the custom
     domain once one is attached)
5. Attach the custom domain in the Pages project before changing the Play Store links.

No secret belongs in a workflow file, `.env`, or a commit. If the secrets are absent
the deploy job still validates the site and posts a notice that publishing was
skipped, instead of failing `main`.

## What a deploy does

`.github/workflows/deploy-website.yml` runs on every push to `main` that touches
`website/`. It:

1. Runs the link check and the deployment check
   (`scripts/check-website-deploy.py`), which fails if a page is not linked from the
   index, if `_headers` names a file that is not published, or if the workflow stops
   pinning Wrangler.
2. Uploads `website/` with `npx wrangler@4.167.0 pages deploy website --branch=main`.
3. Requests `/`, `/legal/` and the four policy pages from the live site and fails the
   run if any of them is not reachable or the privacy page does not serve its text.

`npx --yes` is used with an exact version so the tooling is pinned; there is no
floating `latest`.

## Caching and headers

`website/_headers` is read by Pages on every request:

| Path | Cache policy | Why |
| --- | --- | --- |
| `/*` (HTML) | `public, max-age=0, must-revalidate` | A corrected policy or guide is visible immediately; revalidation uses the ETag and costs one conditional request. |
| `/*.css` | `public, max-age=3600, must-revalidate` | One hour of browser reuse without a day of stale styling, because filenames are not fingerprinted. |
| `*.svg`, `*.png`, `*.webp` | `public, max-age=604800` | Guide screenshots and icons rarely change; replace them under a new filename when they do. |

The same file sets a strict `Content-Security-Policy` (`default-src 'none'` with only
same-origin styles and images, so a future inline script stays blocked),
`X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`,
`Permissions-Policy`, `Cross-Origin-Opener-Policy` and HSTS.

Cloudflare's edge caches the uploaded assets and revalidates against the uploaded
copy, so a reader behind the CDN does not need a cache purge after a deploy.

## Reviewing a change before it ships

- `Validate documentation site` runs the link check and deployment check on any pull
  request that touches `website/`.
- Open the preview build of the branch, or run the workflow manually with
  **Actions → Deploy documentation site → Run workflow** after merging.
- Confirm the policy page in a private window, at mobile width, with the browser's
  network panel showing `cf-cache-status`.
- Play Store submission uses the custom-domain URL only after it returns the page.

## Rollback

Pages keeps prior deployments. In the Cloudflare dashboard open the project,
**Deployments**, pick the last good one and **Rollback**. No repository change is
needed, and the next push to `main` publishes the site again.

## Not covered here

Publishing still needs the owner's Cloudflare account and token, the custom domain,
and the store-listing link update. Those remain open in the release checklist; the
repository pipeline is ready before the account exists.
