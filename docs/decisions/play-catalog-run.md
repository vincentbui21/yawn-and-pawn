# Play catalogue runs

Every live run of `tools/play-catalog` against Play is recorded here (Story 4.1 human-verify; README "Owner run").
Each run needs three outputs: the dry run, the apply, and a second dry run showing `0 changes.`. Never paste the key or any other credential.

## Run 1: first creation of the 50 snooze products

- **Date:** _(to fill in)_
- **Run by:** _(owner)_
- **Commit:** _(`git rev-parse --short HEAD` on main)_
- **Play Console check:** Monetize with Play → Products → One-time products shows 50 active products `snooze_usd_01` … `snooze_usd_50` with local prices: _(yes / no, with notes)_

### Dry run

```
(paste the output of ./gradlew playCatalog -Pmode=dry-run here)
```

### Apply

```
(paste the output of ./gradlew playCatalog -Pmode=apply here)
```

### Second dry run (expected: 0 changes.)

```
(paste the output of the second dry run here)
```
