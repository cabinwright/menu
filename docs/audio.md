# Head unit audio path

How audio gets from an Android app to the speakers on the Gen5W head unit, why a plain
third-party app plays silently, and what an ordinary app has to call to be heard.

**Applies to:** firmware `CVEV.USA.0000.V015.012.260507` (2023 EV6, US). Files read:

| File | md5 |
|---|---|
| `/system/framework/automotive-service.odex` (ModeManagerService, MicomAudioService) | `33a30843e149a3d604ecc6957fd25d46` |
| `/system/framework/automotive.odex` (MicomAudioManager, ModeManager, constants) | (already in `stock-sui/framework`) |
| `/system/framework/framework.odex` (AudioManager, AudioService, MediaFocusControl, AudioServiceHelper) | (already in `stock-sui/framework`) |
| `/system/app/HKMC_GPM.apk` / `.odex` (Android Auto receiver) | `329946b2...` / `8e4f8fe9...` |
| `/system/app/HKMC_USBMusic.apk` / `.odex` | `c75ab329...` / `4d47221b...` |
| `/system/app/HKMC_MediaGate.apk` / `.odex` (source picker UI, no audio code) | `e9fbc5a9...` / `ec7ab5a6...` |
| `/system/lib/libautoreceiver_infobank.so` (GPM native AA stack) | `c08410a8d32a615b1aa805cd8a243d52` |
| `/system/lib/libaudioflinger.so` (Kia-modified AudioFlinger) | `ec74c76c604df97f8bf1c96e30626819` |
| `/system/lib/hw/audio_policy.default.so` (vendor `AudioPolicyManagerBase`) | `5498273e70757101c8827af58e4cbb57` |
| `/system/lib/hw/audio.primary.tcc803x.so` (Telechips HAL) | `4e23b3753aecde59b180b374745653a7` |
| `/system/etc/audio_policy.conf` | `e99f7849076cd3767f5d93b2200ab8aa` |

Both ModeManagerService and MicomAudioService are in `automotive-service.odex`
(`com/hkmc/micom/`), loaded into system_server. Their binder names are `com.hkmc.micom.mode`
and `com.hkmc.micom.audiosetup` (`dumpsys com.hkmc.micom.audiosetup` as root prints the
audio stack and every StreamTypeParameter).

## Decompiling

Java was easier to follow than smali for the 60k-line services, so the smali was reassembled
and run through jadx:

```sh
baksmali x -a 18 -d <framework dir> -b <BCP>:automotive.jar:automotive-service.odex X.odex -o smali
smali a -a 18 smali -o X.dex
jadx -d src X.dex          # jadx 1.4.7
```

In zsh, quote `"$BCP"`: an unquoted `$BCP:a...` is read as the `:a` history modifier.
Native code was read with Xcode's `llvm-objdump -d --triple=thumbv7-linux-androideabi`.
`libaudioflinger.so` has no internal symbols; addresses below are file offsets (= vaddr in
the first segment), found from the log strings each function prints.

## Summary

1. **Kia's AudioFlinger mutes every track whose audio session id is not the one the current
   source owns.** MicomAudioService tells AudioFlinger the session for each source
   (`setParameters("track_exclusive=N")`). On the main (AV) output, a track with any other
   session is mixed at volume 0. It stays "active" and looks normal in `dumpsys`. A plain
   app gets a session id above 100 from AudioFlinger, which never matches. This is why
   audio is silent even when the MICOM is unmuted and the source is GPM.
2. Android Auto's music track uses fixed session **21** (hard-coded in native code). USB
   Music uses fixed session **11** (`MediaPlayer.setAudioSessionId(11)`). Each source's
   session is listed in `AudioManager.SESSION_TRACK_*`.
3. The MICOM (amplifier/DSP controller) also has to be in a mode that listens to the
   Android output, and AV has to be unmuted (`MicomAudioManager.setMute(3)`).
   `requestAudioFocus` with a mode does the first. Every mode change mutes AV (`setMute : 2`),
   and the source app unmutes it.
4. There is no permission check on any of this: not on MicomAudioService binder calls, not on
   focus requests with a mode, and not on choosing a session id.

## Signal path

```
app AudioTrack (stream, session)
  -> AudioPolicy: stream -> strategy -> device -> output
       STREAM_MUSIC -> MEDIA -> 0x10000000 -> output 101 ("primary", AV channel)
       RING/NOTIFICATION/TTS/11/12/14 -> strategy 6 -> 0x2 SPEAKER -> output 102 (TTS channel)
  -> AudioFlinger MixerThread::prepareTracks_l
       output 101 only: track muted unless track.session == exclusive session   <-- the gate
  -> HAL audio.primary.tcc803x (tinyalsa PCM, I2S)
  -> MICOM / DSP: AUDIO_SET_AUDIO_MODE_C picks the AV input,
                  AUDIO_SET_NAVI_TTS_MODE_C opens the TTS channel,
                  AUDIO_SET_MUTE_CONTROL_C mutes and unmutes
  -> amplifier
```

## 1. The AudioFlinger session gate (`libaudioflinger.so`)

