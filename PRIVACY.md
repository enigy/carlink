# Privacy policy

Effective October 30, 2026.

Carlink collects no data about you. It has no analytics, no telemetry, no crash reporting, no ads and no accounts, and nothing you do in it is sent to the developer. This policy covers the Carlink app (`com.enigy.carlink3`) and the Carlink USB Bridge.

## What leaves the head unit

Carlink connects to the internet for two things only, and you can turn the first off. Neither goes to the developer, and neither carries your name, your phone's name or an account.

| When | What is sent | To |
| --- | --- | --- |
| A song plays | Its title, artist, album and length, to look up its lyrics. This happens even while lyrics are hidden on the music card, so they are ready if you show them. Songs already looked up are read from the head unit instead. Turn off Settings › Adapter and about › Look up lyrics, and no song is ever sent. | [LRCLIB](https://lrclib.net) (lrclib.net) |
| You convert or update your adapter | A request for the installer or update file. Nothing about you or the adapter is sent. | GitHub (github.com), where the files are published |

Like any website, LRCLIB and GitHub see the head unit's internet (IP) address when it connects. Their own privacy policies cover what they keep.

## What stays on the head unit

Carlink keeps a few things on the head unit so it works the next time you drive. None of it is uploaded. Uninstalling Carlink deletes all of it except saved logs, which stay in the Downloads folder until you delete them.

| What | Why | How long, and how to remove it |
| --- | --- | --- |
| Your settings, including each iPhone's own settings | So each driver gets their choices back | Until you change them or uninstall |
| Phones the adapter has seen: name, model, Bluetooth address, when last connected | The Phones list, and the order phones connect in | Until you tap Forget on the phone, or uninstall |
| Song lyrics | So songs heard before show their lyrics without going online | 30 days each, 500 MB in all at most; Settings › Adapter and about › Clear |
| Album art | So covers show again quickly | In the app's cache; the system or Clear cache removes it |
| Logs | To diagnose problems, and only if you tap Save Logs or turn on Record drive logs (off by default) | In Downloads/Carlink on the head unit; drive logs are deleted after 7 days. Logs hide phone names, Bluetooth addresses, callers and places. They leave the head unit only if you copy them off. |

Carlink does not keep audio, contacts, call history, messages or your routes. The microphone, calls, maps and navigation pass through to and from your iPhone as you use them, and are not stored.

## Permissions

| Permission | What Carlink uses it for |
| --- | --- |
| Microphone | Siri and phone calls: your voice goes to your iPhone through the adapter while you talk. Nothing is recorded or kept. |
| Vehicle speed | Holding back features while the vehicle moves (Limit features while driving), and the turn alert. Read on the head unit only. |
| Outside temperature and display units | Showing the temperature in the side pane, in the car's own units |
| Internet | Lyrics lookups and adapter downloads, as above |
| Notifications | Incoming calls, and setting up the USB Bridge |
| Car templates and surfaces | Turn-by-turn directions on the cluster and HUD |
| Foreground services and wake lock | Keeping CarPlay, music and calls running while Carlink is on screen |

The Carlink USB Bridge only connects Carlink to the adapter over USB. It answers no app but Carlink. It holds the cluster's turn-arrow icons in memory while it runs, and stores nothing.

## Your iPhone, the adapter, the car and Google

Carlink shows what your iPhone, the CarPlay adapter and the car give it. Each of those has its own owner and its own policy:

- **Apple** runs CarPlay on your iPhone. What the iPhone and its apps share with Apple is up to them and your settings.
- **The adapter** keeps its own Bluetooth pairings and Wi-Fi settings. On an OCBM adapter that software is the [OCBM project](https://github.com/enigy/ocbm); on a stock adapter it is Carlinkit's.
- **GM** makes the head unit and its software, including the cluster and HUD that show Carlink's directions.
- **Google** may collect crash and diagnostic reports through Android and Google Play, under Google's policies and your head unit's settings. Carlink adds none of its own.

## Children, changes and contact

Carlink collects nothing from anyone, children included.

If Carlink ever starts sending something new, this policy changes first, in the same release, and the change is listed in that release's notes. The policy's history is this file's history in the [Carlink repository](https://github.com/enigy/carlink).

Questions about privacy: open an issue on the [Carlink repository](https://github.com/enigy/carlink/issues). To report a privacy problem you'd rather not make public, follow [SECURITY.md](SECURITY.md).
