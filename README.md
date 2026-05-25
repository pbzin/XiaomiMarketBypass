<p align="center">
  <img src="logo.png" width="180" alt="TabVision YT Logo">
</p>

[![Repo Views](https://api.visitorbadge.io/api/visitors?path=https%3A%2F%2Fgithub.com%2Fpbzin%2FXiaomiMarketBypass&label=repo%20views&countColor=%230e75b6&style=flat)](https://visitorbadge.io/status?path=https%3A%2F%2Fgithub.com%2Fpbzin%2FXiaomiMarketBypass)

# Xiaomi Market Bypass

LSPosed module for running Xiaomi Market on AOSP/crDroid ROMs.

The module targets `com.xiaomi.market` and patches the compatibility gaps that make recent Xiaomi Market builds misbehave outside MIUI/HyperOS. It was developed and tested on Xiaomi Redmi Note 13 4G (`sapphire`) running crDroid Android 16.

## What it fixes

- Missing MIUI framework classes and methods used by Xiaomi Market.
- Xiaomi Market identity/storage compatibility checks that crash or block flows on AOSP.
- Blocked `Settings.Secure` writes that are not valid for a normal third-party app.
- PackageInstaller confirmation started from the background on modern Android.
- Stuck installs after a committed install session when the Market UI was backgrounded.

## Tested behavior

The current build was validated with Xiaomi Market downloading and installing TikTok Lite/Douyin Lite. The failing path was:

1. Xiaomi Market finished the download.
2. Android blocked the install confirmation activity because Market was in the background.
3. Market stayed stuck at 100% / installing.

The module now defers the pending PackageInstaller intent until Market returns to the foreground, retries the committed-but-uninstalled task, and lets Market receive the normal install-finished callback.

## Requirements

- Android with LSPosed.
- Xiaomi Market installed as `com.xiaomi.market`.
- Enable this module for Xiaomi Market in LSPosed, then force stop or reboot before testing.

## Build

Use Android Gradle Plugin from this project with a local Gradle 8.2+ install:

```bash
gradle assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```
