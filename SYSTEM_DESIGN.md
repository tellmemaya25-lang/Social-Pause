# Social Pause — requirements and system design

**Status:** Proposed; not implemented or validated · **Date:** 29 October 2026

## 1. Context and objectives

Social Pause is a standalone Android app for personal use. It lets a person temporarily restrict internet connectivity for selected apps while intending to leave other apps connected. It does not prevent opening apps or accessing offline content. There is no pricing, account, server, remote VPN endpoint, accessibility service or root requirement. Play distribution is undecided.

The repository initially contained only a README; there is no existing Android app or Daymark architecture to reuse. This design is a starting point, not evidence that Android routing or every device behaves as intended. Build and test a prototype before treating the design as feasible.

### Scope

**MVP:** discoverable launchable app picker; timed and indefinite sessions; Android VPN consent; a local per-app drop tunnel; session status, editing and stopping; local recovery; and a foreground notification.

**Not in MVP:** blocking app launch, website filtering, schedules, statistics, account sync, remote management, parental controls, tamper resistance, ads, installed-app analytics, packet inspection, task/habit integration or automatic restoration after interruption or reboot.

## 2. Requirements

### Functional

| ID | Requirement | Acceptance condition |
|---|---|---|
| R1 | Select apps | Show discoverable launchable apps with icon, label, package-based identity, search and multi-selection. Exclude Social Pause. Explain that some installed apps may be missing. Never require broad package visibility by default. |
| R2 | Configure duration | Offer 15, 30 and 60 minutes, custom integer minutes from 1 through 720, and indefinite. Require at least one app. Show an approximate wall-clock end when timed. |
| R3 | Consent and start | Explain purpose, privacy, limitations and VPN conflict before Android consent. Do not start after denial. Display Starting until establishment succeeds and live service status matches the session. |
| R4 | Restrict traffic | For the effective selected apps, route IPv4 and IPv6 through a local TUN and discard packets. Nonselected apps should retain connectivity; validate this on physical devices rather than assuming it. Fail if any selected package cannot be added; never establish an empty allowlist. |
| R5 | Manage session | Show selected apps, start and optional end time, and actual status. Stop from the screen requires confirmation; notification Stop is one tap. Editing rebuilds the tunnel and warns of a possible interruption. |
| R6 | Expire | A live service stops at its monotonic deadline. Reconcile expired records on later launch; timing after process death, sleep or force-stop is best-effort, not exact. |
| R7 | Notification | Provide an ongoing foreground notification with session state, end time if any, screen entry and Stop action. Account for API 33+ notification denial without claiming the drawer notification is always visible. |
| R8 | Recover honestly | Distinguish completed, interrupted and error from active. Reconcile stale records after process death or reboot; never automatically reestablish protection. Offer user-initiated review/restart. |
| R9 | Local data | Keep selections and session metadata locally, excluded from cloud backup and analytics. Provide a way to clear saved selections. Do not transmit app lists or packet data. |
| R10 | Explain limits | State that opening apps, cached content, calls/SMS and all distractions are not blocked. Explain Android routing, VPN conflicts and that protection can stop. |

### Nonfunctional

- **Privacy:** Do not parse, forward, log, store or transmit packet contents. Do not log package lists. This local-only feature does not imply any unrelated app or SDK is data-free.
- **Security:** Validate commands, session IDs, durations and packages; restrict app-owned components and use the Android-required VPN binding permission. Do not expose app command interfaces to other apps.
- **Accessibility:** TalkBack labels and state announcements, 48 dp targets, keyboard navigation, scalable text, contrast, localized strings and RTL.
- **Compatibility:** Android 7.0+ (API 24), subject to device testing. Select current compile/target SDK and compatible dependencies when implementation starts.
- **Measurement goals, not guarantees:** picker first usable render under 2 seconds for 200 visible apps; activation under 3 seconds after consent; idle overhead under 2% battery over 8 hours on an agreed reference device.

## 3. High-level design

```text
Android activity / Compose screens
    │ UI actions                            ▲ state
    ▼                                       │
FocusViewModel ────────────────► SessionCoordinator
                                      │
                    ┌─────────────────┼─────────────────┐
                    ▼                 ▼                 ▼
              PackageCatalog   SessionRepository   VpnController
              PackageManager   local atomic store   user-visible start/commands
                                                        │
                                                        ▼
                                             PauseVpnService (foreground)
                                               ├─ serialized session lifecycle
                                               ├─ VpnService.Builder / TUN
                                               ├─ read and discard loop
                                               ├─ monotonic deadline
                                               └─ notification / live state
```

