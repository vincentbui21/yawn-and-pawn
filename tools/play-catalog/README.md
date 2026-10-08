# tools/play-catalog

Creates and updates the 50 snooze products in Play Console (Story 4.1, PRD §6.3, AD-7). Prices are never typed by hand, and the catalogue always matches the fee ladder.

**The rule:** after the first live run, every change to the snooze products goes through this tool, in a reviewed commit. Never edit `snooze_usd_NN` products in Play Console by hand. The next run would overwrite the edit or flag the product.

## What it manages

| | |
|---|---|
| Products | `snooze_usd_01` … `snooze_usd_50` (the list is in `config/snooze-products.txt`) |
| Base price | product NN costs NN.00 USD |
| Local prices | Play's `convertRegionPrices` for NN.00 USD, for every region Play offers, plus Play's USD/EUR price for regions it adds later |
| Listing | `en-US`: title "Snooze", description "One snooze for your alarm." Listings in other languages are kept. |
| Purchase option | one Buy option `buy`: legacy-compatible (Play Billing Library 9 shows it as the product's offer), one item per purchase, active |
| Package | `com.yawnandpawn.app` (`docs/decisions/package-id.md`) |

**What it never does:**
- It never touches any other product, such as `spike_s1_test`. Those are reported as `unmanaged`.
- It never deletes anything. The tool has no delete call.
- It never retries a failed write.

**Pending purchases:** the API has no catalogue setting for them. The app turns them on in Play Billing (`enablePendingPurchases(...enableOneTimeProducts())`, Story 4.12).

## Output

Every run prints one line per product, then a summary:

```
snooze_usd_02   update     price USD 3.00 -> USD 2.00
snooze_usd_04   activate   inactive; reactivate
snooze_usd_11   create     USD 11.00, 173 regions
spike_s1_test   unmanaged  not a snooze_usd_NN product; never changed
40 create, 2 update, 1 activate, 7 unchanged, 1 unmanaged, 0 attention. 43 changes.
```

- **create:** the product does not exist. Apply creates it, then activates its `buy` option.
- **update:** the `en-US` listing, the US price, a region, the new-regions price or the Buy option settings differ. Apply patches only those fields. A price update rewrites every local price from a fresh conversion.
- **activate:** the `buy` option is draft or inactive. Apply activates it.
- **unchanged:** nothing to do. Local prices that only moved with exchange rates count as unchanged.
- **unmanaged:** not one of the 50 ids. Never changed.
- **attention:** a managed product has a purchase option other than `buy`. It is left alone; fix it in Play Console, then run again.

When nothing is left to do, the summary ends with `0 changes.` and the last line is `0 changes. Play matches the catalogue.`

**Exit codes:**
- **0:** success.
- **1:** a Play API call failed. The tool stops at once and prints the API's message. Run the dry run again to see what is left.
- **2:** a usage or credentials problem.

## Credentials

The tool reads the service-account key only from:

1. `-Pcredentials=<path>`: a key file **outside the repository**. A path inside the repository is refused. This option wins when both are set.
2. The `PLAY_SERVICE_ACCOUNT_JSON` environment variable, which holds the key JSON itself (as in CI).

When neither is set, the tool stops with a message. It never prints the key.

**The service account needs, in Play Console → Users and permissions → (the service account) → App permissions → Yawn & Pawn:**
- **"Manage store presence"**: create and edit one-time products and their prices.
- **"View app information"**.

The release permissions in `docs/ci-release.md` ("Release apps to testing tracks") are **not** enough. A 401/403 error from the tool names the missing permission. The **Google Play Android Developer API** must be enabled in the service account's Google Cloud project.

## Owner run (Story 4.1 human-verify, Session B)

Do this once, as soon as Story 4.1 is merged. New products can take a few hours to reach Play Billing, so run it well before any real-price test (Story 4.12 smoke, 4.18).

**Before you start:**
- Play Console → Settings → Payments profile is active.
- The service account has the two permissions above.
- The key JSON file is saved **outside** the repository, for example `C:\Users\BuiTua\keys\play-catalog.json`.
- Optional: `spike_s1_test` is deactivated (Session A). The tool leaves it alone either way.

**Steps** (PowerShell, from the repository root, on `main` with 4.1 merged):

1. **Dry run:**
   ```powershell
   $env:JAVA_HOME = "C:/Users/BuiTua/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1"
   ./gradlew playCatalog -Pmode=dry-run "-Pcredentials=C:/Users/BuiTua/keys/play-catalog.json"
   ```
   - Expected: `50 create`, `1 unmanaged` (`spike_s1_test`), `50 changes.` and "Dry run: nothing was written."
   - Copy the whole output.
2. **Apply:**
   ```powershell
   ./gradlew playCatalog -Pmode=apply "-Pcredentials=C:/Users/BuiTua/keys/play-catalog.json"
   ```
   - Expected: 50 `Done i of 50` lines and "Applied 50 changes."
   - Copy the whole output.
   - **If it fails:** read the `FAILED` line. Fix the cause (usually a permission), then go back to step 1. Products created before the failure show as `unchanged` or `activate`; the rest as `create`.
3. **Check in Play Console:** Monetize with Play → Products → One-time products shows 50 active products, `snooze_usd_01` … `snooze_usd_50`. Open one or two and check the local prices (for example `snooze_usd_03`: USD 3.00 in the US, a euro price in Finland).
4. **Second dry run:** run the step 1 command again.
   - Expected: `50 unchanged`, `0 changes.` and "0 changes. Play matches the catalogue."
   - Copy the output.
5. **Record the run:** paste the three outputs, with the date, into `docs/decisions/play-catalog-run.md`. Commit it in a reviewed change.

## Later changes

To change the catalogue (a new listing text, a different price rule, more products):
1. Change `SnoozeCatalog.kt` (and `config/snooze-products.txt` if the ids change) and the tests, in a reviewed commit.
2. After merge, run the dry run, then apply, then a second dry run showing `0 changes.`.
3. Add the outputs to `docs/decisions/play-catalog-run.md`.

To take a product off sale, deactivate it in Play Console. The tool would reactivate a managed product, so remove it from the catalogue in code first.

## Development

- `./gradlew :play-catalog:test` runs the unit tests. They use `FakePlayCatalogApi` and a scripted HTTP transport, and never call Play.
- `./gradlew :play-catalog:checkToolDependencyAllowlist` checks every runtime dependency against `config/tool-dependency-allowlist.txt` (licences listed there).
- Both run in `./gradlew qualityGate`.
- The tool is a top-level included build. It runs in its own JVM through the root `playCatalog` task (`JavaExec`), so the Google API client is on no build-script or app classpath.
