# One-time Play initialization bundle

The owner requested a signed initial AAB for manual Play Console initialization,
with GitHub's minimum artifact retention (one day). The owner explicitly accepted
that the plaintext AAB artifact in this public repository is downloadable by
other signed-in readers. This is not a production release of the finished app.

`initial-play-bundle.yml` is dispatch-only and restricted to the owner on main.
It checks out foundation commit d6dbe94, whose validation run 42980838050 passed,
rather than packaging unfinished, unvalidated native changes. It builds version
0.1.0+1 in Actions, creates a new RSA upload key, signs the AAB and verifies it
against that key. It never uploads to Play and never uses SERVICE_ACCOUNT_JSON.

The workflow refuses to run when UPLOAD_KEYSTORE_BASE64 already exists. The key
is generated once and must be retained for all later uploads. The keystore and
password metadata leave the runner only inside a CMS-encrypted recovery archive;
the recipient private key stays in ignored local credential storage. After
recovery, the operator stores UPLOAD_KEYSTORE_BASE64, UPLOAD_STORE_PASSWORD,
UPLOAD_KEY_ALIAS and UPLOAD_KEY_PASSWORD as repository secrets, then deletes the
recovery artifact and bootstrap recipient secret. No key/password enters Git,
plaintext artifacts or logs. The public upload certificate accompanies the AAB.

For future builds, decode the keystore in a runner temporary directory and set
CLOCKROVE_UPLOAD_KEYSTORE, CLOCKROVE_UPLOAD_STORE_PASSWORD,
CLOCKROVE_UPLOAD_KEY_ALIAS and CLOCKROVE_UPLOAD_KEY_PASSWORD. The repository's
release signing configuration uses these environment variables. Always clean
temporary keys after builds. Future Play version codes must exceed 1.

Download `clockrove-initial-play-aab` from the successful workflow run, extract
the AAB and upload it to an appropriate testing/draft track in Play Console.
With Play App Signing, Google registers the upload certificate and signs the
store-distributed APKs using the app-signing key. Preserve the upload key for CI.
Manual upload, Play acceptance, installation, review and publication each require
separate evidence; an artifact build proves none of those outcomes.
