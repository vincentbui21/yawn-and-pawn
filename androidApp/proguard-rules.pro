# R8 rules for the release build. Libraries ship their own consumer rules; add app rules here only
# when a release build proves one is needed.

# Story 1.12: the session engine, LoggingEffectRunner and SessionJson log effect, event and error types by
# `::class.simpleName`. Keep those class names readable in release logs (names only; members may still shrink).
-keepnames class com.yawnandpawn.app.core.session.**
-keepnames class com.yawnandpawn.app.core.log.**
