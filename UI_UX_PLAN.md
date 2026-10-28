# Social Pause — UI/UX design plan

**Status:** Proposed · 29 October 2026  
**Product:** Standalone Android app for personal use · **Implementation:** Not started

## 1. Design intent

Help a person deliberately take a break from selected apps' internet connectivity, understand when protection is actually running, and regain control quickly. The app should feel calm and straightforward, not punitive or like a parental-control tool.

**Core principles**
- **Honest status:** Only call a session Active when the running service reports a matching established tunnel. Establishment is not a guarantee that every network path is blocked.
- **Informed choice:** Explain VPN consent, possible conflicts and limitations before the Android dialog. Do not imply apps cannot be opened or that offline content, calls, SMS or notifications are blocked.
- **User control:** Start and restart are explicit. The screen Stop action requires confirmation; the notification Stop action is one tap. No automatic restart after reboot or interruption.
- **Privacy by design:** Explain that packets are discarded locally and selected package names/session metadata are stored locally, not sent by this feature. Do not present local storage as a guarantee of security or imply unrelated software collects no data.
- **Accessible by default:** Clear language, visual and nonvisual status, keyboard and TalkBack support, scalable text and RTL.

## 2. Navigation and information architecture

```text
Home / session status
  ├─ Start or Review → App picker → Duration & review → VPN explanation/consent → Starting → Active
  ├─ Active → Edit → App picker → Duration & review → Rebuilding → Active or interrupted/error
  ├─ Active → Stop confirmation → Stopped
  ├─ Interrupted / error / completed → Review and restart
  └─ Settings & information → Privacy & limitations / Clear saved selections

Ongoing notification → Home / session status
Notification Stop → Stopped (no confirmation)
```

Use a single-activity Compose layout with a clear back path; avoid a bottom navigation bar for this small MVP. Android's system VPN consent is a platform dialog, not a custom imitation. Notification permission, if requested, uses the system prompt separately and is not represented as a prerequisite for VPN consent.

## 3. Screen specifications

### 3.1 Home and introduction

**Idle:** App title, a short description, a prominent **Choose apps** action and a small **How it works** link. Suggested copy: “Take a break from the internet in apps you choose. You can still open them and use offline content.”

**How it works / pre-consent disclosure:** Explain that Social Pause uses Android's VPN to route selected apps' traffic into a local drop tunnel; no remote VPN server is used. Packets are discarded on the device and selection/session metadata stays local to this feature. Another VPN can be replaced or prevent this one from starting. Android routing and device behavior can affect coverage. Protection can stop, and the app does not automatically restart it. Provide **Continue** and **Not now**.

**Returning user:** Show the most relevant session card instead of a generic start card. Saved selections can prepopulate the picker, but never start a session without an explicit action.

### 3.2 App picker

- Search field with accessible label **Search apps**; match app labels and package names without relying on unique labels.
- Scrollable list of discoverable launchable apps, showing icon, label and checkbox. Include package name where labels collide or in accessible detail. Do not list Social Pause itself.
- Selection count and **Continue**; disabled for zero selections with an adjacent explanation. Tapping a row or its checkbox toggles selection once.
- Persistent short note: “Some installed apps may not appear because of Android's app visibility rules.”
- Empty catalog: explain the visibility limitation and offer Back/Retry. No search results: distinguish from an empty catalog and offer Clear search.
- Load catalog and icons asynchronously; show a loading state without confusing it with an empty list. Preserve selection and search through rotation or ordinary UI recreation.

### 3.3 Duration and review

- Show selected app count with **Edit apps**; summarize selected labels and allow expansion when many are selected.
- Single-choice options: **15 minutes**, **30 minutes**, **60 minutes**, **Custom**, **Until I stop it**. For custom, accept integer minutes 1–720 and show an inline error outside the range or for invalid input. Do not silently clamp values.
- For timed sessions, show an approximate end time with an indication that system clock changes and interruptions affect recovery. For indefinite, show “Runs until stopped or interrupted.”
- Show an explicit reminder: “Apps can still open and use offline content.”
- Primary **Start pause** opens the pre-consent explanation if not already shown in this flow, then Android's system consent if required. A denial returns to review with a clear retry option; it never displays Active.