The UI observes durable metadata **and** a service-owned live status. The repository records intent and history; it never proves a tunnel is alive. The service owns the TUN descriptor and timer, and is the only owner that can report a session Active. Keep feature data isolated from any future network, analytics or cloud repository.

### Trust and platform boundaries

- The user authorizes Android VPN consent. A VPN may displace or be blocked by another VPN; always-on/lockdown and device policies may behave differently.
- The Android system binds to the VPN service through `BIND_VPN_SERVICE`; app-specific actions must remain protected even if the service needs to be exported for system integration. Validate the manifest against current Android documentation and devices.
- The tunnel has no upstream connection: selected traffic is discarded locally. Android routes, shared UIDs, other profiles, existing sockets, DNS and OEM behavior can produce gaps. Never present this as guaranteed fail-closed protection.
- Only one VPN is normally active per user/profile. Reboot, force-stop, revocation, another VPN or service failure can stop protection. No covert restart.

## 4. Low-level design

### 4.1 Components and contracts

Names below are proposed, not existing APIs.

| Component | Responsibility | Key operations / outputs |
|---|---|---|
| `FocusViewModel` | Derive accessible screen state and dispatch user actions; no direct TUN ownership. | `StateFlow<FocusUiState>`, select, start, edit, stop, clear. |
| `SessionCoordinator` | Validate requests, coordinate persistence and service commands, and reconcile stored and live states. | `start`, `edit`, `stop`, `reconcile`, `clearSelection`. |
| `PackageCatalog` | Query visible launcher activities, deduplicate by package, exclude own package, load/cache icons off main thread. | `listLaunchableApps`, `resolvePackages`. Labels are display values, not identifiers. |
| `SessionRepository` | Atomic local persistence of versioned session and saved selections. | observe/read/update, clear selections; no cloud sync. |
| `VpnController` | Prepare consent and send explicit, validated service commands from eligible contexts. | prepare consent, start/edit/stop with session ID, observe live state. |
| `PauseVpnService` | Own foreground lifecycle, descriptor, read loop, monotonic timer, terminal cleanup and live state. | start/rebuild/stop/revoke; serialize all transitions. |
| `Clock` | Supply elapsed and wall time separately for deterministic tests. | `elapsedRealtime`, `wallTime`. |

Use a single source of truth for each responsibility. Inject interfaces with Hilt where useful; use coroutines/StateFlow for asynchronous state. Ensure service status is genuinely tied to the currently running service instance, not restored from disk. If the activity and service share a process, an in-process state holder may provide this signal; its initial state must be inactive on every process start. A future separate process would require an authenticated IPC design.

### 4.2 Session record

Persist atomically, for example in DataStore, with a versioned schema:

```text
SessionRecord {
  schemaVersion: Int
  sessionId: UUID
  selectedPackages: Set<String>
  startedAtWallMillis: Long
  endsAtWallMillis: Long?       // null = indefinite
  storedStatus: Starting | Active | Completed | Interrupted | Error
  terminalReason: Reason?       // e.g. user stop, expiry, revoke, failure, unknown
}
SavedSelection { packages: Set<String> }
```

No packet bytes or app labels are required in the record. The live service additionally holds an optional `elapsedRealtime` deadline, TUN descriptor and live session ID in memory; the elapsed deadline is not interpreted across reboot. A manually stopped session may be represented as a terminal record with reason `user stop` and mapped to Stopped in the UI. Clear-selection behavior clears saved choices independently of an active session; it must not silently stop or change the active tunnel. Make this distinction clear in the UI.

### 4.3 State and transitions

| From | Event | To | Notes |
|---|---|---|---|
| Idle / terminal | Valid start request | AwaitingConsent or Starting | Prepare VPN in activity; denied consent returns to a non-active state with explanation. |
| AwaitingConsent | Consent granted | Starting | Start only from a permitted visible context. |
| Starting | All packages added and TUN established | Active | Publish matching live ID only after establishment; durable Active alone never drives the badge. |
| Starting | Validation, foreground or establishment failure | Error / Interrupted | Close resources, persist reason where possible and expose an actionable message. |
| Active | Edit | Starting | Serialize, close old tunnel and establish new configuration; protection can briefly stop. Failure is not Active. |
| Active / Starting | Stop | Stopping → stopped | Idempotent; close descriptor, cancel jobs, persist terminal state and remove foreground notification. |
| Active | Deadline | Stopping → Completed | Use elapsed time while the service lives. |
| Active / Starting | Revoke, I/O error or service failure | Interrupted / Error | Clean up where possible; next launch reconciles if process dies before persistence. |
| Stored Starting / Active, no matching live service | Reconcile | Completed if overdue, otherwise Interrupted | Do not start VPN in background. |

