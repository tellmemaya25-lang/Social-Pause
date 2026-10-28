# Social Pause

Social Pause is a planned standalone Android app for taking a break from selected apps' internet connectivity. See [PLAN.md](PLAN.md), [SYSTEM_DESIGN.md](SYSTEM_DESIGN.md), and [UI_UX_PLAN.md](UI_UX_PLAN.md).

## Current implementation

This is an experimental Android/Compose implementation with a launchable-app picker, local saved selections, duration selection, VPN consent, a local per-app IPv4/IPv6 drop tunnel, a foreground notification and session recovery. **It has not been built or tested on an Android device. Do not rely on it to block traffic until you have verified it.** VPN behavior, service type eligibility, isolation, battery use and release readiness remain unverified. Android backup is disabled for locally stored selections and session metadata.

The VPN may conflict with another VPN. Apps can still open and use offline content; calls and SMS are not blocked. VPN revocation, force-stop, reboot, process death or device policies can interrupt protection. Timed expiry is best-effort after interruptions and there is no automatic restart.

### Device testing checklist

Use test apps and controlled endpoints, not sensitive data. On a physical device, verify selected app TCP/UDP/DNS connectivity on IPv4 and IPv6 while an unselected app remains online, on both Wi-Fi and cellular where available. Test VPN consent denial and revocation, another VPN, start/edit/stop, expiry, notification permission denial, app removal, process death, reboot and force-stop. Check that interrupted sessions are never shown as active. Record any gaps and do not treat a successful VPN establishment as proof of isolation. Foreground-service type and Google Play eligibility require separate validation before distribution.

## Build

Open the repository in Android Studio with JDK 17 and Android SDK 35 installed, then sync Gradle and run the `app` configuration on an Android 7.0+ device. Alternatively run `./gradlew :app:assembleDebug` with JDK 17 and SDK 35 installed; the wrapper downloads Gradle 8.9. The app does not request broad package visibility, so some installed apps will not appear.