| Offset | What |
|---|---|
| `AudioParameter::keyTrackExclusive` (`0x62f18` -> `"track_exclusive"`), `keyTrackDisable` (`"track_disable"`), `keyPcmDropping` (`"pcm_dropping"`) | Vendor keys added to `AudioParameter`. Java side: `AudioManager.setExclusiveTrackSession(n)` = `setParameters("track_exclusive=n")`, `disableTrackSession(n)` = `"track_disable=n"`, `clearOutputPcmBuffer()` = `"pcm_dropping=1"`, `setStreamPriority(s)` = `"stream_priority=s"`. |
| `0x1c9be` | `AudioFlinger::setParameters`, `track_exclusive` branch: for each playback thread that is a mixer (`thread+40 == 0`) **and whose device has bit `0x10000000`**, calls `exclusiveTrackSession(session)`. Only output 101 qualifies. Output 102 (device `0x2`) is never gated. |
| `0x200d8` | `MixerThread::exclusiveTrackSession(int)` (logs `MixerThread::exclusiveTrackSession() mixer %p, session %d, ...`): stores the session at `thread+484`. Initialised to 100 in the thread constructor (`0x2567a`), which means no restriction. |
| `0x1c96c`, `0x22320` | `track_disable` branch -> `MixerThread::disableTrackSession(int)`: sets a per-track enable byte (`track+186`) to 0 for tracks with that session and to 1 for all others. With session 50 it instead disables every active `STREAM_MUSIC` track whose session is above 100 (logs `disable music stream track`). Only ModeManagerService has a (dead) caller for this. |
| `0x24416`-`0x24448` | **The gate in `prepareTracks_l`.** For each track: skip if `track+186 == 0`; then with `ex = thread+484`: if `ex == 50` skip tracks with session <= 99; if `ex <= 49` skip tracks with session != ex; if `ex > 50` no restriction. "Skip" (`0x248fe`) sets the track's left/right gain to 0 for this mix cycle. The track keeps running and consuming data. |

Who sets the exclusive session (all in `MicomAudioService`):

| Call site | Session |
|---|---|
| `systemReady` (line ~4837 in jadx output) | 9 (AV sound off) |
| `setMuteStreams` "mute by AvOFF" | 9 |
| `setAudioMode` -> `setAudioSessionTrack(micomAudioMode)` (every mode change) | table below |
| `startAudioImplMain` for a stream type with `mSessionId > -1` | AVOFF (33) -> 9, AAVR_AV (63) -> 21, DiPORING (16) -> 50, UVOBLUERING (35) -> 19 |

`MicomAudioService.setAudioSessionTrack(int mode)`: MICOM audio mode -> session. The names are
`AudioManager.SESSION_TRACK_*` (also in `AudioSystem`):

| MICOM audio mode | Source | Session | `SESSION_TRACK_` |
|---|---|---|---|
| 6, 17, 26 | CD | 14 | `CDP` |
| 9 | USB | 11 | `USB_MUSIC` |
| 10 | TDMB | 22 | `DMB` |
| 13, 20, 21 | iPod, Aha, Pandora | 15 | `IPOD` |
| 14 | Bluetooth audio | 13 | `BT_AUDIO` |
| 18 | My Music | 10 | `INTERNAL_MUSIC` |
| **23** | **GPM (Android Auto)** | **21** | `AA_MUSIC` |
| 25 | USB video | 12 | `USB_VIDEO` |
| 29 | MirrorLink | 27 | `MIRRORLINK` |
| 30 | mConnect | 24 | `MCONNECT` |
| 32 | ISDB-T | 25 | `ISDB_T` |
| 34 | QQ | 28 | `QQ_MUSIC` |
| **37** | **KaolaFM / Music streaming** | **29** | `KAOLA_FM` |
| 41 / 42 / 45 / 46 | Melon / Genie / JioSaavn / Vibe | 31 / 32 / 33 / 34 | `MELON_MUSIC` ... `VIBE_MUSIC` |
| **78** | **Preview audio** | **26** | `PREVIEWAUDIO` |
| 85 | Nature sound | 30 | `NATURESOUND` |
| anything else (e.g. 153 SIRIUS1 = mode DEFAULT, 22 CarPlay, radio) | | 50 | `NONE` (= only sessions > 99 play) |

Live evidence (`dumpsys media.audio_flinger`, source GPM): Android Auto's music track is on
output 101 as `Type 3` (STREAM_MUSIC), session 21, 48 kHz. Navigation (pid 1535) plays
stream 12 on output 102 with session 113 and is audible, because output 102 is not gated.

## 2. GPM (Android Auto receiver): what makes it audible

Package `com.google.android.projection.sink`, runs as uid `system`. The relevant classes
are not obfuscated.

- **Focus with a mode** (`sink/audio/AudioFocusManager.requestAudioFocus()` and
  `handleAudioFocusGain()`):
  `mAudioManagerMedia.setModeManagerExtension(true); setPermitAudioFocusOnCallState(true);
  requestAudioFocus(listener, 3 /*STREAM_MUSIC*/, 1 /*GAIN*/, 116 /*ModeType.GPM*/)`.
  ModeManager maps mode 116 to MICOM audio mode 23, so MicomAudioService sends
  `AUDIO_SET_AUDIO_MODE_C(23)`, sets `AndroidAudioRelease=off` and `track_exclusive=21`.
  `mPreAudioMode = 23` in the logs is this MICOM audio mode, `ModeConstants.DeviceId.GPM`.
