# Can an Android app capture other apps' screens from a floating overlay? (Android 14/15/16/17, as of Nov 2026)

Scope: a Kotlin + Jetpack Compose app ("print-book") that shows a floating button over reading apps (Kindle, Kobo, Google Play Books, Skeelo, and others) and captures their pages. Only non-root, Play-Store-compatible approaches are covered.

Research note: a Firecrawl credit cap was hit partway through. The remaining research used WebSearch and WebFetch, and several app-specific questions stayed unanswered (see each Gaps section).

---

## 1. MediaProjection API: requirements and limits on Android 14/15/16

### Takeaway
MediaProjection is still the standard Play-compatible way to capture other apps. It needs:
- a foreground service of type `mediaProjection`
- the `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission
- user consent through a system dialog **for every session** (Android 14+)

Each consent yields one token, and each token allows one `createVirtualDisplay()` call. Within that one session you can still take many still frames with `ImageReader`, so a single consent can cover a whole reading session of many pages. Android 15 QPR1+ adds a large status-bar chip, and capture stops automatically when the screen locks.

### Cited Findings
- **Manifest:** you need `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PROJECTION`, plus a `<service android:foregroundServiceType="mediaProjection">` declaration. — [Android Developers: Media projection](https://developer.android.com/media/grow/media-projection)
- **Missing permission crash:** on targetSdk 34, calling `startForeground` without `FOREGROUND_SERVICE_MEDIA_PROJECTION` throws `SecurityException: Starting FGS with type mediaProjection ... requires permissions ... [android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION] ... any of [CAPTURE_VIDEO_OUTPUT, android:project_media]`. The service type must also be passed to `startForeground(id, notif, FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)`. — [Stack Overflow 77307867](https://stackoverflow.com/questions/77307867/screen-capture-mediaprojection-on-android-14)
- **Consent per session (official):** "Your app must request user consent before each media projection session. A session is a single call to `createVirtualDisplay()`. A `MediaProjection` token must be used only once to make the call." — [Android Developers: Media projection](https://developer.android.com/media/grow/media-projection)
- **Token reuse:** on Android 14+, `createVirtualDisplay()` throws `SecurityException` in either of these cases — [Android Developers: Media projection](https://developer.android.com/media/grow/media-projection):
  - the app passes the `Intent` from `createScreenCaptureIntent()` to `getMediaProjection()` more than once
  - the app calls `createVirtualDisplay()` more than once on the same `MediaProjection`
- **Callback requirement:** the app must register the `MediaProjection.Callback` (`onStop()`). "If your app doesn't register the callback, any call to `createVirtualDisplay()` throws `IllegalStateException`." — [Android Developers: Media projection](https://developer.android.com/media/grow/media-projection)
- **App screen sharing (single app):** "Android 14 (API level 34) introduces app screen sharing, which enables users to share a single app window instead of the entire device screen... excludes the status bar, navigation bar, notifications, and other system UI elements... Only the contents of the selected app are shared." — [Android Developers: Media projection](https://developer.android.com/media/grow/media-projection)
  - A secondary source places user-selectable partial capture at Android 14 QPR2+. — [Fora Soft 2026](https://www.forasoft.com/blog/article/screen-sharing-android-1644)
- **Resize and visibility callbacks:**
  - `onCapturedContentResize()` fires whenever the captured content changes size (rotation, windowing mode). Use it to resize the VirtualDisplay and Surface so the capture is not letterboxed.
  - `onCapturedContentVisibilityChanged()` reports whether the captured region is visible to the user.
  - Source: [Android Developers: Media projection](https://developer.android.com/media/grow/media-projection)
- **ImageReader:** the app supplies the `Surface` through `MediaRecorder`, `SurfaceTexture` or `ImageReader`. These consume the captured display and let the app manage the frames in real time. — [Android Developers: Media projection](https://developer.android.com/media/grow/media-projection)
- **Surface size:** "Make the surface large (low resolution) for screen casting to TVs... and small (high resolution) for device display recording." — [Android Developers: Media projection](https://developer.android.com/media/grow/media-projection)
- **Status bar chip and auto-stop (Android 15 QPR1+):** "a status bar chip that is large and prominent alerts users to any in-progress screen projection. Users can tap the chip to stop... Also, screen projection automatically stops when the device screen is locked." — [Android Developers: Media projection](https://developer.android.com/media/grow/media-projection); [Android 15 behavior changes (all apps)](https://developer.android.com/about/versions/15/behavior-changes-all)
- **Boot receivers (Android 15):** `BOOT_COMPLETED` receivers may no longer start a `mediaProjection` foreground service; doing so throws `ForegroundServiceStartNotAllowedException`. — [Android 15 behavior changes (targeting 35)](https://developer.android.com/about/versions/15/behavior-changes-15)
- **Sensitive content protection (Android 15):** Android 15 adds "screen share protections" that hide sensitive content during screen sharing. A Developer Options toggle, "Disable screen share protections", turns them off. — [Android Police](https://www.androidpolice.com/android-15-screen-sharing-protections/); [Guardsquare](https://www.guardsquare.com/blog/android-15-screen-spying-protection)
- **Android 16 (secondary source only):** a 2026 developer blog claims "the projection token is single-use, and Android 16 re-asks consent every session", with "no cache across restart". Its matrix also says Play requires targeting the new API level for new apps and updates. — [Fora Soft 2026](https://www.forasoft.com/blog/article/screen-sharing-android-1644). The official media-projection page does not mention Android 16.

### Inferences
- **How repeated captures work:** obtain consent once, then create **one** VirtualDisplay backed by an `ImageReader` (`PixelFormat.RGBA_8888`, maxImages 2). Keep it alive for the whole reading session. On each tap of the overlay button, call `acquireLatestImage()` and copy the buffer into a Bitmap. This avoids calling `createVirtualDisplay()` again, which Android 14+ forbids, so the user consents only once per session, not once per page.
- **Resolution:** use the real display metrics (`WindowMetrics`) to get full-resolution page images. Mind the row-stride padding when copying the buffer.
- **Single-app mode:** if the user picks "single app" (for example Kindle), system UI and our own overlay are excluded from the capture automatically. In full-screen mode, the overlay must be hidden before each frame is grabbed.
- **Session ends easily:** because capture stops when the screen locks (Android 15 QPR1+) and the user can tap the chip, the app must handle `onStop()` and ask for consent again.
- **Status of the Android 15 protections:** these are reported to target notifications, OTPs and password fields, not ebook content. They are unlikely to affect book pages by themselves.

### Gaps
- **No official Android 16 doc fetched:** I could not retrieve an official Android 16 behavior-changes page that confirms any new MediaProjection restriction. The Android 16 claims above come from a secondary blog.
- **No benchmarks:** I found no measured figures for `ImageReader` capture latency or frame cost on recent devices.
- **Unclear whether hiding the overlay is needed in single-app mode:** I found no explicit statement that a `TYPE_APPLICATION_OVERLAY` window is excluded from a single-app capture. It is inferred from "only the contents of the selected app are shared."

---

## 2. AccessibilityService.takeScreenshot() and dispatchGesture()

### Takeaway
`takeScreenshot()` (API 30+) captures silently, without the per-session MediaProjection consent. Two hard limits apply:
- it is rate-limited to one call per **333 ms**
- it does **not** bypass FLAG_SECURE: secure windows are excluded or blanked in full-display screenshots, and `takeScreenshotOfWindow()` fails with `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW`

The bigger obstacles are policy ones: the Play Accessibility API declaration rules, and Android 17 Advanced Protection Mode.

### Cited Findings
- **Basic use:** `takeScreenshot(displayId, executor, callback)` returns a `ScreenshotResult` (a HardwareBuffer, wrapped with `Bitmap.wrapHardwareBuffer`). The service must declare `android:canTakeScreenshot="true"` in its meta-data. — [AccessibilityService API reference](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- **Window capture:** `takeScreenshotOfWindow(accessibilityWindowId, ...)` captures one window even when it sits under an accessibility overlay. — [AccessibilityService API reference](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- **Rate limit:** `public static final int ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS = 333;`. Calling sooner fails with `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` (3), meaning "too little time has elapsed since the last screenshot". — [AOSP AccessibilityService.java (main)](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/accessibilityservice/AccessibilityService.java); [API reference](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- **Interval enforcement:** the system service checks elapsed time against that constant and tracks it per window ID for `takeScreenshotOfWindow`. — [AOSP AbstractAccessibilityServiceConnection.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/accessibility/java/com/android/server/accessibility/AbstractAccessibilityServiceConnection.java)
- **FLAG_SECURE:** `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` (6) means "the window contains secure content", with a "See also: `WindowManager.LayoutParams.FLAG_SECURE`". — [API reference](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- **CTS confirms the FLAG_SECURE behavior:** — [AOSP CTS AccessibilityTakeScreenshotTest](https://android.googlesource.com/platform/cts/+/master/tests/accessibilityservice/src/android/accessibilityservice/cts/AccessibilityTakeScreenshotTest.java)
  - `testTakeScreenshotWithSecureWindow_GetScreenshotAndVerifyBitmap`: a full-display screenshot succeeds, but the secure window's content is absent from the bitmap.
  - `testTakeScreenshotOfWindow_ErrorForSecureWindow`: the call fails with `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW`.
- **Earlier analysis:** FLAG_SECURE was found to still protect against accessibility-service capture. — [Nightwatch Cybersecurity (2020, older)](https://wwws.nightwatchcybersecurity.com/2020/03/23/flag_secure-and-accessibility-services-a11y/)
- **No indicator:** "The takeScreenshot() API captures the screen silently - no notification, no toast, no visual indicator." — [Chocapikk 2026](https://chocapikk.com/posts/2026/android-a11y-god-mode/)
- **Play policy:**
  - Google Play restricts the Accessibility API and the `isAccessibilityTool` flag.
  - Apps that are not accessibility tools must make a prominent disclosure, get consent, and complete the accessibility declaration in Play Console.
  - Apps targeting Android 12+ that use AccessibilityService must complete the Permission Declaration Form and get approval.
  - Only verified accessibility tools (`isAccessibilityTool="true"`) may use the API for autonomous functionality, and only for assisting people with disabilities.
  - Source: [Play Console Help: Use of the AccessibilityService API](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)
- **Rejections happen:** developers report rejections, and "Deceptive and non-declared uses of these APIs may result in a suspension of your app and/or termination of your developer account." — [Reddit r/androiddev](https://www.reddit.com/r/androiddev/comments/163s09l/my_app_got_rejected_for_using_accessibility_api/)
- **Automation is not an accessibility use:** one developer's risk assessment says an automation use (injecting text) "almost certainly cannot honestly declare `isAccessibilityTool`". — [GitHub trevornk/ramblr #99](https://github.com/trevornk/ramblr/issues/99)

### Inferences
- **Page turning:** `dispatchGesture()` with a `GestureDescription` (a short horizontal swipe, or a tap on the right edge) can technically turn pages in Kindle, Kobo and others. Combined with `takeScreenshot()`, this allows a fully automatic "capture the whole book" loop at up to about 3 pages per second.
- **Policy risk:** that loop is exactly the kind of non-assistive automation Play restricts. It also points to mass copying of DRM'd books, which makes Play approval unlikely.
- **MVP choice:** a MediaProjection-based manual capture (the user turns the page, then taps the overlay) is the safer Play-compatible design.
- **Setup friction:** the user must enable the service manually in Settings > Accessibility. On Android 13+, sideloaded apps also hit "restricted settings", which is general knowledge and was not re-verified here.

### Gaps
- **GestureDescription limits:** I could not retrieve the exact values of `GestureDescription.getMaxStrokeCount()` and `getMaxGestureDuration()` (believed to be 20 strokes and 60 s; unverified).
- **Restricted settings:** no 2026 source was fetched on the Android 13+ "restricted settings" flow for sideloaded accessibility apps.

---

## 3. SYSTEM_ALERT_WINDOW overlay with Compose, and Android 15 FGS restrictions

### Takeaway
A `TYPE_APPLICATION_OVERLAY` window from a Service works with Compose, provided the ComposeView gets a `LifecycleOwner` and a `SavedStateRegistryOwner` (and optionally a `ViewModelStoreOwner`).

On Android 15 (targetSdk 35+), holding `SYSTEM_ALERT_WINDOW` no longer lets the app start a foreground service from the background on its own. The overlay must already be **visible** first.

### Cited Findings
- **Window type:** on Android O+, use `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`. — [Medium: Using Jetpack Compose in WindowManager Overlays](https://medium.com/@rohitkasanwal/using-jetpack-compose-in-windowmanager-overlays-a-case-study-a8aea57cb44d)
- **Lifecycle setup:** implement `LifecycleOwner`, `ViewModelStoreOwner` and `SavedStateRegistryOwner`, backed by a `LifecycleRegistry` and a `SavedStateRegistryController`. Call `ViewTreeLifecycleOwner.set(composeView, owner)` and `ViewTreeSavedStateRegistryOwner.set(composeView, owner)`, and fire the lifecycle events manually. — [Medium case study](https://medium.com/@rohitkasanwal/using-jetpack-compose-in-windowmanager-overlays-a-case-study-a8aea57cb44d); [TechYourChance: Compose inside a Service](https://www.techyourchance.com/jetpack-compose-inside-android-service/); [handstandsam OverlayService gist](https://gist.github.com/handstandsam/6ecff2f39da72c0b38c07aa80bbb5a2f)
- **Missing permission:** a missing overlay permission causes `WindowManager$BadTokenException ... permission denied for window type`. — [handstandsam gist comments](https://gist.github.com/handstandsam/6ecff2f39da72c0b38c07aa80bbb5a2f)
- **Android 15 FGS rule:** "If an app targets Android 15, this exemption is now narrower. The app now needs to have the `SYSTEM_ALERT_WINDOW` permission and also have a visible overlay window... the app needs to first launch a `TYPE_APPLICATION_OVERLAY` window and the window needs to be visible before you start a foreground service." Otherwise the system throws `ForegroundServiceStartNotAllowedException`. Check `View.getWindowVisibility()` or `onWindowVisibilityChanged()`. — [Android 15 behavior changes (targeting 35)](https://developer.android.com/about/versions/15/behavior-changes-15)
- **Background activity launches (Android 15):** Android 15 tightens background activity launches, including "Block non-visible windows from being considered for background activity launches". — [Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-15)

### Inferences
- **Recommended flow:**
  1. The user opens print-book (foreground activity), grants the overlay permission, and taps "Start".
  2. The activity launches the MediaProjection consent intent.
  3. On RESULT_OK, start the `mediaProjection` foreground service while the activity is still in the foreground. This avoids background-start issues entirely.
  4. The service adds the ComposeView overlay bubble and creates the VirtualDisplay and ImageReader.
- **Re-consent:** if the session dies (lock screen or chip tap), the bubble must bring an activity to the foreground to ask for consent again. A tap on our own visible overlay counts as user interaction.
- **Keeping the overlay out of captures:**
  - Option 1: set the overlay view to `INVISIBLE`, or move it off-screen with `updateViewLayout`.
  - Option 2: wait one or two frames (about 50–100 ms, or `Choreographer` callbacks) so the VirtualDisplay renders without it, then call `acquireLatestImage()` and restore the overlay.
  - Option 3: rely on single-app capture mode, which already excludes the overlay.

### Gaps
- No official guide shows a ComposeView inside a `TYPE_APPLICATION_OVERLAY`. The pattern comes from community articles and gists.
- No authoritative measurement of the delay needed between hiding the overlay and capturing a clean frame.

---

## 4. FLAG_SECURE in reading apps, workarounds and alternatives

### Takeaway
The **Kindle Android app** is reported to protect book pages with FLAG_SECURE, so screenshots come out black or blank. For Kobo, Google Play Books, Skeelo, Scribd/Everand and Libby, **I found no reliable source either way**; they must be tested on a device.

No legitimate non-root, Play-compatible way around FLAG_SECURE exists. MediaProjection and accessibility screenshots both honor it. Bypasses need root/LSPosed or desktop tools, and all of them circumvent DRM.

The most practical fallback is a web reader such as Kindle for Web (read.amazon.com), not every title is available there.

### Cited Findings
- **Kindle:** "Kindle uses this kind of protection on Android book pages, which is why screenshots may be blocked, blank, or unusable for OCR." The flag involved is `FLAG_SECURE`, which prevents screenshots and screen casting; "the captured image can be blank". — [TextMuncher (2026)](https://textmuncher.com/blog/kindle-android-copy-text-blocked)
  - Caveat: TextMuncher sells a Kindle-for-Web extraction product, so it has a commercial interest. Its claim matches widespread user reports, but I did not independently verify it.
- **Kindle for Web alternative:** "Open the same book in Kindle for Web... Not every title is available in Kindle for Web... when the book opens at read.amazon.com, the extraction path is much better." — [TextMuncher](https://textmuncher.com/blog/kindle-android-copy-text-blocked)
- **Black screenshots in general:** these happen because the app "utilizes the Secure Flag, provided by android which prevents anyone (even you) from capturing a screenshot". — [BestAccountability support](https://support.bestaccountability.com/articles/android/completely-black-screenshots); [Sauce Labs docs](https://docs.saucelabs.com/mobile-apps/features/bypass-screenshot/)
- **Bypasses need root or Xposed:**
  - Known bypasses are Xposed/LSPosed modules such as "Disable FLAG_SECURE".
  - Commercial test clouds (Sauce Labs, webmate) re-sign or instrument the APK.
  - None of these works for a normal Play-distributed app on a stock device.
  - Sources: [Xposed-Disable-FLAG_SECURE](https://github.com/varuns2002/xposed-disable-flag_secure); [LSPosed module page](https://modules.lsposed.org/module/com.varuns2002.disable_flag_secure/); [Sauce Labs](https://docs.saucelabs.com/mobile-apps/features/bypass-screenshot/)
- **No supported override:** "there is no universal, supported Android switch that should override an app's secure-window choice" (summary of search results). — [macmyths](https://macmyths.com/why-android-says-an-app-does-not-allow-screenshots/)
- **Accessibility screenshots also honor FLAG_SECURE:** see Section 2 and the [CTS test](https://android.googlesource.com/platform/cts/+/master/tests/accessibilityservice/src/android/accessibilityservice/cts/AccessibilityTakeScreenshotTest.java).
- **Google Play Books:**
  - A 2015 Reddit thread describes users capturing Play Books pages with the hardware screenshot button. That suggests no blocking at the time; this is old and may have changed. — [Reddit r/google (2015, older)](https://www.reddit.com/r/google/comments/2sdiuv/lets_talk_about_google_play_books_publishers_and/)
  - A WikiHow guide on "printing" from Play Books tells users to take a screenshot (date unknown). — [wikiHow](https://www.wikihow.tech/Print-from-Google-Books-on-Android)
  - No 2025–2026 source confirms either way.
- **Skeelo:** searches in Portuguese found no source saying whether Skeelo blocks screenshots. Reading happens only inside the app. — [Canaltech: what is Skeelo](https://canaltech.com.br/apps/o-que-e-skeelo-ebook-livros-digitais/)
- **Kobo, Libby, Scribd/Everand:** no specific reports found.
- **Apple Books:** there is no Android app (general knowledge).

### Inferences
- **Kindle is the main risk.** It is likely the biggest target app for print-book and the most likely to show black frames in MediaProjection captures.
- **Detect black frames:** the product should check for an all-black or near-uniform bitmap and show a "this app blocks capture" message, rather than saving empty images.
- **Web reader inside our app:** a WebView pointed at read.amazon.com could capture its own content with `View.draw()` or PixelCopy, since our app owns that window.
  - Risk 1: Amazon could detect or block embedded WebViews, or apply its own DRM such as canvas obfuscation.
  - Risk 2: this almost certainly conflicts with Amazon's Kindle terms of use and could attract Play policy or IP complaints. A legal and ToS review is needed.
- **Fallback for other apps:** for apps that turn out not to set FLAG_SECURE (possibly Play Books, to be verified), the MediaProjection overlay approach works as designed.

### Gaps
- **FLAG_SECURE status not verified for:** Kobo, Google Play Books (current version), Skeelo, Scribd/Everand and Libby. This needs hands-on testing on Android 14–16 with each app, and possibly per title, since DRM-free titles may behave differently.
- **Kindle per-title behavior:** no confirmation whether Kindle applies FLAG_SECURE to all titles or only DRM'd ones, or since which version.
- **WebView DRM:** no source on whether read.amazon.com blocks rendering inside a third-party WebView or uses DRM that defeats capture.
- **Android 15 content-sensitivity API:** I did not fetch the official page for the API that lets apps mark views as sensitive during screen share (believed to be `View.setContentSensitivity`). Whether reading apps use it is unknown.

---

## 5. New restrictions in Android 16/17: Advanced Protection and accessibility

### Takeaway
In **Android 17**, Advanced Protection Mode (APM) blocks the AccessibilityService API for any app not declared as a genuine accessibility tool (`isAccessibilityTool="true"`). Turning APM on revokes access already granted.

APM is opt-in and aimed at high-risk users, so most users are unaffected. Still, an accessibility-based print-book feature would break silently for that group. I found no Android 16/17 change that further restricts MediaProjection.

### Cited Findings
- **Headline (March 2026):** "Android 17 blocks non-accessibility apps from the Accessibility API in Advanced Protection Mode, reducing malware abuse and the attack surface." — [The Hacker News (Mar 2026)](https://thehackernews.com/2026/03/android-17-blocks-non-accessibility.html); [Security Affairs](https://securityaffairs.com/189497/security/advanced-protection-mode-in-android-17-prevents-apps-from-misusing-accessibility-services.html); [Android Authority (Beta 2)](https://www.androidauthority.com/android-17-beta-2-advanced-protection-mode-accessibility-apps-3648860)
- **Details from search summaries of coverage:**
  - Only apps flagged `isAccessibilityTool="true"` keep access.
  - Already-granted permissions are revoked when APM is turned on.
  - Users cannot force access while APM is active.
  - There is an `AdvancedProtectionManager` API for detecting the mode.
  - Sources: [Android Authority](https://www.androidauthority.com/android-17-beta-2-advanced-protection-mode-accessibility-apps-3648860); [ThaiCERT](https://www.thaicert.or.th/en/2026/03/17/android-17-enhances-security-by-restricting-accessibility-api-access-to-reduce-malware-risks/); [The Hacker News (Oct 2026)](https://thehackernews.com/2026/10/android-17-advanced-protection-locks.html)
- **Effect in practice:** users report accessibility services breaking with Advanced Protection; the workaround is turning off device protection. — [Google Android Community thread](https://support.google.com/android/thread/445201968/accessibility-services-are-broken-with-advanced-protection?hl=en)
- **Developer report:** with APM enabled, accessibility services become unavailable if the app is not distributed through the Play Store and does not declare itself an accessibility tool. — [Reddit r/androiddev](https://www.reddit.com/r/androiddev/comments/1uf2v4z/advanced_protection_program_and/)
  - This differs slightly from the "isAccessibilityTool only" reporting. The exact rule may depend on install source, so treat it as unconfirmed.

### Inferences
- **MediaProjection should be the core capture path.** The accessibility path (silent capture plus auto page turn) faces Play policy limits and the Android 17 APM revocation. If kept at all, it should be optional.
- **Detect APM:** if an accessibility feature is shipped, check for APM via `AdvancedProtectionManager` and degrade gracefully.

### Gaps
- **No official Android 17 docs fetched:** the strictest details (exact flag requirement, revocation, Play-install exception) come from news coverage of Beta 2, so confirm them in the official docs.
- **Android 16 changes not confirmed:** no official Android 16 behavior-change page was fetched to rule out other MediaProjection or overlay changes. The only secondary claim is consent per session with no token caching across restarts ([Fora Soft](https://www.forasoft.com/blog/article/screen-sharing-android-1644)).
