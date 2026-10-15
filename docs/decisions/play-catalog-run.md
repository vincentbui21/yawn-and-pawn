# Play catalogue runs

Every live run of `tools/play-catalog` against Play is recorded here (Story 4.1 human-verify; README "Owner run").
Each run needs three outputs: the dry run, the apply, and a second dry run showing `0 changes.`. Never paste the key or any other credential.

## Run 1: first creation of the 50 snooze products

- **Date:** 2026-10-15
- **Run by:** Claude Code on the owner's PC, with the owner granting the Play Console permissions (Session B)
- **Commit:** `c11cc11b` (main)
- **Play Console check:** Monetize with Play → Products → One-time products shows 50 active products `snooze_usd_01` … `snooze_usd_50` with local prices: _(owner to confirm)_
- **Notes:**
  - With the service account holding only "View app information", "Manage store presence" and "View financial data" (at app level, then also at account level), `convertRegionPrices` returned HTTP 403 "The caller does not have permission" for 3 hours. The error gave only the generic reason `forbidden`. `onetimeproducts.list` worked throughout.
  - The run worked once the service account had **Admin (all permissions) on Yawn and Pawn only**. The owner removes Admin again right after the run. A future run needs Admin granted temporarily the same way (README "Service account").
  - The first apply attempt stopped at `convertRegionPrices(USD 18.00): HTTP 503: The service is currently unavailable.` before writing anything (prices are converted before any write). A rerun a minute later applied all 50.

### Dry run

```
Play catalogue for com.yawnandpawn.app (mode: dry-run)
Found 1 one-time products in Play.
Play prices 174 regions (regions version 2026/01).
snooze_usd_01   create     USD 1.00, 174 regions
snooze_usd_02   create     USD 2.00, 174 regions
snooze_usd_03   create     USD 3.00, 174 regions
snooze_usd_04   create     USD 4.00, 174 regions
snooze_usd_05   create     USD 5.00, 174 regions
snooze_usd_06   create     USD 6.00, 174 regions
snooze_usd_07   create     USD 7.00, 174 regions
snooze_usd_08   create     USD 8.00, 174 regions
snooze_usd_09   create     USD 9.00, 174 regions
snooze_usd_10   create     USD 10.00, 174 regions
snooze_usd_11   create     USD 11.00, 174 regions
snooze_usd_12   create     USD 12.00, 174 regions
snooze_usd_13   create     USD 13.00, 174 regions
snooze_usd_14   create     USD 14.00, 174 regions
snooze_usd_15   create     USD 15.00, 174 regions
snooze_usd_16   create     USD 16.00, 174 regions
snooze_usd_17   create     USD 17.00, 174 regions
snooze_usd_18   create     USD 18.00, 174 regions
snooze_usd_19   create     USD 19.00, 174 regions
snooze_usd_20   create     USD 20.00, 174 regions
snooze_usd_21   create     USD 21.00, 174 regions
snooze_usd_22   create     USD 22.00, 174 regions
snooze_usd_23   create     USD 23.00, 174 regions
snooze_usd_24   create     USD 24.00, 174 regions
snooze_usd_25   create     USD 25.00, 174 regions
snooze_usd_26   create     USD 26.00, 174 regions
snooze_usd_27   create     USD 27.00, 174 regions
snooze_usd_28   create     USD 28.00, 174 regions
snooze_usd_29   create     USD 29.00, 174 regions
snooze_usd_30   create     USD 30.00, 174 regions
snooze_usd_31   create     USD 31.00, 174 regions
snooze_usd_32   create     USD 32.00, 174 regions
snooze_usd_33   create     USD 33.00, 174 regions
snooze_usd_34   create     USD 34.00, 174 regions
snooze_usd_35   create     USD 35.00, 174 regions
snooze_usd_36   create     USD 36.00, 174 regions
snooze_usd_37   create     USD 37.00, 174 regions
snooze_usd_38   create     USD 38.00, 174 regions
snooze_usd_39   create     USD 39.00, 174 regions
snooze_usd_40   create     USD 40.00, 174 regions
snooze_usd_41   create     USD 41.00, 174 regions
snooze_usd_42   create     USD 42.00, 174 regions
snooze_usd_43   create     USD 43.00, 174 regions
snooze_usd_44   create     USD 44.00, 174 regions
snooze_usd_45   create     USD 45.00, 174 regions
snooze_usd_46   create     USD 46.00, 174 regions
snooze_usd_47   create     USD 47.00, 174 regions
snooze_usd_48   create     USD 48.00, 174 regions
snooze_usd_49   create     USD 49.00, 174 regions
snooze_usd_50   create     USD 50.00, 174 regions
spike_s1_test   unmanaged  not a snooze_usd_NN product; never changed
50 create, 0 update, 0 activate, 0 unchanged, 1 unmanaged, 0 attention. 50 changes.
Dry run: nothing was written. Run ./gradlew playCatalog -PplayCatalogMode=apply to make these changes.
```