- **Unmute**: `service/GalReceiverService.onModeChanged(116)` -> `setMicomMute(3)` ->
  `MicomAudioManager.setMute(3)`. That is the `setMute : 3` seen after a working source.
- **Session 21, hard-coded**: native `AudioPlayer::onSetup(int)` in `libautoreceiver_infobank.so`
  calls `android::AudioTrack::set` at `0xb05b6` with `sessionId` = literal `21` (`movs r6, #21`
  -> `[sp+32]`), flags 0, no shared buffer. Stream type, rate and format come from the
  AudioPlayer object (stream 3 per dumpsys). No `setParameters`, no output flags.
- **Voice/guidance** use the TTS channel, not the AV mode:
  `startAudio(63 AAVR_AV)` + `setMute(3)` for assistant playback over AV,
  `startAudio(17 GSA)` / `startAudio(18 AANAVI)` for assistant and nav prompts,
  `endAudio(17/18/63)` afterwards (`GalReceiverService.startMicomAudio/endMicomAudio`). Its
  transient voice focus is requested with `setModeManagerExtension(false)`, so no mode change.
  Guidance plays on stream 14 (`STREAM_GVLS_TTS`), output 102.

## 3. USB Music: what makes it audible

Package `com.daudio.av.app.usbmusic`, uid 10026 (an ordinary app uid). Obfuscated class
`player/k` = the player/focus manager, `player/FilePlayerManager` = MediaPlayer wrapper.

- `k.<init>(Context, int mode)`: `setPermitAudioFocusOnCallState(true)`; for mode 49 (USB_AUDIO)
  sets session `tc = 11` (default 50).
- `k.ko()`: `requestAudioFocus(listener, 3, 1, 49)`.
- `MusicService.l()` after focus is granted -> `k.ku()` -> `MicomAudioManager.setMute(3)`.
- `FilePlayerManager` (preparePlay): `new MediaPlayer(); setAudioSessionId(11);
  setDataSource(...); setAudioStreamType(3); prepareAsync()`.
- `k` (abandon): `abandonAudioFocus(listener)`.

So the recipe is: focus with your mode, unmute, and play with the session that mode's
MICOM audio mode owns.

## 4. ModeManagerService and MicomAudioService

### Focus -> mode change (`framework.odex`)

- `AudioManager.requestAudioFocus(l, stream, hint)` uses mode `0` while `mModeManagerExtension`
  is true, which is the default (`AudioManager` field initialiser), or `-1` after
  `setModeManagerExtension(false)`.
- `AudioManager.requestAudioFocus(l, stream, hint, mode)` (hidden) ->
  `AudioService.requestAudioFocusE` -> `MediaFocusControl.requestAudioFocusE`. The only gate
  is `AppOps.noteOp(32 OP_TAKE_AUDIO_FOCUS)`. There is no permission or signature check.
- `AudioServiceHelper.RequestAudioFocus_PostProcessing`: if granted and `mode != -1` ->
  `changeModeToModeManager(mode)` -> `ModeManagerService.onChangeMode` -> `changeMode(mode,
  processName, packageName, isAuto, true)`.
- When a previous holder gets focus back, `AudioServiceHelper.FocusEventHandlerDelegate` calls
  `changeModeToModeManager(holder.mAVMode, ...)` again. **Abandoning focus therefore restores the
  previous source's MICOM mode and session automatically.**
- Mode 0 (`ModeType.DEFAULT`) has MICOM audio mode 153 (SIRIUS1 = "AV off"). That is why a
  plain `requestAudioFocus` gives "Set MicomAudioMode with SIRIUS1(0x99)" then AVOFF.

### `ModeManagerService.ModeChanger.changeModeWithDeviceIdInternal`

Checks power state and that the package holds focus (`hasAudioFocus(packageName)`), then
`ModeManagerState.getModeInfo(mode)` (unknown -> "Mode is not supported"). It sets current
mode, pushes last-mode stacks, abandons ModeManager's own focus, logs
`Set MicomAudioMode with <DeviceId>`, calls `MicomAudioService.setAudioMode(deviceId)`
(or `setAudioEqualizer`), broadcasts `com.hkmc.intent.action.MODE_CHANGED`
(`EXTRA_MODE_TYPE`, `EXTRA_PREV_MODE_TYPE`) and notifies mode listeners.

ModeInfo table (`ModeManagerState.<init>`, `new ModeInfo(modeType, micomAudioMode, shortcut,
package, group)`), relevant rows:

| ModeType | MICOM mode | Package | Notes |
|---|---|---|---|
| 0 DEFAULT | 153 | | "AV off" |
| 49 USB_AUDIO | 9 | `com.daudio.av.app.usbmusic` | |
| 54 BLUETOOTH | 14 | `com.daudio.av.app.btaudio.ui` | |
| 55 MUSIC (My Music) | 18 | `com.daudio.av.app.mymusic` | |
| 116 GPM | 23 | `com.google.android.projection.sink` | |
| 115 DIPO (CarPlay) | 22 | `com.daudio.app.dipo` | sets `AndroidAudioRelease=on` (Android output released) |
| 128 KAOLAFM | 37 | `com.daudio.av.app.kaolafm` (not installed) | media group only with feature `com.hkmc.software.kaolafm` |
| 130 MUSICSTREAMING | 37 | `com.daudio.app.musicstreaming` (not installed) | |
| 131 / 132 / 134 GENIE / MELON / VIBE | 42 / 41 / 46 | none | media group only with `com.hkmc.software.music.streaming` |