### 3.4 Starting and active

**Starting / rebuilding:** Show a progress indicator, “Starting protection…” or “Updating protection…”, and a short explanation that selected apps may briefly reconnect during an edit. Do not show an Active badge while rebuilding. Provide Stop/cancel if the implementation can safely serialize it; otherwise describe the temporary state without promising cancellation.

**Active:** Show a text-and-icon status **Active**, selected app count/list, start time, and either remaining time plus approximate end time or **Until stopped**. The live service is the source for Active; do not derive it solely from persisted metadata. Countdown uses the service's monotonic deadline while alive; displayed wall end time is approximate.

Provide **Edit session** and **Stop**. Edit carries current selections and configuration into the picker/review flow. Explain that applying changes rebuilds the VPN and can briefly interrupt protection. Define a changed timed duration as starting from confirmation of the edit. A canceled edit leaves the existing session unchanged.

### 3.5 Stop confirmation

Screen action opens a confirmation dialog: “Stop this pause? Selected apps may reconnect to the internet.” Actions: **Keep pausing** and **Stop pause**. Announce the resulting state and return to the status screen. Repeated requests are safe. Notification Stop bypasses this dialog, as explicitly intended.

### 3.6 Completed, stopped, interrupted and error

Use distinct text and iconography, not color alone:

| State | Suggested message | Actions |
|---|---|---|
| Completed | “Your timed pause has ended.” | Review and start again; Home |
| Stopped | “Your pause is off.” | Review and start again; Home |
| Interrupted | “Protection stopped. Your selected apps may be online.” Include a known cause only when verified; otherwise say the cause is unknown. | Review and restart; Home |
| Error | “Couldn't start protection.” Give a specific actionable reason when known, such as an app no longer being available. | Review apps / Try again |

Review and restart opens configuration and requires explicit Start and platform consent if needed. Do not automatically restart after process death, reboot or another VPN taking over. Do not imply that an overdue record ended at an exact time while the service was absent.

### 3.7 Settings, data and limitations

A compact information screen contains:
- **Privacy:** selection and session metadata are kept locally; this feature does not transmit app lists or packets. Packets routed to the tunnel are discarded locally. Explain that the app doesn't inspect traffic.
- **Limitations:** apps can open and use offline content; Android routing and profiles can affect protection; calls/SMS are not blocked; notification or message-delivery behavior is not guaranteed; another VPN and system/device policies can interrupt it.
- **Clear saved selections:** confirmation explains it removes saved picker choices only, not an active session or its required metadata. It does not silently stop or edit a running VPN. After clearing, show confirmation. If history deletion is later offered, define it separately.

### 3.8 Foreground notification

- Channel: a clear, user-facing session/protection channel. Notification displays Starting or Active and approximate end time for timed sessions, or **Until stopped** for indefinite sessions.
- Tapping the notification opens the session screen; **Stop** immediately sends a session-specific action. Stale notification actions must not stop a newer session.
- On Android 13+, if notifications are denied, explain that the service may still run but the drawer notification may not be visible; point to system settings where appropriate. Do not promise visibility in the drawer.
- Remove the ongoing notification when service protection ends. An optional completion notification is not required for MVP.

## 4. Key flow and edge-case decisions

| Situation | UX behavior |
|---|---|
| First use | Introduction → picker → duration/review → VPN disclosure → system consent → Starting → Active or actionable failure. |
| No selection | Continue disabled; explain selection is required. |
| App removed or unavailable | Show which selection needs review when known; do not silently continue with a partial set or establish an empty allowlist. |
| Consent denied | Explain that the pause did not start; offer retry and Back. |
| Competing VPN / policy conflict | Warn before start; on failure or interruption explain only what is actually known. Offer review/retry. |
| Edit fails | Show interrupted/error, not Active. Preserve the draft where practical for review. |
| Process death or reboot | On next launch reconcile saved status with live service status; show Completed if overdue, otherwise Interrupted. Never silently restart. |
| Force-stop | No background operation is promised until the user opens the app; reconcile then. |
| Device clock changes | Live countdown follows monotonic time; displayed end and post-restart recovery are approximate. |
| Notification permission denied | Explain visibility differences; in-app status remains available and must reflect live service state. |
| Clear selections while active | Clear saved picker choices only; current protection is unchanged until explicitly edited or stopped. |

