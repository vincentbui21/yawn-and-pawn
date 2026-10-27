# Play Store graphics (closed test)

Interim graphics for the closed test, for the owner to approve before upload. Nothing here has been uploaded. Story 8.2 later replaces this set with its `captureStoreScreenshots` task and `StoreAssetsTest`.

Every image is rendered from the real composables with the design preview's sample data (no real names, alarms or payments), by `androidApp/src/testDebug/.../debug/preview/StoreGraphicsTest.kt`. Those tests are skipped in the normal run; to render again after a UI change:

```sh
YP_STORE_GRAPHICS=1 ./gradlew :androidApp:testDebugUnitTest --tests "*StoreGraphics*" --rerun
```

## Files

| File | Size | Format | What it shows |
|---|---|---|---|
| `icon.png` | 512 × 512 | 32-bit PNG (RGBA), 9 KB | The app's filled alarm symbol in Sunrise cream on the Sunrise accent, full bleed (Play rounds the corners) |
| `feature-graphic.png` | 1024 × 500 | 24-bit PNG, no alpha, 36 KB | App name, "Every snooze costs real money. Waking up is free." and a crop of the ringing screen |
| `screenshots/01-ringing.png` | 1080 × 2400 | 24-bit PNG, no alpha | Ringing screen: "I'm up" and "Snooze · $1.00" |
| `screenshots/02-snooze-confirm.png` | 1080 × 2400 | 24-bit PNG, no alpha | Snooze confirm sheet with the price |
| `screenshots/03-check-math.png` | 1080 × 2400 | 24-bit PNG, no alpha | Math check with the grace countdown |
| `screenshots/04-home.png` | 1080 × 2400 | 24-bit PNG, no alpha | Home (Dark): streak card and alarm list |
| `screenshots/05-progress.png` | 1080 × 2400 | 24-bit PNG, no alpha | Progress (Light): 30-morning ring, tiles, streak, calendar |
| `screenshots/06-success.png` | 1080 × 2400 | 24-bit PNG, no alpha | Success: "12 days in a row. Up on time." |
| `screenshots/captioned/01-ringing.png` to `06-success.png` | 1080 × 2400 | 24-bit PNG, no alpha | The same six screens, smaller, under a plain caption |

Screenshots are 9:20 portrait, inside Play's limits (each side 320 to 3,840 px, long side at most twice the short side). Pick either the plain set or the captioned set, not both.

Captions (no em dashes, no superlatives, no numbers or claims):

1. Every snooze costs real money.
2. You see the price before you pay.
3. Getting up is free. Just finish a quick check.
4. Your alarms and your streak at a glance.
5. Every morning on your calendar.
6. Up on time. Keep the streak going.

## For the owner

- **Icon:** the app has no launcher icon yet (it shows Android's default). This icon is a proposal (EXPERIENCE.md open question D1). If you approve it, a follow-up adds the same design as the app's adaptive launcher icon so the store and the phone match.
- **Prices** show in US dollars from the sample data, as Play formats them in en-US.
- Choose plain or captioned screenshots, and which 4 to 6 to upload.
