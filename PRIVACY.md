# Privacy policy

Effective November 10, 2026.

Carlink collects no data about you. It has no analytics, no telemetry, no crash reporting, no ads and no accounts, and nothing you do in it is sent to the developer. This policy covers the Carlink app (`com.enigy.carlink3`) and the Carlink USB Bridge.

## What leaves the head unit

Carlink connects to the internet for four things only, and you can turn off the first and the fourth (which is off until you turn it on). A video goes online only when you play one from your iPhone. Fuel prices from Google come only with a key of your own. None goes to the developer, and none carries your name, your phone's name or an account.

| When | What is sent | To |
| --- | --- | --- |
| A song plays | Its title, artist, album and length, to look up its lyrics. This happens even while lyrics are hidden on the music card, so they are ready if you show them. Songs already looked up are read from the head unit instead. Each request also names Carlink: its version and its LRCLIB app ID, the same for every head unit, which identify the app, not you or your car. Turn off Settings › Adapter and about › Look up lyrics, and no song is ever sent. | [LRCLIB](https://lrclib.net) (lrclib.net) |
| You convert or update your adapter, or update the USB Bridge | A request for the installer, update or bridge file. Nothing about you or the adapter is sent. | GitHub (github.com), where the files are published |
| Nearby stations is on (Settings › Vehicle, off by default) and the Fuel card is up | The car's position and a search radius, to find fuel stations around: when the card first shows, then after about 3 miles or 15 minutes, and when the fuel runs low. Carlink doesn't store the position or the stations, only the ids of stations you star. | [OpenStreetMap](https://www.openstreetmap.org) through the Overpass API: overpass-api.de (FOSSGIS), and overpass.private.coffee when that one is busy |
| As above, with Settings › Vehicle › Price source set to Google Places | The same position and radius, with the Google API key you entered, to get each station's fuel prices. Google bills the lookups to your own Google Cloud account; Carlink counts them and stops at the monthly limit you set. Carlink doesn't store what Google sends, except the place ID of a station you star. | Google (places.googleapis.com) |
| You play a video from your iPhone on the car's screen (parked only) | Requests for that video, at the address your iPhone hands Carlink, to stream it. They go through your iPhone's own internet connection when the iPhone offers it, as it usually does, and through the head unit's otherwise. Carlink doesn't store the video or its address. | The site the video comes from, the one your iPhone was playing it from |

Like any website, LRCLIB, GitHub, the Overpass servers and a video's site see the head unit's internet (IP) address when it connects. Their own privacy policies cover what they keep.

## What stays on the head unit

Carlink keeps a few things on the head unit so it works the next time you drive. None of it is uploaded. Uninstalling Carlink deletes all of it except saved logs, which stay in the Downloads folder until you delete them.

| What | Why | How long, and how to remove it |
| --- | --- | --- |
| Your settings, including each iPhone's own settings | So each driver gets their choices back | Until you change them or uninstall |
| Your Google API key, if you enter one for fuel prices | So the Fuel card can ask Google. It's encrypted with a key kept in the head unit's keystore, and Settings shows only its last four characters. | Until you tap Remove in Settings › Vehicle, or uninstall |
| Stations you star on the Fuel card | So the Starred tab shows them. Only each station's id is kept (Google's place ID, or its OpenStreetMap id): no name, address, position or price. | Until you tap the star again, or uninstall |
| Phones the adapter has seen: name, model, Bluetooth address, when last connected | The Phones list, and the order phones connect in | Until you tap Forget on the phone, or uninstall |
| Song lyrics | So songs heard before show their lyrics without going online | Until they fill 100 MB; then the songs played least, and longest ago, are deleted first. Settings › Adapter and about › Clear deletes them all. |
| Album art | So covers show again quickly | In the app's cache; the system or Clear cache removes it |
| Logs | To diagnose problems, and only if you tap Save Logs or turn on Record drive logs (off by default) | In Downloads/Carlink on the head unit; drive logs are deleted after 7 days. Logs hide phone names, Bluetooth addresses, callers and places. They leave the head unit only if you copy them off. |

Carlink does not keep audio, contacts, call history, messages or your routes. The microphone, calls, maps and navigation pass through to and from your iPhone as you use them, and are not stored. The car's position, its engine type, and the vehicle data you share in Settings › Vehicle go to your iPhone the same way, through the adapter, and are not stored either.

## Permissions

| Permission | What Carlink uses it for |
| --- | --- |
| Microphone | Siri and phone calls: your voice goes to your iPhone through the adapter while you talk. Nothing is recorded or kept. |
| Vehicle speed | Holding back features while the vehicle moves (Limit features while driving), and the turn alert. Read on the head unit only. |
| Outside temperature and display units | Showing the temperature in the side pane, in the car's own units; and, if you share it in Settings › Vehicle, giving it to your iPhone |
| Location | Car GPS for the iPhone (Settings › Vehicle, on by default): the head unit's GPS goes to your iPhone through the adapter, to help its own. Nearby stations (off by default): the car's position goes to OpenStreetMap to find fuel stations, as above. Read only while Carlink runs, never stored, sent nowhere else. |
| Vehicle energy and information | The car's engine type (gasoline, diesel or electric), or the one you choose in Settings › Vehicle, goes to your iPhone each time it connects. Range and the low-fuel warning go only if you share them there (off by default). Your iPhone may use them in CarPlay. Never stored. |
| Internet | Lyrics lookups, adapter downloads and nearby stations, as above |
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