### Apply

```
Play catalogue for com.yawnandpawn.app (mode: apply)
Found 1 one-time products in Play.
Play prices 174 regions (regions version 2026/01).
snooze_usd_01   create     USD 1.00, 174 regions
snooze_usd_02   create     USD 2.00, 174 regions
snooze_usd_03   create     USD 3.00, 174 regions
snooze_usd_04   create     USD 4.00, 174 regions
snooze_usd_05   create     USD 5.00, 174 regions
snooze_usd_06   create     USD 6.00, 174 regions
snooze_usd_07   create     USD 7.00, 174 regions
snooze_usd_08   create     USD 8.00, 174 regions
snooze_usd_09   create     USD 9.00, 174 regions
snooze_usd_10   create     USD 10.00, 174 regions
snooze_usd_11   create     USD 11.00, 174 regions
snooze_usd_12   create     USD 12.00, 174 regions
snooze_usd_13   create     USD 13.00, 174 regions
snooze_usd_14   create     USD 14.00, 174 regions
snooze_usd_15   create     USD 15.00, 174 regions
snooze_usd_16   create     USD 16.00, 174 regions
snooze_usd_17   create     USD 17.00, 174 regions
snooze_usd_18   create     USD 18.00, 174 regions
snooze_usd_19   create     USD 19.00, 174 regions
snooze_usd_20   create     USD 20.00, 174 regions
snooze_usd_21   create     USD 21.00, 174 regions
snooze_usd_22   create     USD 22.00, 174 regions
snooze_usd_23   create     USD 23.00, 174 regions
snooze_usd_24   create     USD 24.00, 174 regions
snooze_usd_25   create     USD 25.00, 174 regions
snooze_usd_26   create     USD 26.00, 174 regions
snooze_usd_27   create     USD 27.00, 174 regions
snooze_usd_28   create     USD 28.00, 174 regions
snooze_usd_29   create     USD 29.00, 174 regions
snooze_usd_30   create     USD 30.00, 174 regions
snooze_usd_31   create     USD 31.00, 174 regions
snooze_usd_32   create     USD 32.00, 174 regions
snooze_usd_33   create     USD 33.00, 174 regions
snooze_usd_34   create     USD 34.00, 174 regions
snooze_usd_35   create     USD 35.00, 174 regions
snooze_usd_36   create     USD 36.00, 174 regions
snooze_usd_37   create     USD 37.00, 174 regions
snooze_usd_38   create     USD 38.00, 174 regions
snooze_usd_39   create     USD 39.00, 174 regions
snooze_usd_40   create     USD 40.00, 174 regions
snooze_usd_41   create     USD 41.00, 174 regions
snooze_usd_42   create     USD 42.00, 174 regions
snooze_usd_43   create     USD 43.00, 174 regions
snooze_usd_44   create     USD 44.00, 174 regions
snooze_usd_45   create     USD 45.00, 174 regions
snooze_usd_46   create     USD 46.00, 174 regions
snooze_usd_47   create     USD 47.00, 174 regions
snooze_usd_48   create     USD 48.00, 174 regions
snooze_usd_49   create     USD 49.00, 174 regions
snooze_usd_50   create     USD 50.00, 174 regions
spike_s1_test   unmanaged  not a snooze_usd_NN product; never changed
50 create, 0 update, 0 activate, 0 unchanged, 1 unmanaged, 0 attention. 50 changes.
Done 1 of 50: snooze_usd_01 create
Done 2 of 50: snooze_usd_02 create
Done 3 of 50: snooze_usd_03 create
Done 4 of 50: snooze_usd_04 create
Done 5 of 50: snooze_usd_05 create
Done 6 of 50: snooze_usd_06 create
Done 7 of 50: snooze_usd_07 create
Done 8 of 50: snooze_usd_08 create
Done 9 of 50: snooze_usd_09 create
Done 10 of 50: snooze_usd_10 create
Done 11 of 50: snooze_usd_11 create
Done 12 of 50: snooze_usd_12 create
Done 13 of 50: snooze_usd_13 create
Done 14 of 50: snooze_usd_14 create
Done 15 of 50: snooze_usd_15 create
Done 16 of 50: snooze_usd_16 create
Done 17 of 50: snooze_usd_17 create
Done 18 of 50: snooze_usd_18 create
Done 19 of 50: snooze_usd_19 create
Done 20 of 50: snooze_usd_20 create
Done 21 of 50: snooze_usd_21 create
Done 22 of 50: snooze_usd_22 create
Done 23 of 50: snooze_usd_23 create
Done 24 of 50: snooze_usd_24 create
Done 25 of 50: snooze_usd_25 create
Done 26 of 50: snooze_usd_26 create
Done 27 of 50: snooze_usd_27 create
Done 28 of 50: snooze_usd_28 create
Done 29 of 50: snooze_usd_29 create
Done 30 of 50: snooze_usd_30 create
Done 31 of 50: snooze_usd_31 create
Done 32 of 50: snooze_usd_32 create
Done 33 of 50: snooze_usd_33 create
Done 34 of 50: snooze_usd_34 create
Done 35 of 50: snooze_usd_35 create
Done 36 of 50: snooze_usd_36 create
Done 37 of 50: snooze_usd_37 create
Done 38 of 50: snooze_usd_38 create
Done 39 of 50: snooze_usd_39 create
Done 40 of 50: snooze_usd_40 create
Done 41 of 50: snooze_usd_41 create
Done 42 of 50: snooze_usd_42 create
Done 43 of 50: snooze_usd_43 create
Done 44 of 50: snooze_usd_44 create
Done 45 of 50: snooze_usd_45 create
Done 46 of 50: snooze_usd_46 create
Done 47 of 50: snooze_usd_47 create
Done 48 of 50: snooze_usd_48 create
Done 49 of 50: snooze_usd_49 create
Done 50 of 50: snooze_usd_50 create
Applied 50 changes. Run the dry run again: it should report 0 changes.
```