Consent is platform-owned and may be revoked at any time. User-visible state should distinguish known terminal reasons from an unknown interruption, and must not infer revocation as the cause of every process death.

### 4.4 Start and edit sequence

1. UI checks a nonempty selection and duration bounds; coordinator revalidates. Generate a fresh session ID for a new session and capture wall and elapsed times. On edit, define the new duration from the edit confirmation, update the deadline and issue an ID-scoped rebuild.
2. Explain conflict and limitations; invoke `VpnService.prepare()` and process the activity result. If consent is denied, do not start the service.
3. Persist Starting atomically. Start the foreground service from a permitted user-visible context. Service immediately promotes with a Starting notification, handling failures without claiming Active.
4. Under serialized lifecycle control, resolve every package again, reject own package or missing packages, and add each with `addAllowedApplication`. Configure tunnel IPv4/IPv6 addresses and default routes; do not call `establish()` with an empty effective allowlist.
5. Establish the descriptor and launch one bounded read/discard loop. On success publish matching live Active state, persist metadata and update the notification. Reconcile if persistence or startup fails. Descriptor closure must unblock the read loop; test this on devices.
6. For timed sessions, calculate the in-process elapsed deadline and schedule a coroutine that rechecks elapsed time on wake. On edit, invalidate old jobs and cleanly replace the descriptor. Failure leaves the session interrupted/error, not partially protected.

A serialization strategy must not block stop or expiry forever on a blocking TUN read: keep reads outside the command lock and close the descriptor during cleanup. Guard callbacks with session ID and, for rebuilds within one session, a generation token so an old reader or timer cannot stop a new tunnel.

### 4.5 Stop, expiration and recovery

- **Stop:** Validate session ID; ignore stale notification actions. Serialize cleanup, invalidate the generation, close the descriptor, cancel timer/reader, update live status, persist terminal reason and remove foreground state. Repeated stop is safe.
- **Expiry:** The service's timer compares elapsed time, then follows the same cleanup path. The wall end is for display and recovery, not a live countdown source.
- **Revoke/failure:** `onRevoke()` and read EOF/errors trigger serialized cleanup. A killed process may not execute cleanup callbacks; the OS closes its descriptor, and the next UI launch reconciles stored Starting/Active against missing live status.
- **Relaunch/reboot:** If no matching live tunnel exists, mark an overdue timed session Completed, otherwise Interrupted. Wall-clock changes can make recovery approximate. Require an explicit user action and fresh platform permission check to restart.
- **Force-stop:** Android suppresses background execution until the user opens the app. Do not promise timely notification or expiry while stopped.
- **Package changes:** On detection or rebuild, validate every package. If a selected package is removed or no longer resolvable, stop conservatively and explain; never silently narrow or broaden the selection.

### 4.6 Notification and platform configuration

- Declare launcher-intent package queries, without `QUERY_ALL_PACKAGES` by default. Expect hidden/non-launchable/other-profile apps to be missing.
- Declare the VPN service with the required system binding permission and VPN intent filter; verify export behavior against supported versions. Restrict any additional command receivers and use explicit immutable PendingIntents with session IDs and distinct identities so stale actions cannot affect a later session.
- Create a notification channel and request notification permission where appropriate. Android may permit a foreground service when notification permission is denied, but visibility differs.
- Android 14+ requires a suitable foreground-service type and corresponding permissions. Android documentation lists configured VPN apps under `systemExempted` eligibility and describes `specialUse`; validate eligibility, actual runtime behavior and any Play declarations for this app before finalizing. Never assume an exemption or use a type without meeting its prerequisites.
- If Play distribution is chosen, separately review VPN policy eligibility for a local-only drop tunnel, listing disclosure, Data safety, foreground-service declarations and current target requirements. Personal sideload use does not establish Play eligibility.

### 4.7 UX states

- **Introduction:** VPN purpose, local packet handling, conflict notice, limitations and entry to selection.
- **Picker:** search, checkboxes, selected count, empty/unavailable explanation and disabled Continue when empty.
- **Duration/consent:** presets, valid custom minutes, indefinite, approximate end time, disclosure and Android dialog. Consent denial provides a retry path.
- **Session:** live Starting/Active/Stopping state, app list, countdown or indefinite label, edit and Stop. Never imply app launch is blocked.
- **Terminal:** Completed, stopped, interrupted or error; known/unknown reason, review and user-initiated restart. Notification denial and competing VPNs get actionable explanations where detectable.

