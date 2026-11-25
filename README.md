# Pause (Android)

A real Android app: it notices when you open an app or website you picked and shows the pause first. No MacroDroid, no website.

Play Store name: Pause a moment. Under the icon: Pause. App id: `app.pauseamoment` (can't change after the Play Store).

## How it's built
GitHub builds the app file on every change (`.github/workflows/build.yml`) and puts **Pause.apk** on the repo's "latest" release.

## Install on your phone
1. On your phone, open the repo on GitHub (signed in), go to **Releases → latest**, and download **Pause.apk**.
2. Open the file. Android asks to allow installs from Chrome (or your file app): allow it once, then tap **Install**.
3. Open **Pause**. Tap **Open Accessibility**, find **Pause** (sometimes under "Downloaded apps"), and turn it on.
4. If Android says it's a **restricted setting**: go back, tap **Open App info**, tap the **⋮** menu, **Allow restricted settings**, then turn it on again.
5. Tap **+ Choose an app** and pick Instagram, YouTube and so on. Apps added on the website version need to be removed and chosen again here.

## What it does
- Pause, picture or message, question, Open it for 5/10/15 min, daily opens and time spent: same as the web version.
- Closed hours, Normal / Hard / Strict, Block until: same rules. The let-in time stops early if closed hours start.
- When the let-in time ends while you're still in the app, the pause shows again.
- Websites are caught in Chrome, Samsung Internet, Brave, Edge and Firefox once the address bar shows the site.

## Honest limits
- Turning off Pause in Accessibility, or uninstalling it, gets around everything (Strict too).
- Some phones stop accessibility apps to save battery. If it stops catching apps, set Pause's battery use to Unrestricted.
- A paused app can flash for a split second before the pause covers it.
- Your Gentech filter may need to allow the app or the download.
- For the Play Store, Google reviews apps that use Accessibility like this and asks for a clear explanation and a short video.
