<p align="center">
  <img src="logo.png" width="180" alt="TabVision YT Logo">
</p>

<p>
  <a href="https://visitorbadge.io/status?path=https%3A%2F%2Fgithub.com%2Fpbzin%2FXiaomiMarketBypass"><img src="https://api.visitorbadge.io/api/visitors?path=https%3A%2F%2Fgithub.com%2Fpbzin%2FXiaomiMarketBypass&label=repo%20views&countColor=%230e75b6&style=flat" alt="Repo Views"></a>
  <a href="https://github.com/Xposed-Modules-Repo/com.pb.xiaomimarketbypass"><img src="https://api.visitorbadge.io/api/visitors?path=https%3A%2F%2Fgithub.com%2FXposed-Modules-Repo%2Fcom.pb.xiaomimarketbypass&label=LSPosed%20repo%20views&countColor=%230e75b6&style=flat" alt="LSPosed Repo Views"></a>
</p>
<p>
  <a href="https://github.com/pbzin/XiaomiMarketBypass/releases"><img src="https://img.shields.io/github/downloads/pbzin/XiaomiMarketBypass/total?style=flat&color=0e75b6&label=downloads" alt="Downloads"></a>
  <a href="https://github.com/Xposed-Modules-Repo/com.pb.xiaomimarketbypass/releases"><img src="https://img.shields.io/github/downloads/Xposed-Modules-Repo/com.pb.xiaomimarketbypass/total?style=flat&color=0e75b6&label=LSPosed%20downloads" alt="LSPosed Downloads"></a>
</p>

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

The current build was validated with Xiaomi Market **v4.111.0** (recommended version) downloading and installing TikTok Lite/Douyin Lite. The failing path was:

1. Xiaomi Market finished the download.
2. Android blocked the install confirmation activity because Market was in the background.
3. Market stayed stuck at 100% / installing.

The module now defers the pending PackageInstaller intent until Market returns to the foreground, retries the committed-but-uninstalled task, and lets Market receive the normal install-finished callback.

## Requirements

- Android with LSPosed.
- Xiaomi Market installed as `com.xiaomi.market` (v4.111.0 recommended).
- Enable this module for both `com.xiaomi.market` and `com.android.providers.downloads` in LSPosed, then force stop the Market and DownloadProvider or reboot before testing.

The second scope is required for pause and resume because the AOSP DownloadProvider on this ROM does not schedule a paused download again when Xiaomi Market resumes it.

## Download

[![Download](https://img.shields.io/badge/Download-Xiaomi%20Market%20Bypass-orange?style=for-the-badge&logo=github)](https://github.com/pbzin/XiaomiMarketBypass/releases)

### 💖 Support My Work

<p align="center">
  <a href="https://buymeacoffee.com/pbzin">
    <img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" height="38" align="absmiddle">
  </a>
  <a href="https://github.com/sponsors/pbzin">
    <img src="https://img.shields.io/badge/Sponsor-%F0%9F%92%96-ea4aaa?style=for-the-badge&logo=githubsponsors&logoColor=white" alt="GitHub Sponsors" height="38" align="absmiddle">
  </a>
  <br><br>
  <img src="https://img.shields.io/badge/Pix-%E2%9A%A1-32BCAD?style=for-the-badge&logo=pix&logoColor=white" alt="Pix" height="30" align="absmiddle">
  <img src="https://raw.githubusercontent.com/pbzin/pbzin/main/assets/brasil-badge.png" alt="Brasil" height="30" align="absmiddle">
  <br>
  <code>5198a8b3-6b89-4475-aec1-5adcfcfd12cf</code>
  <br><br>
  <img src="https://img.shields.io/badge/Bitcoin-F7931A?style=for-the-badge&logo=bitcoin&logoColor=white" alt="Bitcoin" height="30" align="absmiddle">
  <br>
  <code>1GkpDZDHYov7WZLs54Nv19f2KUoZPcACs2</code>
  <br>
  <img src="https://raw.githubusercontent.com/pbzin/pbzin/main/assets/bitcoin-qr.png" width="150" alt="Bitcoin donation QR code">
  <br><br>
  <img src="https://img.shields.io/badge/Monero-FF6600?style=for-the-badge&logo=monero&logoColor=white" alt="Monero" height="30" align="absmiddle">
  <br>
  <code>45YtYmxUeXeFdokKPG1KWtMFLByS8nwmtiJjEiZ9LfbkNaSUCvyWWAx3VmtDKKkxPJFdQLSXxodRWMt7EBu5TmA3Qi9dgwT</code>
  <br>
  <img src="https://raw.githubusercontent.com/pbzin/pbzin/main/assets/monero-qr.png" width="150" alt="Monero donation QR code">
</p>