This car has none of the streaming features (`pm list features`). Mode 130 was nevertheless
accepted in testing ("Set MicomAudioMode with Unknown Device Code : 0x25").
`ModeConstants.DeviceId` has no name for 0x25 (37), which is why the log says "Unknown".

Permissions: ModeManagerService has `enforceSelfCallingPermission()` on some internal methods.
The focus-driven `onChangeMode` path above runs inside system_server and is not gated.

### `MicomAudioService.setAudioMode(mode, type, value, send)`

Logs `setAudioMode() : pre(..) cur(..)`. In order:
refuses AV modes while preview audio is active (unless preview no longer holds focus), during
UVO/BT call; `setAudioSessionTrack(mode)` (session gate above); for AV modes ends AVOFF (33)
and POWEROFF (32) stream types (that is the `endAudioImpl - streamType : 33` log line); if the mode
changed, `setMute(2)` (AV mute) and sleeps 60 ms; `setParameters("AndroidAudioRelease=on")`
for modes 22, 77, 82, 43 (CarPlay audio, Android output released), `cdda_player` for 6,
otherwise `AndroidAudioRelease=off`; remaps the command value (77/82 -> 22,
78 -> 33 if MICOM version >= 259 else 9, 153/81/85 -> 9, 79 -> 15, 80 -> 14, 83 -> 15,
86 -> 23, 76 -> announce radio band); **`sendCommand(513 AUDIO_SET_AUDIO_MODE_C, mode, 1)`**;
resends the stream volume; `mCurrentTimecheck = true` and posts `EVENT_MODECHANGE_AVAIL`
after 200 ms (400 ms from CarPlay).

`handleModeChangeAvail` clears `mCurrentTimecheck` and, if an unmute was deferred, performs it.
`handleDelayDo` = `setMute(3)`.

### `MicomAudioService.setMute(int)`: values

MICOM commands are `AudioSetup.DFunc`: `523 AUDIO_SET_MUTE_CONTROL_C(target, set)`,
`568 AUDIO_SET_MUTE_CONTROL_FADEOUT_C`. Targets are `AudioSetup.MUTE_MODE`: 1 AMP, 2 AV, 3 NAVI
(TTS channel). `set` is 1 to mute, 0 to release.

| `setMute(n)` | Sends | Meaning |
|---|---|---|
| 0 | 523(3, 1) | mute TTS/navi channel |
| 1 | 523(3, 0) | unmute TTS/navi channel |
| 2 | 523(2, 1) | **mute AV** (sent on every mode change) |
| 3 | 568(2, 0) or 523(2, 0) | **unmute AV**, with conditions: ignored in delay mode; deferred 200 ms (`EVENT_DELAY_DO`) while `mCurrentTimecheck`; refused while AV-off/mode-change mute bits are set (`mMuteState & 2/4`) or a TTS-group stream is on top, **except** during preview audio, where it forces the unmute. Spec step >= 55 and an AV mode -> the fade-out variant 568 (`sendCommand AUDIO_SET_MUTE_CONTROL_FADEOUT_C, mPreAudioMode = ..`). |
| 4 / 5 | 523(1, 1) / 523(1, 0) | mute / unmute amplifier |
| 16 | 568(1, 1) | fade-out mute all |
| 240 | 523(1, 1), latch | "mute forever" (software update) |

### `startAudio(type, a1, a2)` / `endAudio(type, a1)`

Binder methods with no permission check (the only `getCallingUid` in the class is in `dump`).
They push/pop a stack of `StreamTypeParameter` entries (`MicomAudioService.StreamTypeHashMap`,
class `com.hkmc.media.StreamTypeParameter`). Channel `1` AV entries go to `startAudioImplMain`:
optional `AndroidInputRelease`/`AndroidAudioRelease`, `setAudioMode(stp.mAudioMode)`, optional
`track_exclusive`. Channel `4` TTS entries go to `startAudioImplTTS`, which sends
`519 AUDIO_SET_NAVI_TTS_MODE_C(mode, priority, ON[, volume])`, and for the TTS group (40) also
`setMute(2)`. `endAudio` pops, sends the matching OFF, and restarts the entry below.
AV source modes (GPM, USB, ...) are **not** on this stack; with GPM playing, `[AudioStack]` is
empty.

Relevant stream types (`MicomAudioManagerConstant.AudioType` and the dump):

| Type | Name | Channel / type | Effect |
|---|---|---|---|
| 3 | NAVI | TTS / 51 (navi group) | NAVI_TTS on, mixed over AV |
| 7 / 10 | TTS / GUIDE | TTS / 41 (TTS group) | NAVI_TTS on + AV mute |
| 17 | GSA (AA assistant) | TTS / 42 | AV mute; `mForcedAVUnmute` on end |
| 18 | AANAVI | TTS / 52 (navi group) | NAVI_TTS on, mixed over AV |
| 28 | PREVIEWAUDIO | AV / 32 (non-mode) | `setAudioMode(78)` -> MICOM 33 or 9, session 26 |
| 29 | PREVIEWAUDIOTTS | TTS / 41 | `openTTSSoundPath` |
| 33 | AVOFF | AV / 8 | mute main, session 9 |
| 63 | AAVR_AV | AV / 43 | `setAudioMode(86)` -> MICOM 23, session 21 |
| 78+ | allocated by `createStreamType(id, ch, type)` | always TTS channel (`createAllocatedSTP` forces channel 4) | e.g. `com.mobis.voicehandler.BEEP` = 78 |