### Second dry run (expected: 0 changes.)

```
Play catalogue for com.yawnandpawn.app (mode: dry-run)
Found 51 one-time products in Play.
Play prices 174 regions (regions version 2026/01).
snooze_usd_01   unchanged
snooze_usd_02   unchanged
snooze_usd_03   unchanged
snooze_usd_04   unchanged
snooze_usd_05   unchanged
snooze_usd_06   unchanged
snooze_usd_07   unchanged
snooze_usd_08   unchanged
snooze_usd_09   unchanged
snooze_usd_10   unchanged
snooze_usd_11   unchanged
snooze_usd_12   unchanged
snooze_usd_13   unchanged
snooze_usd_14   unchanged
snooze_usd_15   unchanged
snooze_usd_16   unchanged
snooze_usd_17   unchanged
snooze_usd_18   unchanged
snooze_usd_19   unchanged
snooze_usd_20   unchanged
snooze_usd_21   unchanged
snooze_usd_22   unchanged
snooze_usd_23   unchanged
snooze_usd_24   unchanged
snooze_usd_25   unchanged
snooze_usd_26   unchanged
snooze_usd_27   unchanged
snooze_usd_28   unchanged
snooze_usd_29   unchanged
snooze_usd_30   unchanged
snooze_usd_31   unchanged
snooze_usd_32   unchanged
snooze_usd_33   unchanged
snooze_usd_34   unchanged
snooze_usd_35   unchanged
snooze_usd_36   unchanged
snooze_usd_37   unchanged
snooze_usd_38   unchanged
snooze_usd_39   unchanged
snooze_usd_40   unchanged
snooze_usd_41   unchanged
snooze_usd_42   unchanged
snooze_usd_43   unchanged
snooze_usd_44   unchanged
snooze_usd_45   unchanged
snooze_usd_46   unchanged
snooze_usd_47   unchanged
snooze_usd_48   unchanged
snooze_usd_49   unchanged
snooze_usd_50   unchanged
spike_s1_test   unmanaged  not a snooze_usd_NN product; never changed
0 create, 0 update, 0 activate, 50 unchanged, 1 unmanaged, 0 attention. 0 changes.
0 changes. Play matches the catalogue.
```
