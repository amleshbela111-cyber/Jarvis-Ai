# Jarvis AI, Stages 1 to 7

## What works now

**Stage 1: UI and voice.** Compose dark UI with an animated orb, speech-to-text (English India and Hindi India), spoken replies, conversation history, STOP, and Settings.

**Stage 2: Gemini brain and safe planning.** Gemini returns a JSON action plan. The API key is encrypted on the phone. A validator checks every action against a closed allowlist. Gemini cannot run code or shell commands.

**Stage 3: Device controls.** Torch, media volume, brightness, opening apps by name, Settings pages, and screen lock. Success is confirmed where Android allows it (for example, torch and volume are read back).

**Stage 4: Accessibility.** Tap by visible label, scroll, swipe, typing into a named or focused field (never password fields), reading screen text, back, and home. You turn the service on once in Settings.

**Stage 5: Screen questions.** Turn on "Screen reading" in Settings once. Then ask "Is screen par kya likha hai?" or "Ye kya hai?". Jarvis takes one screenshot at a time, scales it down, and asks Gemini. A notification stays visible while screen reading is on. Screen capture stops when you tap Stop.

**Stage 6: Coding workspace.** "Calculator project banao" creates a small Android (Jetpack Compose) project inside the app's private workspace. "Is project mein MainActivity.kt likho ..." writes a file inside that project. Paths are checked so writes stay inside the project folder.

**Stage 7: Control and log.**
- **Pause automation** (Settings): commands are understood, but no actions run until you resume.
- **STOP** cancels listening, speech, and any running action.
- **Activity log** (Settings): the last 100 commands and their results. It is kept in memory and clears when the app closes.
- **Background use:** see the honest limits below.

**Stage 8: Confirmation.** Delete, send, submit, publish, pay jaise labels wale taps, `download_file` aur `install_apk` pehle confirm hote hain. Jarvis bolega "ye action chalane se pehle confirm karein", aap "haan" ya "nahi" bolein. Plan ko bina confirmation ke nahi chalaya jaata.

**Stage 9: Web.** `open_url` https links kholta hai, jaise GitHub repo ya website. Form bharne ke liye `tap` aur `input_text` use hote hain. Login, CAPTCHA aur payment ko Jarvis bypass nahi karta.

**Stage 10: APK download aur install.** `download_file` ek direct https `.apk` link (jaise GitHub Release asset) download karta hai, 50 MB tak. `install_apk` system installer kholta hai. Dono ke liye confirmation zaroori hai. Pehli baar Android "Install unknown apps" permission maangega. GitHub Actions ki artifact link login ke bina download nahi hoti, isliye us ke liye GitHub Release use karein.

**OCR.** Screen questions mein ML Kit se on-device text nikaalkar Gemini ko diya jaata hai. Secure windows abhi bhi capture nahi hote.

## Honest limits

**Build the APK.** Jarvis cannot build the project it creates on the phone. Push the project to a GitHub repository and the included workflow builds the APK. Jarvis does not yet fetch the finished APK back to the phone, and `build_apk` is not built.

**Screen reading.** Each question takes one screenshot. Secure windows (banking apps, DRM video) cannot be captured, and Android blocks that on purpose. Jarvis does no OCR beyond what Gemini sees in the image.

**Tap.** Works on visible text or button labels. A tap means the app accepted the click, not that the app did what you wanted. Apps that draw their own screens may not expose labels.

**Background listening.** Android 12 and newer stop the microphone for voice recognition when the app is not on screen. So voice commands work while Jarvis is open. Jarvis now runs a microphone foreground service with a notification, so it keeps listening while other apps (such as YouTube) are open. Tap PAUSE to stop listening. Some phone makers may still stop background services, so keep battery optimization off for Jarvis AI. Some phone makers (Xiaomi, Realme, OPPO) close background apps aggressively, so turn off battery optimization for Jarvis AI in Android Settings.

**Wake phrase.** Not built. Android background mic limits mean a wake phrase cannot be promised on every phone.

**Web forms.** Filling forms works only where the page exposes accessible fields. Jarvis does not solve CAPTCHA or handle two-factor login.

**Testing.** This code was written without compiling it on a device. The GitHub Actions build shows any compile errors. Send the log if it fails.

---

## Build with GitHub Actions (recommended)

1. Create a repository on GitHub. Public is fine. Your Gemini key is never in the code.
2. Upload the project files (keep the folder structure; include the hidden `.github` folder).
3. Open the **Actions** tab. "Build debug APK" runs on each push and takes about 5 to 10 minutes.
4. Open the finished run and download **JarvisAI-debug-apk** from Artifacts. Install the APK on your phone.

## Build on Termux (optional)

Use Termux from F-Droid or GitHub, not the Play Store.

```bash
pkg update -y && pkg upgrade -y
pkg install -y openjdk-17 gradle aapt2 wget unzip zip
mkdir -p ~/android-sdk/cmdline-tools && cd ~
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdtools.zip
unzip -q cmdtools.zip -d ~/android-sdk/cmdline-tools
mv ~/android-sdk/cmdline-tools/cmdline-tools ~/android-sdk/cmdline-tools/latest && rm cmdtools.zip
export ANDROID_HOME=$HOME/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
termux-setup-storage
cd ~ && unzip ~/storage/downloads/JarvisAI-stage1-7.zip && cd JarvisAI
echo "sdk.dir=$HOME/android-sdk" > local.properties
sed -i 's|# android.aapt2FromMavenOverride|android.aapt2FromMavenOverride|' gradle.properties
gradle wrapper --gradle-version 8.9 && chmod +x gradlew
./gradlew assembleDebug --no-daemon
cp app/build/outputs/apk/debug/app-debug.apk ~/storage/downloads/JarvisAI-debug.apk
```

## First-time setup on the phone

1. Open Jarvis, tap **SETTINGS**, paste your Gemini key, tap **Save key**.
2. Under **Device permissions**: turn on **Accessibility**, tap **Allow** for **Screen reading**, and optionally **Open** for brightness and **Enable** for screen lock.
3. Go back and speak. Try "Torch on karo", "Neeche scroll karo", or "Screen par kya likha hai".

## Permissions

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Voice commands. |
| `INTERNET` | Speech recognition and Gemini. |
| `WRITE_SETTINGS` | Brightness. Granted on a Settings screen. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Screen reading service. |
| `POST_NOTIFICATIONS` | Screen-reading notification (Android 13+). |
| Accessibility service | Taps, scrolling, typing, reading the screen. |
| Device admin (`force-lock`) | Screen lock only. |
| Screen capture (MediaProjection) | Screen questions. You approve each time you turn it on. |

## Troubleshooting

| Problem | Fix |
|---|---|
| Build error on GitHub | Send the log. |
| "Accessibility service band hai" | Turn on Jarvis AI under Accessibility. |
| "Screen reading band hai" | Tap Allow for Screen reading in Settings. |
| "Screen ka frame nahi mil paya" | Try again. Some screens only send a frame after they change. |
| "Automation paused hai" | Turn off Pause automation in Settings. |
| "Project ... already exists" | Choose a different project name. |
| "Gemini error 400/403" | Key is wrong, or the Gemini API is not enabled for that key. |
| "Gemini error 404" | Model name is wrong. Check the Gemini model field in Settings. |