`MicomAudioManager.preparePreviewAudio()` -> `mIsPreviewAudio = true; startAudioImpl(28)`;
`exitPreviewAudio()` -> `endAudioImpl(28)`.

## 5. Audio policy and HAL

`audio_policy.default.so` is the legacy `AudioPolicyManagerBase` with Kia ("daudio") strategies.

`getStrategy(stream)` (`0x9820`, table at `0x982a`):

| Stream | Strategy |
|---|---|
| 0 VOICE_CALL, 6 BT_SCO | 1 PHONE |
| 1 SYSTEM, **3 MUSIC**, 10 | 0 MEDIA |
| 4 ALARM | 2 SONIFICATION |
| 7 SYSTEM_ENFORCED | 5 ENFORCED_AUDIBLE |
| 8 DTMF | 4 DTMF |
| **2 RING, 5 NOTIFICATION, 9 TTS, 11, 12 NAVI, 14 GVLS_TTS** | **6 navigation (tts)** |
| 13 BLUETOOTH_TTS | 7 telematics |
| 15 CALL_HFDSP | 9 FM1288 |
| 16 BLUETOOTH_DTMF | 10 |

`getDeviceForStrategy` (`0x9cb4`): MEDIA prefers A2DP/wired/USB devices, then falls back to
**`0x10000000`** (forced to it directly if `persist.sys.spdif_setting` starts with `1` (it is
`0` here) or force-use is 12). Strategy 6 -> `0x2` SPEAKER, 7 -> `0x10000` TELEMATICS,
8 -> `0x20000` DiPO, 9 -> `0x40000` FM1288, 10 -> `0x80000`. Each logs
`daudio - <name> available device not found` when the device is not attached. Available
outputs right now are `0x10010002` (= `0x10000000 | TELEMATICS | SPEAKER`), so the
"DiPO available device not found" line only means CarPlay is not connected.

- **`0x10000000`** (named `AUDIO_DEVICE_OUT_SPDIF_PCM` in `audio_policy.conf`) is the AV channel
  to the MICOM/DSP. Output 101, "primary", 44.1 kHz.
- **`0x2` SPEAKER** is the TTS/guidance channel. Output 102, the conf's `spdif` profile.
  The MICOM mixes it in only while a NAVI_TTS mode is on (`519`).
- **DiPO** = Apple CarPlay ("digital iPod out"). Its own device, `pcm_config_out_DiPO` in
  the HAL, `libCarPlayAudio.so`.
- **FM1288** = Fortemedia FM1288 voice processor (echo/noise cancellation for hands-free, VR
  and mics). The HAL has `set_FM1288_Mic*`, `set_FM1288_mode`, tuning tables
  `fm1288_mic_VR*`, `fm1288_2mic_*`.

So **STREAM_MUSIC from any app goes to the same output and device as Android Auto's
music.** It is not routed somewhere the amplifier ignores. The difference is the session
gate, plus MICOM mode/mute.

HAL `setParameters` keys found (`audio.primary.tcc803x.so`, all sent only by
MicomAudioService): `AndroidAudioRelease=on|off` (release the AV PCM to another master,
used for CarPlay and CD), `AndroidAudio2Release`, `AndroidInputRelease`,
`CallDaemonRunning=start|stop`. Mixer paths come from `/system/etc/mixer_paths_ak4601.xml`.
GPM sends none of these keys.

## 6. The owner's tests, explained

| Test | Why it was silent |
|---|---|
| plain `requestAudioFocus` | mode 0 -> MICOM 153 (SIRIUS1, AV off) -> `track_exclusive=50`, AVOFF -> session 9, AV mute. With session 50, sessions > 99 do pass, so the app may have been audible for the moment before AVOFF (the one beep). |
| focus with mode 130 MUSICSTREAMING | MICOM 37, `track_exclusive=29`. The app's session (>100) != 29, so it was gated. |
| no focus (GPM) + `setMute(3)` | AV unmuted on MICOM 23, but `track_exclusive=21` and the app's session is not 21, so it was gated. The beep was probably the MICOM key beep (`AUDIO_SET_BEEP_C` is sent on every touch), not the app. |
| same + `ToneGenerator` | ToneGenerator makes its own AudioTrack with a fresh session, so it was gated. |

## 7. What a third-party app should call

Neither `MicomAudioManager` (in `automotive.jar`, on `BOOTCLASSPATH`) nor the hidden
`AudioManager` methods need a permission or system uid. Compile against stubs or use reflection.
Choosing an audio session id is not checked: `AudioTrack(stream, rate, ch, fmt, buf, mode,
sessionId)` and `MediaPlayer.setAudioSessionId(int)` are public SDK API (since API 9), the
framework only rejects negative ids, and USB Music (uid 10026) uses session 11 through
MediaPlayer. A direct AudioTrack from an app uid with a low session id should work the same
way (stock 4.4 `createTrack` does not check it), but that is untested.

