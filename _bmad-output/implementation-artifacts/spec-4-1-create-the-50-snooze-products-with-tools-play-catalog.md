---
title: 'Story 4.1: Create the 50 snooze products with tools/play-catalog'
type: 'feature'
created: '2026-10-08'
status: 'review'
baseline_revision: '0cacc88'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-3-generated-design-tokens-and-ppstheme.md'
  - '{project-root}/docs/ci-release.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Snoozing costs B × N USD, so Play needs 50 consumable one-time products `snooze_usd_01` … `snooze_usd_50` priced 1.00 … 50.00 USD with local prices everywhere Play sells. Typing 50 products by hand is slow and error-prone, and nothing would keep the catalogue in line with the fee ladder afterwards.

**Approach:**
- **Tool:** `tools/play-catalog` is a JVM command-line tool in its own included build, like `tools/tokens` (Story 1.3), with its unit tests in `qualityGate`. The root task `./gradlew playCatalog -Pmode=dry-run|apply [-Pcredentials=<path>]` runs it in a separate JVM (`JavaExec`), so the Play API client never joins the Gradle build classpath or the app classpath.
- **Desired catalogue (in memory):** 50 products, id `snooze_usd_NN`, base price NN.00 USD. Each has:
  - the `en-US` listing "Snooze" / "One snooze for your alarm." (EXPERIENCE.md Key strings);
  - one Buy purchase option `buy`, legacy-compatible (what Play Billing Library 9 shows as the product's one-time offer), multi-quantity off;
  - a regional price for every region `monetization.convertRegionPrices` returns for NN.00 USD (US pinned to NN.00 USD), all `AVAILABLE`, plus the new-regions USD/EUR prices from the same call;
  - state `ACTIVE`.
- **Plan:** list the existing one-time products (`monetization.onetimeproducts.list`, all pages; package `com.yawnandpawn.app` from `docs/decisions/package-id.md`) and print one line per product: create, update (with the reason), activate/reactivate, unchanged, unmanaged or attention. The last line is the summary, ending "N changes." ("0 changes." when nothing is left).
- **Apply:** for each planned product, in id order: `onetimeproducts.patch` (`allowMissing=true` to create, `updateMask` limited to what differs, `regionsVersion.version` from the conversion), then `purchaseOptions.batchUpdateStates` (activate) when the `buy` option is not `ACTIVE`. The first failed call stops the run with the API message and exit code 1. Nothing is retried.
- **Credentials:** only from `-Pcredentials=<path>` (a key file outside the repository) or the `PLAY_SERVICE_ACCOUNT_JSON` environment variable (the key JSON itself, as in CI). Neither present → exit 2 with a clear message.

## Boundaries & Constraints

**Always:**
- Dry run makes no write call (asserted on the fake API client); `convertRegionPrices` is a read-only calculation and counts as a read.
- A product id outside the 50 managed ids (for example `spike_s1_test`) is reported as "unmanaged" and never written.
- Apply is idempotent: a second run right after a successful apply reports "0 changes." and makes no write call.
- Listings in other languages on a managed product are kept when the listing is patched.
- The service account needs Play Console **"Manage store presence"** (and "View app information") for Yawn & Pawn. The release-only account in `docs/ci-release.md` is not enough. A 401/403 error says so.
- The 50 ids live in `config/snooze-products.txt` (shared with Story 4.2's FeeLadder test). A tool test asserts the file equals the generated list.
- Tool-only dependencies are listed with their licences in `config/tool-dependency-allowlist.txt`, checked by `checkToolDependencyAllowlist` in `qualityGate`.

**Never:**
- No delete call exists in the tool's API port, so the tool cannot delete a product, a purchase option or an offer.
- No real API call in tests; no credentials, key file or token in the repository or in the output.
- No change to app code, the app's dependency allowlist or `sprint-status.yaml`.

## AC deviations and decisions (fast mode: default taken, owner can change)

1. **Pending purchases:** the Play Developer API (`v3-rev20260924`) has no pending-transactions field on one-time products or purchase options. Pending purchases for one-time products are switched on in the app with `enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())`, which PBL 8+ requires. That is Story 4.12's adapter. The catalogue sets nothing for it.
2. **"Status active":** one-time products have no state in the new API. The state lives on the purchase option, so "active" means the `buy` option is `ACTIVE`.
3. **Price drift:** a product is "update" when the US price is not NN.00 USD, a region Play now offers is missing or not available, the new-regions USD price is not NN.00, the `buy` option is not legacy-compatible single-quantity, or the `en-US` listing differs. Other regions' local prices are **not** compared to a fresh conversion, because Play's conversion rates move and every later dry run would show 50 updates. A price update rewrites every regional price from a fresh conversion.
4. **Unexpected purchase options:** a managed product with a purchase option other than `buy` is reported as "attention" and left alone (patching `purchaseOptions` would remove the other option, which counts as a delete).
5. **Listing language:** `en-US`, assumed to be the app's default store-listing language from Story 1.4.
6. **Purchase option id:** `buy`.
7. **Credentials:** `-Pcredentials` wins over the environment variable when both are set. A `-Pcredentials` path inside the repository is refused.
8. **Wiring:** the tool is a top-level included build (`includeBuild("tools/play-catalog")`) run through `JavaExec`, not a Gradle plugin like `tools/tokens`. A plugin would put the Google API client (Guava, HTTP client, gRPC context) on the build-script classpath next to AGP.
9. **Allowlist:** the tool's dependencies go in a new `config/tool-dependency-allowlist.txt` rather than `config/dependency-allowlist.txt`. An entry in the app allowlist would let the same library into the app without review.
10. **Latency:** writes use Play's default (latency-sensitive) propagation, so the products reach Billing as soon as possible.
11. **`docs/decisions/package-id.md`** did not exist. This story writes it (`com.yawnandpawn.app`, from `applicationId`), and a test keeps it, `androidApp/build.gradle.kts` and the tool constant in line.

## I/O & Edge-Case Matrix

| Scenario | Existing catalogue | Expected output / writes |
|----------|--------------------|--------------------------|
| First dry run | `spike_s1_test` only | 50 create, 1 unmanaged, "50 changes."; no write |
| First apply | `spike_s1_test` only | 50 patch (`allowMissing`) + 50 activate; summary "50 changes." |
| Partial catalogue (AC) | 10 managed, 2 wrong price, 1 inactive, + `spike_s1_test` | 40 create, 2 update, 1 reactivate, 7 unchanged, 1 unmanaged; 42 patches + 41 activations |
| Second run | everything applied | "0 changes."; no write |
| Listing typo | title "Snoze" | update `listings` only; other languages kept |
| Missing region | Play added a region | update `purchaseOptions` (fresh conversion) |
| Draft option | an earlier apply failed after the patch | activate only |
| Extra purchase option | `buy` + `rent` | attention, untouched |
| API error | patch #3 fails with 403 | stops, message + "Manage store presence" hint, exit 1; no retry, no later write |
| Credentials | none / env JSON / `-Pcredentials` file / file in repo / missing file / env not JSON | error exit 2 / env / file / error / error / error |
| Mode | missing or unknown `-Pmode` | usage error, exit 2 |

</intent-contract>

## Code Map

- `settings.gradle.kts`: top-level `includeBuild("tools/play-catalog")`.
- `build.gradle.kts`: `playCatalogTool` configuration, the `playCatalog` `JavaExec` task, detekt source, and `qualityGate` gets the tool's `:test` and `:checkToolDependencyAllowlist`.
- `tools/play-catalog/` (package `com.yawnandpawn.app.playcatalog`):
  - `CatalogModel.kt`: `Price`, `Listing`, `PurchaseOption`, `OneTimeProduct`, `ConvertedPrices`.
  - `SnoozeCatalog.kt`: ids, prices, listing, package name, desired product.
  - `CatalogPlan.kt`: the pure planner.
  - `PlayCatalogApi.kt`: the port (list, convert, patch, activate; no delete) and `PlayApiException`.
  - `CatalogRunner.kt`: dry-run and apply, output and exit codes.
  - `Credentials.kt`: credential source resolution.
  - `GooglePlayCatalogApi.kt`: the androidpublisher adapter and model mapping.
  - `Main.kt`: arguments and wiring.
- `config/snooze-products.txt`, `config/tool-dependency-allowlist.txt`.
- `docs/decisions/package-id.md`, `docs/decisions/play-catalog-run.md` (template for the owner run), `tools/play-catalog/README.md`.
- **Tests:** `SnoozeCatalogTest`, `CatalogPlanTest`, `CatalogRunnerTest` (on `FakePlayCatalogApi`), `CredentialsTest`, `GooglePlayCatalogApiTest` (mapping, and request URLs on `MockHttpTransport`), `MainTest`, `PackageIdTest`.

## Verification

**Commands:**
- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon` -- expected: BUILD SUCCESSFUL.

**Human-verify (owner, Session B):** see `tools/play-catalog/README.md`: dry run, apply, check 50 active products in Play Console, second dry run "0 changes.", outputs pasted into `docs/decisions/play-catalog-run.md`.

## Auto Run Result

Status: implemented in fast mode (one agent; owner-approved unattended Epic 4 run), waiting for review. Branch `story/4-1-play-catalog` on `0cacc88`.

**Verification:**
- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon` ends BUILD SUCCESSFUL (10 min).
- 48 tool tests on `FakePlayCatalogApi` and a scripted `MockHttpTransport`, with no network.
- `./gradlew playCatalog` without credentials stops with exit 2 and the "No Play credentials" message. No real Play call was made.

**Notes:**
- **Patch path:** the client's patch path is `.../onetimeproducts/{productId}` (lower case), unlike list's `oneTimeProducts`. It comes from Google's discovery document; a test pins it.
- **Owner run:** the live run (README "Owner run", Session B) is what proves that Play accepts the request bodies. That covers the required `regionsVersion`, the purchase option starting as DRAFT, and the tax defaults. If Play rejects a field, the tool stops at the first product with Play's message.
