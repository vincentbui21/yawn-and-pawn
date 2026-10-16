# R8 rules for the release build. Libraries ship their own consumer rules; add app rules here only
# when a release build proves one is needed.

# Story 1.12: the session engine, LoggingEffectRunner and SessionJson log effect, event and error types by
# `::class.simpleName`. Keep those class names readable in release logs (names only; members may still shrink).
-keepnames class com.yawnandpawn.app.core.session.**
-keepnames class com.yawnandpawn.app.core.log.**

# Story 5.6 review fix 8: play-services-ads-identifier is excluded from Firebase Analytics on purpose. The measurement
# code reads the advertising id only when ad storage is granted, which the app never does (manifest default and the
# consent gate both deny it), so the missing class is never loaded.
-dontwarn com.google.android.gms.ads.identifier.**
