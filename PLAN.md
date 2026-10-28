# Social Pause — build plan

Status: planning · 29 October 2026

## Product decisions

- Social Pause is a standalone Android app for personal use; no pricing or monetization is planned.
- Its purpose is to temporarily restrict internet access for user-selected apps, not to prevent opening them or accessing cached content.
- MVP selection is limited to discoverable launchable apps. Timed sessions last 1–720 minutes, with 15-, 30- and 60-minute presets; indefinite sessions are allowed.
- Do not automatically restart sessions after reboot or interruption. No accounts, server, packet inspection, app-list analytics, accessibility service, root, or task/habit integration.

## Starting point and constraints

This repository currently contains only a README, not the Daymark application described in the initial proposal. Create a new Kotlin/Jetpack Compose project rather than assuming existing architecture or assets. Target Android 7.0+ (API 24); select a current compile/target SDK and compatible dependency versions at implementation time. Google Play distribution has not been decided; if pursued, policy review and current target SDK requirements are release gates, not assumed approvals.

## Phase 1 — feasibility prototype

Build a small, local-only prototype before committing to the final architecture:

1. Create a minimal Android project and two controlled test apps or equivalent test fixtures. Define a reachable test endpoint and record the network and device setup.
2. Request VPN consent from a visible activity. Establish an IPv4 and IPv6 TUN with default routes and a nonempty per-app allowlist. Drain and discard packets locally; never inspect, forward, store or log them.
3. Test that selected apps fail TCP, UDP and DNS connectivity while a nonselected app succeeds on Wi-Fi and cellular, over available IPv4 and IPv6 networks. Check existing connections and network changes. Treat `establish()` as necessary but not proof of enforcement.
4. Validate service declaration, foreground-service type and permissions, notification behavior, package visibility, VPN conflicts and lifecycle behavior on representative devices. Record actual outcomes and known gaps, including policies and OEM differences.
5. Make a go/no-go decision. If isolation, safe lifecycle handling or necessary policy requirements cannot be demonstrated, document the gap rather than claiming the app works reliably.

## Phase 2 — foundation and local state

- Establish Compose navigation, theming, localization, Hilt, coroutines and tests in the new project. Keep Android APIs behind interfaces; add dependencies only when justified.
- Define session IDs, selected package names, start/end wall times, state and terminal reason in atomic local storage. Exclude this metadata from backup/cloud sync and do not send it to a server or analytics.
- Model Idle, AwaitingConsent, Starting, Active, Stopping, Completed, Interrupted and Error. Durable state is not proof of a live tunnel: reconcile it with service-owned live status on launch. An overdue inactive session becomes Completed; another stale active session becomes Interrupted.
- Use an elapsed-realtime deadline while a session is alive, with wall time for display and conservative recovery. Validate duration, selection, package identity and stale session IDs. Unit-test state transitions, recovery and clock changes.

## Phase 3 — VPN service and session management

- Start from a user-visible context after consent; promote promptly to a foreground service with a notification and a platform-appropriate, validated service type. Protect the VPN service with `BIND_VPN_SERVICE` and secure app-specific commands.
- Resolve every selected package, exclude Social Pause itself, and fail safely if any package is missing or cannot be added. Never establish with an empty effective allowlist or mix allowed and disallowed application lists.
- Serialize start, edit, stop, expiry and revocation. Own one TUN descriptor and bounded read/drop loop; close and clean up on every terminal path. Show Active only after successful establishment and a matching live service state.
- In-service monotonic timing handles expiry while alive. Reconcile later if the service is absent; timing after sleep, process death or force-stop is best-effort. Do not silently restart on reboot. Stop actions are idempotent and keyed by session ID.
- Rebuild on edit and explicitly show Starting; disclose the possible interruption. On failure or VPN revocation show Interrupted or Error, not Active. Handle package removal conservatively.

## Phase 4 — Compose experience

1. Explain the VPN purpose, another-VPN conflict, local data handling and limitations before the system consent dialog.
2. Provide an accessible searchable app picker with labels, icons, package-based identity, selection count and visibility/empty-state explanation. Load icons off the main thread; never include the app itself.
3. Offer presets, validated custom duration and indefinite mode; show an approximate end time where applicable.
4. Show starting, live active, stopped, completed, interrupted and error states distinctly. Include selected apps, end time/countdown, edit, confirmed stop, review/restart, and a way to clear saved selections.
5. Add an ongoing foreground notification with end time and a one-tap Stop action; opening it returns to the session screen. Explain notification permission denial without claiming a drawer notification is always visible.
6. Support TalkBack, keyboard navigation, 48 dp touch targets, scalable text, contrast and RTL; externalize user-visible strings.

## Phase 5 — hardening and personal-use readiness

- Unit-test validation, transitions, duplicate commands, stale IDs, atomic persistence and recovery. Add UI and instrumentation coverage for consent, selection, notification denial and interruption.
- Exercise revocation, competing VPNs, Doze, process death, reboot, force-stop, uninstall/reinstall, repeated edits, shared UIDs and profiles where feasible. Verify cleanup and that an unrelated app remains online.
- Measure picker render time, activation time and idle battery use; the original targets are goals, not guarantees. Record results and known limitations.
- Document setup, device results, limitations and privacy behavior. If Play distribution is later chosen, separately review VPN and foreground-service policies, declarations, privacy policy, Data safety and current SDK requirements before release.

## Proposed device matrix

Physical devices where available: API 24–25, 26–28, 29–32, 33–34 and 35 or current release; at least a Pixel and another OEM with restrictive battery management. Test Wi-Fi and cellular, IPv4/IPv6 availability, and work profile and always-on/lockdown configurations where feasible. This is a proposal, not completed testing; tailor it to the devices actually available.

## Open decisions before implementation

- Which physical Android devices and OS versions are available for testing?
- Is installation solely via local build/sideload, or might Play distribution be desired later?
- What visual direction and initial language(s) should the standalone app use?
- Are the original performance targets appropriate for the available reference device?

## Milestone exit criteria

The prototype has documented network isolation and limitations; core state and recovery tests pass; the app never shows protection solely from stored state; all MVP flows work on the agreed device matrix; and known gaps are documented. No broad claim of guaranteed blocking or exact expiration is made.
