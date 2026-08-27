# Kotlin source size policy

LxChat limits every handwritten Kotlin source file to at most 1499 physical lines. The internal
decomposition target is 700-800 lines; 1499 is a hard regression gate, not a design target.

The cap leaves headroom for AI-generated incremental code: growth within the cap is free, and only
a source exceeding the cap must be decomposed. The previous 999-line gate was a reasonable
migration target, but for an AI-driven codebase it tripped on routine additions to a few files
(e.g. SettingsManager.kt growing 1005 -> 1075 lines failed CI with `baseline_growth`), which is
friction unrelated to code quality. The gate still catches genuinely oversized files.

`verifyKotlinFileSize` scans main, test, flavor and build-logic Kotlin sources. It excludes build
and generated output, caches and the vendored `thirdparty` tree. CRLF, LF and standalone CR each
count as one line boundary, so Windows and Linux produce the same result.

The temporary baseline is `config/kotlin-source-size-baseline.txt`. Each entry is an exact cap
recorded for a source that exceeds 1499 lines and awaits decomposition. The convention plugin
freezes that initial path/cap set: a baseline source may shrink but may not grow, entries may be
lowered or removed, and neither a new path nor a higher cap is accepted. A new oversized source
always fails, and an entry becomes invalid as soon as its source reaches the cap or is removed.
The baseline must be edited intentionally; no task rewrites it.

The migration baseline currently has zero entries. All handwritten Kotlin sources are therefore
checked directly against the 1499-line limit; the immutable initial cap set remains in build logic
only to prevent a removed historical exception from being reintroduced.

The root convention plugin wires the verification into Gradle `check`, the aggregate `test` task
used by the local `build.ps1`, and Android `preBuild`. GitHub Actions also invokes the task
explicitly before assembling the F-Droid APK.

Run the policy tests and repository check with:

```powershell
.\gradlew.bat -p build-logic test
.\gradlew.bat verifyKotlinFileSize
```
