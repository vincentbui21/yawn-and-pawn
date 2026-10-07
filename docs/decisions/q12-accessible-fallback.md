# Q12: accessible fallback path for TalkBack users

- **Date:** 2026-10-07 (Story 3.12)
- **Question (PRD Q12):** the fallback check offered Memory Sequence, a visual check, so a TalkBack user whose camera check could not run had no proven way to stop the alarm for free (NFR-9, flow F5).
- **Decision:** the TalkBack path below, built in Stories 3.2 to 3.11 and proven end to end in Story 3.12. Fast-mode defaults are marked "default taken"; the owner can change any of them.

## The TalkBack path (flow F5)

1. **Ringing.** TalkBack reads the clock as the full time first ("6:15 AM"), then "I'm up, button", then snooze with its price or its reason. The clock is capped at 1.3× at large font sizes.
2. **The camera check without a camera.** When the camera or its permission is unavailable, "Camera isn't available. Pick a fallback check." (a polite live region) and the "Can't do this check?" link are there at once, in the first frame after "I'm up". With a working camera the link appears after 5 failed attempts.
3. **The Fallback check picker.** "Pick a fallback check" is the heading, then "Back to check", then one button per non-camera check, each read once as name and description. **Math is always first.**
4. **Math with spoken input.** The problem is a heading read in words ("23 times 4 plus 17"); each number pad key reads its digit; "Delete digit" and "Check" are labelled; the answer is announced politely after each key ("Answer 109"). After a wrong answer "Not quite. Try again." is announced and focus goes back to the "1" key (default taken).
5. **Memory Sequence, numbered and announced.** With TalkBack on when the ring starts, Memory Sequence is chosen in its numbered variant by itself: always 3×3, a number on every tile, the round's sequence announced as numbers ("3, 7, 1, 9"), and the phase line ("Watch the sequence" / "Your turn") is a heading and a polite live region. Tiles are disabled, and say so, while the sequence plays. As a fallback check, Memory Sequence is always the numbered variant, TalkBack on or not (default taken, Story 3.12; epic Story 3.9 "in its numbered accessible variant"). After a wrong tap focus goes to tile 1 once "Your turn" starts again.
6. **Word Unscramble with spoken tiles.** "Word {n} of {count}" is the heading (Word has no instruction line; default taken). Slots read "Slot {n}, empty" or "Slot {n}, {letter}", tiles "Letter {letter}", and the answer so far is announced. After a wrong word focus goes back to the first letter.
7. **No timing and no gestures beyond a tap.** No step needs sight, a timed gesture, a long press or a custom action. Every control has a label and a role; the torch is a switch with its state. The grace countdown is announced politely every 10 s and at 5 s, never every second.
8. **Success.** The headline is the heading, then "Done".

## Evidence

- Host (Robolectric): `TalkBackF5FlowTest` drives F5 through the real wake service and `WakeActivity` by labels and roles only; `CheckSemanticsSuiteTest` checks every check screen, the picker, "Try it" and Success; `MemoryTalkBackTest` proves the automatic numbered variant; `CheckFontScaleTest` checks 200% font on a 360 × 640 dp phone; `AccentTextPlacementTest` and `CheckScreenContrastTest` check the colours.
- Device: TalkBack itself on real phones is Story 3.14, item 14 (F5 end to end without looking, focus after a wrong answer, the torch state) and item 15 (200% on the smallest device).