### A. Preview audio: plays over the current source, no ModeManager mode change, no app launch

Confirmed audible, but it has no knob volume and no volume bar (stream type 28 is
`mHasNoVolume`), and a missed `exitPreviewAudio` disables the knob for every source until it is
cleared. See section 9. Use B for real playback.

This is Kia's own "play a sound over the current source" path (sound-settings previews):

```java
AudioManager am = (AudioManager) ctx.getSystemService(AUDIO_SERVICE);
MicomAudioManager mam = new MicomAudioManager(ctx);          // com.hkmc.media
am.requestAudioFocusForCall(4 /*preview*/, AudioManager.STREAM_MUSIC,
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);              // hidden; clientId "AudioFocus_For_PreviewAudio"
mam.preparePreviewAudio();     // startAudioImpl(28): setAudioMode(78) -> MICOM 33 (or 9), track_exclusive=26, AndroidAudioRelease=off
mam.setMute(3);                // AV unmute; deferred 200 ms by MicomAudioService if sent too early, forced while preview is on
AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, 44100,
        AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT, buf,
        AudioTrack.MODE_STREAM, 26 /*SESSION_TRACK_PREVIEWAUDIO*/);   // or MediaPlayer.setAudioSessionId(26)
// play ...
t.stop(); t.release();
mam.exitPreviewAudio();        // endAudioImpl(28)
am.abandonAudioFocusForCall(4);  // previous holder (e.g. GPM) regains focus -> changeMode(116) -> MICOM 23, session 21, its own setMute(3)
```

Evidence: `MicomAudioService.preparePreviewAudio`, stream type 28 in `StreamTypeHashMap`,
`setAudioSessionTrack(78) = 26`, the `mIsPreviewAudio` forced-unmute branch in `setMute(3)`,
`MediaFocusControl` treating the preview client like a call (no mode change in
`RequestAudioFocus_PostProcessing`), and `FocusEventHandlerDelegate` restoring the previous
holder's mode.

Unknowns: MICOM mode 33 has no name in the code (mode 9, used when the MICOM version is
below 259, is USB = Android audio). While preview focus is held, other apps' full-gain
requests are refused (`canReassignAudioFocusIVILocked`), as during a call. If the app dies
holding preview, the focus entry is removed by the death handler, but the PREVIEWAUDIO entry
stays on MicomAudioService's audio stack (section 9).

### B. Take the KaolaFM / Music Streaming source slot

Confirmed working on the car: audible, knob volume and volume bar work, no Kia screen opens,
and `abandonAudioFocus` returns to GPM (`track_exclusive=21`, unmuted). This is what
kia-menu's `KiaAudio` does.

```java
am.setPermitAudioFocusOnCallState(true);                       // optional, as GPM/USB do
int r = am.requestAudioFocus(l, AudioManager.STREAM_MUSIC,
        AudioManager.AUDIOFOCUS_GAIN, 130 /*ModeType.MUSICSTREAMING*/);   // hidden 4-arg
// r == 1: ModeManager -> MICOM 37, track_exclusive=29
mam.setMute(3);                                                // as USB Music does right after the grant
AudioTrack t = new AudioTrack(STREAM_MUSIC, ..., 29 /*SESSION_TRACK_KAOLA_FM*/);
// play ...
am.abandonAudioFocus(l);       // previous holder regains focus and its mode is restored
```

Evidence: the same three steps USB Music and GPM take (sections 2 and 3). Mode 130 was
accepted on this car. The earlier test failed only on the session.
Risks: it is a real source change. It broadcasts `MODE_CHANGED`, the previous source gets a
permanent focus LOSS (it stays in the focus stack and comes back when this app abandons focus),
and `LastModeController` records mode 130 as the last mode, which a later boot may try to
restore with no app behind it. Whether the US MICOM firmware handles audio mode 37 is
unknown. Mode 128 (KAOLAFM) maps to the same MICOM mode and session.

### C. TTS/navigation channel: mixed over the source, no focus

```java
int type = mam.createStreamType(ctx.getPackageName() + ".AUDIO", 4, 52); // own TTS-channel type (78+); or use 18 AANAVI
mam.startAudio(type);          // 519 AUDIO_SET_NAVI_TTS_MODE_C(NAVI, priority, ON)
AudioTrack t = new AudioTrack(AudioManager.STREAM_NOTIFICATION, ...);   // strategy 6 -> SPEAKER -> output 102, not session-gated
// play ...
mam.endAudio(type);            // NAVI_TTS OFF
```

Evidence: navigation and AA guidance are audible this way (stream 12 and 14 on output 102),
the policy table above, and `startAudioImplTTS`. Suited to prompts and alerts: it ducks or mixes
over the AV source, uses the navi-style volume group, and type 52 entries can be refused while a
main stream forbids navi sound (`getNaviPermitState`). It needs a listening test before
being relied on for music. Using 18 directly would collide with Android Auto's own AANAVI entry.

### D. Piggyback on the current source's session (no calls at all, hacky)

Play `STREAM_MUSIC` with the session the current MICOM mode owns: 21 while GPM is the source,
11 for USB, 10 for My Music, 13 for Bluetooth. Read it from
`MicomAudioManager.getAudioMode()` and the table in section 1. The app mixes into the
source's own audio. It only works when that source is Android-generated
(`AndroidAudioRelease=off` and the MICOM listening to Android), not for radio or CarPlay.
It breaks as soon as the source changes. Lowest confidence as a product path, but the
quickest test of the session theory: with GPM as the source and AV unmuted, an AudioTrack
with session 21 should be audible without any other call.