## 5. Delivery plan and dependencies

| Phase | Deliverables | Exit gate |
|---|---|---|
| 0. Decisions | Confirm available devices, distribution, visual direction and languages. | Agreed test environment and scope. |
| 1. Prototype | Minimal standalone project, VPN consent, per-app dual-stack drop tunnel and controlled network test setup. | Record selected/nonselected behavior and conflicts on physical devices; decide whether to proceed. |
| 2. Domain and persistence | Models, validation, state machine, clock abstraction, atomic local storage and unit tests. | Recovery and transition tests pass; no cloud data path. |
| 3. VPN lifecycle | Serialized foreground service, tunnel, deadlines, stop/revoke/error cleanup and notifications. | Lifecycle and race tests pass; no false Active from persisted state. |
| 4. Compose UX | Explanation, picker, duration, consent, active/edit/terminal screens and accessibility. | Core user flows and permission-denial states verified. |
| 5. Hardening | Device matrix, network/lifecycle testing, performance measurements, documentation and privacy review. | Known gaps documented and agreed personal-use acceptance met. |
| 6. Distribution, if desired | Build/install instructions; separate Play compliance review if applicable. | No distribution claims beyond validated results and applicable policy review. |

The prototype and platform-policy investigation precede final service decisions. Session contracts precede UX integration. Physical-device testing precedes claims of successful enforcement. No timeline is committed before a prototype and device inventory.

## 6. Verification plan

- **Unit:** duration boundaries and indefinite mode; monotonic versus wall clock; transitions; duplicate start/stop/edit; stale session IDs/generations; validation of unavailable and own packages; atomic persistence; interrupted and overdue recovery.
- **UI/accessibility:** consent accepted/denied, empty picker, search and duplicate labels, selection, countdown, edit interruption, errors, notification denial, large text, TalkBack, keyboard and RTL.
- **Network on physical devices:** controlled test apps and endpoints for TCP/UDP/DNS over available IPv4/IPv6, Wi-Fi and cellular; selected traffic fails and nonselected traffic succeeds. Test pre-existing connections, network changes, competing VPN, lockdown/always-on, work profiles, shared UIDs and package reinstall where feasible. Record observations rather than interpreting a successful establish as proof.
- **Lifecycle:** screen off, Doze, expiry, killed service/process, reboot, force-stop, revocation, app update, repeated edits and races. Check descriptor and notification cleanup, and confirm a stored Active record never produces a live Active badge by itself.
- **Performance/privacy:** measure the stated goals on a named reference device. Check that app-list metadata is excluded from backup and not transmitted; inspect logs and dependencies for accidental telemetry.

**Proposed matrix:** physical API 24–25, 26–28, 29–32, 33–34 and 35/current; a Pixel and at least one other OEM with aggressive battery management; Wi-Fi/cellular and both address families where available. This matrix is proposed, not tested, and should be adjusted to available hardware.

## 7. Risks and unresolved decisions

| Risk / question | Mitigation or decision needed |
|---|---|
| Per-app isolation, DNS, existing sockets, shared UIDs and profiles vary by device | Prototype and record actual network results; do not guarantee all paths are blocked. |
| VPN conflict, revocation or OEM process killing stops protection | Explain limitations, show live state, reconcile honestly and require manual restart. |
| Package visibility omits apps | Explain scope and empty states; broaden only with a separate justification and policy review. |
| Indefinite sessions and app retries may consume battery | Measure on devices; keep an explicit Stop path. |
| Play eligibility of local-only drop tunnel is uncertain | Treat as a distribution gate, not as approval; assess current policy if Play is pursued. |
| Personal devices, languages, visual direction and distribution are unspecified | Confirm before finalizing acceptance matrix and UI. |
| Wall clock changes during process absence | Treat recovery timing as approximate; use elapsed time while live. |

## 8. References to verify at implementation/release

- Android VPN guide: https://developer.android.com/develop/connectivity/vpn
- Android foreground services and types: https://developer.android.com/develop/background-work/services/fgs and https://developer.android.com/about/versions/14/changes/fgs-types-required
- Android package visibility: https://developer.android.com/training/package-visibility
- Google Play VPN policy: https://support.google.com/googleplay/android-developer/answer/12564964
