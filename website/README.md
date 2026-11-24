# Clockrove documentation site

The website uses static HTML and CSS with no build step or runtime backend.
For Cloudflare Pages, use `website` as the output directory. Keep release
publishing disabled while reviewing this source.

`.github/workflows/deploy-website.yml` uploads this directory to Cloudflare Pages
on every push to `main` that changes it, then checks that `/legal/` and the four
policy pages answer. `_headers` sets the cache policy and security headers; see
[website deployment](../docs/WEBSITE_DEPLOYMENT.md) for the owner setup, the
caching table and rollback.

Before public deployment, add the reviewed version-specific screenshots and
guides, publish the approved policy texts at stable URLs, and verify links,
mobile layout, and images through the release validation process. The policy
drafts are kept in [docs/policies](../docs/policies/) during review.

## Published legal documents

The Play Store listing links to the static pages under `legal/`. Keep these URLs
stable:

- `legal/privacy.html` — Privacy Policy (Play Store privacy policy URL)
- `legal/data-use.html` — Data Use Policy
- `legal/terms.html` — Terms of Use
- `legal/license.html` — Source-Available License

`legal/index.html` is the hub linked from the Help site header and footer. The
canonical license text is the repository `LICENSE` file; the page mirrors it for
readers. Update all four pages and the repository `LICENSE` in the same change
when the wording changes, then re-check the store listing link.