### Do not

- Call plain `requestAudioFocus` without `setModeManagerExtension(false)` first. It means mode 0,
  which is AV off.
- `setParameters("track_exclusive=...")` from the app. It works (it needs only
  `MODIFY_AUDIO_SETTINGS`) but fights MicomAudioService, which rewrites it on every mode change.

## Suggested listening tests, in order

1. Source GPM, no focus request, `setMute(3)`, AudioTrack STREAM_MUSIC **session 21**: confirms
   the gate (D).
2. Sequence A (preview) with session 26, then check that the AA source comes back after
   `abandonAudioFocusForCall(4)`.
3. Sequence B with session 29, then check that the previous source is restored after abandon.
4. Sequence C with STREAM_NOTIFICATION.

## 8. Volume knob and volume bar

All in `automotive-service.odex` unless noted.

| Step | Code |
|---|---|
| Knob turn | Arrives as a volume key. Either `AudioService.adjustSuggestedStreamVolume` / `adjustStreamVolume` (`framework.odex`), which first asks `AudioServiceHelper.adjustStreamVolume*`: if any `IAudioServiceNotiListener` is registered, the stock volume code is skipped and `onAdjustStreamVolume` is broadcast. Or, in direct volume mode, `MicomAudioService.mModeKeyListener.onKey` for keycodes 24/25. Both end in `MicomAudioService.adjustsuggestedStreamVolume(3, dir, repeat, 9)`. |
| Pick the stream | `adjustStreamVolume(suggested, dir, repeat, flags)` ignores the suggested stream and calls `getCurStreamType(false, true)` -> `getCurStreamTypeArray`. The top of MicomAudioService's own audio stack (`getCurStreamTypebyStack`) wins. Only when that stack is empty does it use `getStreamTypebyModeNew(mAvAudioMode)`, e.g. MICOM 23 -> 22 AAMUSIC, 37 -> 62 KAOLA FM. A stack entry with `mNotAdjustVolumeByKnob` is skipped for the knob. |
| Refusals | `stp.mHasNoVolume` ("No volume Stream = N"), BT private, emergency, `mIsPreviewAudio` while preview focus exists, AV disabled or power-off lock, and `ModeManagerService.hasAudioFocus() \|\| mPreAudioMode == 0` for AV streams ("Last mode restoring or Preparing"). |
| MICOM command | `adjustStreamVolume(stream, convDir, -1, repeat, flags)` -> `setVolume` -> `setVolumeInternal`, which looks up the volume group (`mVolumeGroupHashMap`) and calls `sendVolumeCommandToMicom(channel, level, ..., group, ...)`. |
| Volume bar | Same place in `setVolumeInternal`: `mVolumePanel.postVolumeChangedNew(level, icon, iconType, titleId, min, max, UIoption)`, unless `stp.mNotShowVolume` is set, or the stream is an AV stream while AV is muted ("AV volume is changed during mute. DID NOT DISPLAY VOLUME PANEL"). `MicomVolumePanel` draws it. |

So the knob and bar follow MicomAudioService's audio stack first and the current MICOM AV mode
second. They do not depend on which app is in the foreground or on `registerMediaApplication`.
With sequence B the stack is empty, the mode is 37 -> stream 62 (volume group KAOLA FM), and
the knob and bar work. That was confirmed on the car. Preview (stream 28) is `mHasNoVolume`
by design, so it never has knob volume.

## 9. Preview audio: stale stack entry (warning)

`preparePreviewAudio` sets `mIsPreviewAudio = true` and calls `startAudioImpl(28)`, which pushes
PREVIEWAUDIO onto `mAudioStack` and runs `startAudioImplMain` (`setAudioMode(78)`,
`track_exclusive=26`). `exitPreviewAudio` is the **only** code that calls `endAudioImpl(28)`.
Nothing else ever removes it:

- No death link. MicomAudioService links to death only for `registerListener` state listeners.
  `MediaFocusControl.AudioFocusDeathHandler` removes the app's preview **focus** entry
  (`removeFocusStackEntryForClient`), and the previous holder regains focus. Nothing tells
  MicomAudioService.