## 5. Visual and interaction direction

**Proposed, awaiting approval:** calm, minimal Material 3-inspired design with generous spacing, a neutral background, one restrained accent and clear typography. Use original assets and wording; do not reuse competitor branding, icons or screenshots. Status indicators pair a label with an icon and sufficient contrast; avoid red/green-only meaning. Support light and dark themes if they can both meet contrast requirements. Favor familiar Android controls and a single primary action per screen.

A suggested hierarchy: app title → state and short explanation → primary action → secondary details → limitations. For long app lists use virtualization and reserve icon space while icons load. On larger screens use a centered readable content width; on small screens allow scrolling and keep essential actions reachable without hiding system UI.

## 6. Accessibility and localization

- All interactive elements have meaningful labels, role and state; list rows expose checked state without duplicate TalkBack announcements. Decorative icons have no redundant description.
- Announce state transitions and inline errors without repeatedly interrupting the user; focus the relevant field after invalid custom duration.
- Minimum 48 dp touch targets; logical keyboard/tab order, visible focus and usable back navigation.
- Support large font sizes, multiline labels, long translations and RTL layouts. Do not rely on fixed-height cards, color alone, or icon-only controls without descriptions.
- Store all user-facing copy in localized resources; use locale-aware numbers and times. Initial languages remain to be decided.

## 7. Design deliverables and implementation order

1. **Agree direction:** approve the navigation, copy tone, proposed visual style, initial languages and target devices.
2. **Wireframes:** produce low-fidelity layouts for idle, picker, duration/review, disclosure, Starting, Active, edit/stop, and each terminal/permission state. Include small-screen, large-text and RTL variants.
3. **Interactive prototype:** validate start, edit, stop, denial and interruption paths; use simulated states rather than implying VPN behavior is already working.
4. **Design tokens and components:** define typography, colors/contrast, spacing, buttons, status cards, app rows, dialogs, error and loading treatments in Compose.
5. **Implementation handoff:** connect screens to state and events defined in `SYSTEM_DESIGN.md`; keep VPN consent and live service state separate from display-only mock states.
6. **Review and iterate:** test accessibility and actual Android permission/notification flows on the agreed devices, then revise copy based on observed behavior.

## 8. UX acceptance checklist

- [ ] A new user can explain what the pause does and at least two key limitations before consent.
- [ ] The picker distinguishes loading, no discoverable apps and no search results; zero selection cannot proceed.
- [ ] Duration validation covers 1 and 720 minutes, invalid values and indefinite mode.
- [ ] Consent denial never presents Active; Starting, Active, Completed, Stopped, Interrupted and Error are distinguishable by text.
- [ ] Active is sourced from a matching live service, not stored state alone; edit makes the possible protection gap clear.
- [ ] Screen Stop requires confirmation; notification Stop is one tap and ignores stale session actions.
- [ ] Recovery never implies automatic protection after service death or reboot; restart requires a user action.
- [ ] Notification denial is explained accurately; clear saved selections does not alter a running session.
- [ ] Core flows work with TalkBack, keyboard, large text and RTL; touch targets and contrast are checked.
- [ ] Wording and behavior are checked against physical-device findings before release.

## 9. Open questions

- Approve or revise the proposed calm Material 3 visual direction and light/dark themes?
- Which language(s) should be included initially?
- Which phone sizes, Android versions and devices are available for design and usability testing?
- Should saved selections prepopulate the next session by default? This plan proposes yes, with an explicit clear action and no automatic start.