- `setAudioMode` refuses AV modes while `mIsPreviewAudio` ("fail PreviewAudio On"), unless the
  focused client is no longer `AudioFocus_For_PreviewAudio` ("Keep going"). At its end,
  `executeFailSafe` clears `mIsPreviewAudio` when `mPreAudioMode != 78` ("PreviewAudio should
  not be true"). It clears only the flag, not the stack entry.
- From then on, `getCurStreamTypebyStack` returns 28 and every knob turn for every source logs
  "adjustStreamVolume. No volume Stream = 28". This is what the force-stop left behind. It lasts
  until `exitPreviewAudio` is called (binder from uid 1000 worked) or until a reboot.

Duplicates are not the cause. `startAudioImpl` returns early when 28 is already on top, and
`insertStack` removes an existing 28 before inserting, so one `exitPreviewAudio` always clears it.

Correct order:

1. `requestAudioFocusForCall(4, STREAM_MUSIC, GAIN_TRANSIENT)`
2. `preparePreviewAudio()`, `setMute(3)`, play on session 26
3. stop and release the track
4. `exitPreviewAudio()`: pops 28. If the stack is now empty, no mode is restored here.
5. `abandonAudioFocusForCall(4)`: the previous holder regains focus and its mode, e.g. GPM 23 and session 21.

Doing 4 before 5 is the right order (the order the test used). Reversed, the regain happens
while `mIsPreviewAudio` is still set: it gets through only through the "Keep going" branch, and
`exitPreviewAudio` then still has to run.

If the app dies (crash or force-stop) between 2 and 4, the entry goes stale. An in-app
`try/finally` or `onDestroy` does not cover force-stop or a native crash. Options:

- Do not use preview for playback. Sequence B keeps all its state in the framework focus stack,
  which is cleaned up on binder death, and abandon/death restores the previous source.
- If preview is needed, use a root-side watchdog. Record the app's pid when it calls
  `preparePreviewAudio`. When that process is gone (poll `/proc/<pid>`, or watch `am`
  process-died logs), call `exitPreviewAudio` as uid 1000 (`service call` on the MicomAudioService
  binder, transaction 0x67 on this build). It is harmless when nothing is stale: `endAudioImpl`
  just finds no entry.

## 10. Making unmodified third-party apps audible (analysis, not tested)

Why ordinary apps are silent:

- Plain `requestAudioFocus` means mode 0 -> MICOM 153 (AV off). `track_exclusive=50` would
  let their sessions (>99) through, but AV off mutes the amp path.
- With no focus request at all, the gate of the current source (e.g. 21 for GPM) blocks them.

A tweak can fix the first case without touching the apps.

**Hook point: `ModeManagerService$<CallbackForMode>.onChangeMode(mode, appInfo, isAuto, isChangeAudioMode)`**
in `automotive-service.odex`. This is where every focus-with-mode request lands, with the
requester's `ApplicationInfo`, and it is also called again when the app regains focus
(`FocusEventHandlerDelegate` -> `changeModeToModeManager(clientInfo.mAVMode)`). Rule: if
`mode == 0` and `appInfo` is not a system app (`FLAG_SYSTEM`/`FLAG_UPDATED_SYSTEM_APP` clear,
or not `com.hkmc.*`/`com.android.*`), use 130 (MUSICSTREAMING) and set a flag
"third-party owner".

**Gate: `MicomAudioService.setAudioSessionTrack(mode)`**, same file. For mode 37 with the flag
set, use 50 instead of 29. 50 passes every app-allocated session (>99) and keeps Kia's
fixed-session sources (all ≤49) silent. A value above 50 (e.g. 100, the AudioFlinger
default) would also let a still-running GPM or USB track mix in. Clear the flag on any other
mode. The flag has to be keyed to the requester so Kia's own Kaola/Music Streaming app still
gets 29.

**Unmute:** sequence B needed an explicit `setMute(3)`. The hook has to do the same, for example
by calling MicomAudioService's `setMute(3)` after `EVENT_MODECHANGE_AVAIL` for a flagged
change, as USB Music and GPM do from their side.

Why not hook elsewhere:

- `AudioServiceHelper.changeModeToModeManager` / `RequestAudioFocus_PostProcessing` are in
  `framework.jar`, which is on `BOOTCLASSPATH`. Patching it invalidates the odex of every app
  and jar built against it.
- `automotive-service.jar` is **not** on `BOOTCLASSPATH` (checked on the car). system_server
  loads it separately, so only its own odex changes.
- A pure runtime daemon (watch focus, then `changeMode` plus `track_exclusive=50` through
  binder) needs no patch. It is racy, though: every `setAudioMode` (mode re-send, regain,
  `EVENT_DELAY_DO`) rewrites `track_exclusive`.

Risks:

- Every non-system app that asks for plain focus becomes the AV source, and the previous source
  gets a permanent loss. `onChangeMode` does not get the focus hint, so a transient or ducking
  request (a notification sound, a navigation prompt from a third-party app) would also switch
  sources. Check in logcat whether those requests reach `onChangeMode` at all
  (`RequestAudioFocus_PostProcessing status=... mode=...`). If they do, the hint has to be
  passed down, which means the framework patch above.
- `LastModeController` records mode 130. After a reboot the car may try to restore Music
  Streaming with no app behind it. The same risk applies to sequence B.
- Apps that never abandon focus keep the source until another source takes it.
- Apps that play without asking for focus (many games, SoundPool effects) are still gated by
  the current source's session. Covering them means changing the gate itself:
  `prepareTracks_l` in `libaudioflinger.so` at `0x24416`, letting sessions >99 through when
  `ex ≤ 49`. That would mix all app audio into whatever Android-generated source is playing
  (GPM, USB). It cannot help radio or CarPlay, where the MICOM does not listen to Android.
  It is a native patch of a system library.
- A broken `automotive-service` patch crash-loops system_server, so there is no UI to turn the
  tweak off. It needs the same USB rescue path as the SystemUI tweak, and checksums against the
  stock odex.
- Steering-wheel media keys and the cluster's source display follow MICOM mode 37 / the
  registered media app for mode 130 (Kia's Music Streaming app), not the third-party app.
  Not checked.
