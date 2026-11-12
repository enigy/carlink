// Named for the protocol (tools/proto_check.py and OCBM's docs point here), not for its one object.
@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package com.carlink.ocbm

/**
 * OCBM v1 wire constants — a byte-faithful port of enigy/ocbm `crates/ocbm-proto/src/lib.rs`
 * (reference copy: proto/ocbm-proto.lib.rs).
 *
 * Everything in the OCBM envelope is LITTLE-endian. Numbers OCBM 1.0.5 retired (CH_MFI, CH_IP,
 * CH_ETH, CH_RTSP, CH_ECHO/CH_DISCARD, CT_SRC/CT_ETH_*, CAP_ECHO/MFI/IP/ETH, PM_*_AA, log source 4,
 * seam marker 0x03 and codec 4) are not defined here: an older box never sends one to this app.
 */
object Ocbm {
    // ---- envelope ---------------------------------------------------------------------------

    /** `0x4F43424D` — on the wire, little-endian, this is the byte sequence 4D 42 43 4F. */
    const val MAGIC: Int = 0x4F43424D
    const val HDR_LEN: Int = 16
    const val VERSION: Byte = 1
    const val MAX_PAYLOAD: Int = 65536

    const val F_SOM: Byte = 0x01
    const val F_EOM: Byte = 0x02

    /** Replay of a frame the box already sent (dedupe hint; receivers may ignore it). */
    const val F_REPLAY: Byte = 0x04
    const val F_NEW_SOURCE: Byte = 0x08

    /** Every frame in the tree is sent SOM|EOM; the reassembler pops one frame per header. */
    const val F_BOTH: Byte = 0x03

    // ---- channels ---------------------------------------------------------------------------
    const val CH_CTRL: Int = 0x0000
    const val CH_CONSOLE: Int = 0x0002
    const val CH_FILE: Int = 0x0011
    const val CH_VIDEO: Int = 0x0020
    const val CH_MEDIA_AUDIO: Int = 0x0021
    const val CH_ALT_AUDIO: Int = 0x0022
    const val CH_METADATA: Int = 0x0023
    const val CH_ALT_VIDEO: Int = 0x0024

    /**
     * Box→host: route A's tunnel to the phone's proxy (`setProxyParameters`), framed
     * `[u32 BE "TUNL"][u32 BE len][op][id][data]` like the metadata seam; the TUNNEL_* ops below. Host→box rides
     * CH_INPUT as [INPUT_TUNNEL]. The box connects a tunnel only to the phone. lib.rs:38
     */
    const val CH_TUNNEL: Int = 0x0025
    val TUNNEL_SEAM_MAGIC: ByteArray = byteArrayOf(0x54, 0x55, 0x4E, 0x4C) // "TUNL"
    const val CH_INPUT: Int = 0x0030
    const val CH_MIC: Int = 0x0031
    const val CH_MGMT: Int = 0x0040

    /** Box→host universal-log stream (`/tmp/box.log` + per-daemon logs); armed by CT_LOG_CTL. */
    const val CH_LOG: Int = 0x0042

    fun channelName(ch: Int): String =
        when (ch) {
            CH_CTRL -> "CTRL"
            CH_CONSOLE -> "CONSOLE"
            CH_FILE -> "FILE"
            CH_VIDEO -> "VIDEO"
            CH_MEDIA_AUDIO -> "MEDIA_AUDIO"
            CH_ALT_AUDIO -> "ALT_AUDIO"
            CH_METADATA -> "METADATA"
            CH_ALT_VIDEO -> "ALT_VIDEO"
            CH_TUNNEL -> "TUNNEL"
            CH_INPUT -> "INPUT"
            CH_MIC -> "MIC"
            CH_MGMT -> "MGMT"
            CH_LOG -> "LOG"
            else -> "0x%04x".format(ch)
        }

    // ---- CH_CTRL message types (first payload byte) -------------------------------------------
    const val CT_HELLO: Byte = 0x01
    const val CT_HELLO_ACK: Byte = 0x02
    const val CT_MODE_SELECT: Byte = 0x03
    const val CT_SETTIME: Byte = 0x05
    const val CT_SUBSCRIBE: Byte = 0x10
    const val CT_STOP: Byte = 0x11
    const val CT_HEARTBEAT: Byte = 0x12
    const val CT_SESSION_EVENT: Byte = 0x13
    const val CT_UPLINK: Byte = 0x14
    const val CT_PAIRING_CODE: Byte = 0x15

    /**
     * host->box `[CT_PAIR_CONFIRM][accept u8: 1 = pair, 0 = cancel]` — the user's answer to the
     * CT_PAIRING_CODE prompt. SSP Numeric Comparison needs a real yes/no on BOTH devices, so the box
     * waits for this instead of auto-accepting, for up to 55 s. Any byte != 0 is a yes.
     */
    const val CT_PAIR_CONFIRM: Byte = 0x1C

    /** host->box `[CT_RADIO][0 = radios off now | 1 = radios on if the pushed cfg allows]`. lib.rs:117 */
    const val CT_RADIO: Byte = 0x16

    /**
     * box->host `[CT_BT_PHASE][BTP_*]` — Bluetooth/iAP2 handshake progress. lib.rs:102
     *
     * The host is not in the BT loop (the box owns the radio) and `SEV_PHONE_*` refers to the box's own
     * USB bus, so without this there is NO signal for the entire Bluetooth phase — the UI can only say
     * "waiting" from subscribe until video appears.
     *
     * Advisory and only roughly monotonic: treat an unknown value as progress and never gate on
     * ordering (lib.rs:107-109).
     */
    const val CT_BT_PHASE: Byte = 0x17

    /**
     * box->host `[CT_PHONE_IDENT][utf8 JSON]` — who the connected phone IS. lib.rs:113
     *
     * `{"name","deviceID","model","osName","osVersion"}`, lifted verbatim from the phone's own
     * AirPlay phase-1 SETUP plist. `name` is what the user typed in Settings > General > About >
     * Name; `deviceID` is the BR/EDR MAC, so it joins against MGMT_INFO's bonded list and is the
     * only thing on the wire that says WHICH bonded phone is live. Empty payload = cleared.
     */
    const val CT_PHONE_IDENT: Byte = 0x18

    // BT phase values — lib.rs:110-116.
    const val BTP_IDLE: Byte = 0x00
    const val BTP_LINK_UP: Byte = 0x01
    const val BTP_AUTHENTICATING: Byte = 0x02
    const val BTP_AUTHENTICATED: Byte = 0x03
    const val BTP_IDENTIFYING: Byte = 0x04
    const val BTP_IDENTIFIED: Byte = 0x05
    const val BTP_WIFI_HANDOFF: Byte = 0x06
    const val BTP_PAIR_REJECTED: Byte = 0x07
    const val BTP_BOND_LOST: Byte = 0x08

    fun btpName(b: Byte): String =
        when (b) {
            BTP_IDLE -> "IDLE"
            BTP_LINK_UP -> "LINK_UP"
            BTP_AUTHENTICATING -> "AUTHENTICATING"
            BTP_AUTHENTICATED -> "AUTHENTICATED"
            BTP_IDENTIFYING -> "IDENTIFYING"
            BTP_IDENTIFIED -> "IDENTIFIED"
            BTP_WIFI_HANDOFF -> "WIFI_HANDOFF"
            BTP_PAIR_REJECTED -> "PAIR_REJECTED"
            BTP_BOND_LOST -> "BOND_LOST"
            else -> "BTP_0x%02x".format(b)
        }

    const val MODE_PROJECTION: Byte = 0x00
    const val MODE_CONSOLE: Byte = 0x01

    // ---- session events (payload of CT_SESSION_EVENT) -----------------------------------------
    const val SEV_HOST_PRESENT: Byte = 0x01
    const val SEV_HOST_GONE: Byte = 0x02
    const val SEV_PHONE_PRESENT: Byte = 0x03
    const val SEV_PHONE_ABSENT: Byte = 0x04

    fun sevName(s: Byte): String =
        when (s) {
            SEV_HOST_PRESENT -> "HOST_PRESENT"
            SEV_HOST_GONE -> "HOST_GONE"
            // NB: PHONE_* refer to an iPhone on the BOX's own USB bus (wired path). They say nothing
            // about Bluetooth — that is CT_BT_PHASE's job.
            SEV_PHONE_PRESENT -> "PHONE_PRESENT (box USB bus)"
            SEV_PHONE_ABSENT -> "PHONE_ABSENT (box USB bus)"
            else -> "SEV_0x%02x".format(s)
        }

    // ---- CT_PROJ_MODE (0x19) — which projection mode the box has selected -----------------------
    // Box->host, additive per the OCBM extensibility rules. Payload `[CT_PROJ_MODE][PM_*]`, emitted
    // on CHANGE only and re-armed on every fresh CT_SUBSCRIBE, so a re-attaching host learns the
    // current mode without waiting. Mirrors `box_common::flags::owner().wire_code()`.
    const val CT_PROJ_MODE: Byte = 0x19

    const val PM_NONE: Byte = 0x00 // idle — no projection session; a phone may claim the box
    const val PM_WIRED_CP: Byte = 0x01 // wired CarPlay (projection_up -> iap2d + airplayd)
    const val PM_WIRELESS_CP: Byte = 0x02 // wireless CarPlay (btd: BT + WiFi AP)

    fun pmName(m: Byte): String =
        when (m) {
            PM_NONE -> "NONE"
            PM_WIRED_CP -> "WIRED_CP"
            PM_WIRELESS_CP -> "WIRELESS_CP"
            else -> "PM_0x%02x".format(m)
        }

    // ---- CT_BOX_HEALTH (0x1A) — one bitmask of box-side subsystem liveness ----------------------
    // Box->host telemetry. Read bit 0 (BH_HCI_PRESENT) FIRST when Bluetooth looks dead: a value of
    // 0x50 with bit 0 clear is the signature of a missing radio HAL script — no hci0 was ever
    // created, while OCBM, MFi and CT_SUBSCRIBE all still report success.
    const val CT_BOX_HEALTH: Byte = 0x1A

    const val BH_HCI_PRESENT: Int = 0x01
    const val BH_SSP: Int = 0x02
    const val BH_IAP2D: Int = 0x04
    const val BH_AIRPLAYD: Int = 0x08
    const val BH_CARPLAY_WIRELESS: Int = 0x10
    const val BH_WLAN_AP: Int = 0x20
    const val BH_ROOTFS_OK: Int = 0x40
    const val BH_PAIR_WINDOW: Int = 0x80

    // ---- CT_LOG_CTL (0x1B) — host→box `[CT_LOG_CTL][enabled u8][cap_kb u16 LE]` ----------------
    // Arms the CH_LOG stream: the box tails its universal log (source 0, truncated at cap_kb once
    // streamed) and the supervisor's per-daemon logs (tail-only). Entry layout on CH_LOG:
    // [source u8][flags u8][seq u16 LE][unix_ms u64 LE][len u16 LE][text]. Armed by
    // OcbmClient.setBoxLogStream.
    const val CT_LOG_CTL: Byte = 0x1B
    const val LOG_CAP_DEFAULT_KB: Int = 0x100
    const val LOG_ENTRY_HDR: Int = 0x0E
    const val LOG_MAX_LINE: Int = 0x400
    const val LOG_MAX_FRAME: Int = 0x1000
    const val LOG_F_DROPPED: Int = 0x01
    const val LOG_F_TRUNCATED: Int = 0x02
    const val LOG_F_BACKFILL: Int = 0x04
    const val LOG_SRC_BOX: Int = 0x00
    const val LOG_SRC_AIRPLAYD: Int = 0x01
    const val LOG_SRC_AIRPLAYD_WL: Int = 0x02
    const val LOG_SRC_IAP2D: Int = 0x03
    const val LOG_SRC_RX_CONNECT: Int = 0x05
    const val LOG_SRC_BT: Int = 0x06
    const val LOG_SRC_RADIO_AP_DHCP: Int = 0x07
    const val LOG_SRC_RADIO_BT_ATTACH: Int = 0x08
    const val LOG_SRC_RX_CONNECT_WL: Int = 0x09
    const val LOG_SRC_CARPLAY_WIRELESS: Int = 0x0A
    const val LOG_SRC_INTERNAL: Int = 0xFF

    /** Human-readable bit list for a CT_BOX_HEALTH payload — the log line and the UI detail. */
    fun bhString(f: Int): String {
        val on =
            buildList {
                if (f and BH_HCI_PRESENT != 0) add("HCI")
                if (f and BH_SSP != 0) add("SSP")
                if (f and BH_IAP2D != 0) add("iap2d")
                if (f and BH_AIRPLAYD != 0) add("airplayd")
                if (f and BH_CARPLAY_WIRELESS != 0) add("btd")
                if (f and BH_WLAN_AP != 0) add("hostapd")
                if (f and BH_ROOTFS_OK != 0) add("rootfs-ok")
                if (f and BH_PAIR_WINDOW != 0) add("pair-window")
            }
        return if (on.isEmpty()) "none" else on.joinToString("|")
    }

    fun fileStatusName(s: Byte): String =
        when (s) {
            FILE_OK -> "OK"
            FILE_ERR_OPEN -> "OPEN_FAILED"
            FILE_ERR_VERIFY -> "VERIFY_FAILED"
            FILE_ERR_NOFILE -> "NO_SUCH_FILE"
            FILE_ERR_WRITE -> "IO_FAILED"
            else -> "0x%02x".format(s)
        }

    // NOTE: which health bits a given deployment REQUIRES is app policy, not protocol, and stays in
    // the app.

    // ---- CH_FILE sub-opcodes -------------------------------------------------------------------
    const val FILE_OPEN: Byte = 0x01
    const val FILE_DATA: Byte = 0x02
    const val FILE_CLOSE: Byte = 0x03
    const val FILE_ACK: Byte = 0x04
    const val FILE_PULL: Byte = 0x05

    const val FILE_OK: Byte = 0
    const val FILE_ERR_OPEN: Byte = 1
    const val FILE_ERR_VERIFY: Byte = 2
    const val FILE_ERR_NOFILE: Byte = 3
    const val FILE_ERR_WRITE: Byte = 4

    // ---- capability bits, as reported in CT_HELLO_ACK ------------------------------------------
    const val CAP_CONSOLE: Int = 0x0000_0001
    const val CAP_FILE: Int = 0x0000_0010
    const val CAP_PHONE_POLICY: Int = 0x0000_0040
    const val CAP_PAIR_WINDOW: Int = 0x0000_0080

    /** The box takes [MGMT_INSTALL_CONFIRM] and [MGMT_GO_STOCK] and reports `"install"` in MGMT_INFO. */
    const val CAP_INSTALL: Int = 0x0000_0100

    /** The box takes [MGMT_UPDATE] and reports `"update"` and `"update_result"` in MGMT_INFO. */
    const val CAP_UPDATE: Int = 0x0000_0200

    /** The box takes [MGMT_SWITCH_DEVICE]: Connect on a phone may end another phone's wireless session (enigy/ocbm, 11-01). */
    const val CAP_PHONE_SWITCH: Int = 0x0000_0400

    private const val KNOWN_CAPS: Int =
        CAP_CONSOLE or CAP_FILE or CAP_PHONE_POLICY or CAP_PAIR_WINDOW or CAP_INSTALL or CAP_UPDATE or CAP_PHONE_SWITCH

    fun capsString(caps: Int): String {
        val bits = ArrayList<String>()
        if (caps and CAP_CONSOLE != 0) bits.add("CONSOLE")
        if (caps and CAP_FILE != 0) bits.add("FILE")
        if (caps and CAP_PHONE_POLICY != 0) bits.add("PHONE_POLICY")
        if (caps and CAP_PAIR_WINDOW != 0) bits.add("PAIR_WINDOW")
        if (caps and CAP_INSTALL != 0) bits.add("INSTALL")
        if (caps and CAP_UPDATE != 0) bits.add("UPDATE")
        if (caps and CAP_PHONE_SWITCH != 0) bits.add("PHONE_SWITCH")
        // An older box also sets bits OCBM 1.0.5 retired (0x02, 0x04, 0x08, 0x20): shown, not named.
        val unnamed = caps and KNOWN_CAPS.inv()
        if (unnamed != 0) bits.add("0x%x".format(unnamed))
        return if (bits.isEmpty()) "none" else bits.joinToString("|")
    }

    // ---- CH_MGMT verbs -------------------------------------------------------------------------
    const val MGMT_GET_INFO: Byte = 0x01
    const val MGMT_REBOOT: Byte = 0x02
    const val MGMT_FORGET_ALL: Byte = 0x03
    const val MGMT_FORGET_DEVICE: Byte = 0x04
    const val MGMT_RESTART_WIRELESS: Byte = 0x05
    const val MGMT_ENTER_NCM: Byte = 0x06
    const val MGMT_SET_POLICY: Byte = 0x07
    const val MGMT_CONNECT_DEVICE: Byte = 0x08
    const val MGMT_PAIR_WINDOW: Byte = 0x09

    /**
     * The one-file install's trial becomes `confirmed`; when this session ends the box removes the
     * Carlinkit stack (`final`). Send only while the app shows the way back ([MGMT_GO_STOCK]) for the
     * rest of the session. MGMT_ACK status 0 = confirmed (or already `confirmed`/`final`), 1 = no
     * one-file install on this box.
     */
    const val MGMT_INSTALL_CONFIRM: Byte = 0x0A

    /**
     * While stock is still on the box (`trial` or `confirmed`): ACK status 0, then the box goes back to
     * stock Carlinkit firmware and restarts (it returns as 1314:1520). Status 1 = nothing to go back to.
     */
    const val MGMT_GO_STOCK: Byte = 0x0B

    /**
     * `[MGMT_UPDATE][64 ASCII lower-case hex: the SHA-256 of /tmp/ocbm-update.tgz]`, after the package was
     * pushed there over CH_FILE. ACK status 0: the box starts ocbm_update.sh, which places the update and
     * restarts into its trial; progress and outcome are MGMT_INFO's `"update"` and `"update_result"`.
     * Status 1 = no such package, a different SHA-256, or no update script on the box.
     */
    const val MGMT_UPDATE: Byte = 0x0C

    /**
     * [ascii MAC]: make that bonded phone the projecting one. With another phone's wireless session live, the box
     * ends it, pages this phone, and for 45 s refuses every other phone, so the one just dropped can't re-dial in
     * first. Idle, or already that phone, it is [MGMT_CONNECT_DEVICE]. Never ends a wired session. MGMT_ACK status 1
     * = not a MAC, not bonded, or the box's Bluetooth daemon unreachable. Only with [CAP_PHONE_SWITCH].
     */
    const val MGMT_SWITCH_DEVICE: Byte = 0x0D
    const val MGMT_INFO: Byte = 0x81.toByte()
    const val MGMT_ACK: Byte = 0x82.toByte()

    // ---- CH_INPUT sub-frames (host->box) -------------------------------------------------------
    // Payload is [type u8][...]. lib.rs:183-206.

    /**
     * `[INPUT_TOUCH][phase u8][nx u16 LE][ny u16 LE][finger u8]`. lib.rs:183
     *
     * Coordinates are NORMALIZED 0..=65535 across the display. airplayd scales them to absolute HID
     * coordinates using the SAME resolution it advertised in `/info`, so the box stays the single
     * resolution authority and the host never needs to know the panel size.
     */
    const val INPUT_TOUCH: Byte = 0x01

    /** Ask the box to request an iOS keyframe (video-loss recovery, NOT user input). lib.rs:184 */
    const val INPUT_KEYFRAME: Byte = 0x02

    /** `[INPUT_MEDIA_BTN][index u8]` -> box taps the uid-2 media HID (press+release). lib.rs:197 */
    const val INPUT_MEDIA_BTN: Byte = 0x03

    /** `[INPUT_COMMAND][cmd u8][...]` -> box dispatches the mapped `/command`. lib.rs:198 */
    const val INPUT_COMMAND: Byte = 0x04

    /** `[INPUT_NAV][nav u8]` -> box taps the uid-3 D-Pad. lib.rs:199 */
    const val INPUT_NAV: Byte = 0x05

    /** Force a keyframe on the ALT/cluster stream specifically; a bare INPUT_KEYFRAME only re-IDRs
     *  the main console. lib.rs:185 */
    const val INPUT_KEYFRAME_ALT: Byte = 0x06

    /** `[INPUT_KNOB][flags u8][nudgeX i8][nudgeY i8][rotation i8]` -> uid-4 knob. lib.rs:203 */
    const val INPUT_KNOB: Byte = 0x07

    /** `[INPUT_TELEPHONY][buttonIndex u8]` -> uid-5 telephony HID (report then release). lib.rs:200 */
    const val INPUT_TELEPHONY: Byte = 0x08

    /**
     * `[INPUT_DATASTREAM][stream u32 LE][JSON message]` -> the box sends the message on that shared DataStream
     * (route A: the phone's video Playback channel; receiver::playback). lib.rs:420
     */
    const val INPUT_DATASTREAM: Byte = 0x0A

    /** The JSON message's cap in an INPUT_DATASTREAM sub-frame. lib.rs:424 */
    const val MAX_INPUT_DATASTREAM: Int = 16_384

    /** `[INPUT_TUNNEL][op][id u8][data]` -> the box opens, writes to or closes route A's tunnel `id`. lib.rs:433 */
    const val INPUT_TUNNEL: Byte = 0x0B

    /** The data cap in one TUNNEL_DATA sub-frame. lib.rs:436 */
    const val MAX_INPUT_TUNNEL: Int = 16_384

    // CH_TUNNEL / INPUT_TUNNEL ops. lib.rs:438
    const val TUNNEL_OPEN: Byte = 0x01 // host->box: [port u16 BE][the proxyUrl's host, UTF-8]
    const val TUNNEL_DATA: Byte = 0x02 // both ways: the stream's bytes
    const val TUNNEL_CLOSE: Byte = 0x03 // host->box: close it; box->host: it closed, data = the reason
    const val TUNNEL_OPENED: Byte = 0x04 // box->host: connected to the phone's proxy

    // Touch phases — lib.rs:295-297. NOTE these differ from the legacy riddleBox MultiTouchAction
    // values (UP=0/DOWN=1/MOVE=2); the two enumerations collide numerically but mean different things.
    const val TOUCH_DOWN: Byte = 0x00
    const val TOUCH_MOVE: Byte = 0x01
    const val TOUCH_UP: Byte = 0x02

    // INPUT_MEDIA_BTN indices — the Consumer-array index into the uid-2 descriptor. lib.rs:211.
    // MEDIA-TRANSPORT ONLY; Home/Back/nav are the uid-3 D-Pad. 0 = release (the box completes the pair).
    const val MEDIA_BTN_RELEASE: Byte = 0
    const val MEDIA_BTN_PLAY: Byte = 1
    const val MEDIA_BTN_PAUSE: Byte = 2
    const val MEDIA_BTN_PLAY_PAUSE: Byte = 3
    const val MEDIA_BTN_NEXT: Byte = 4
    const val MEDIA_BTN_PREV: Byte = 5

    // Box b7144f0 (2026-10-27) extends the descriptor to Apple's template plus two: 6 is AC Navigation
    // Guidance (0x029E, no visible effect yet), 7 Random Play (0xB9) and 8 Repeat (0xBC), which reach the
    // playing app's changeShuffleMode / changeRepeatMode. Apple Music cycles both; Spotify ignores them.
    // Carlink sends none of 6 to 8: the owner took the pane's shuffle and repeat out (10-28) because
    // they cannot work for every music app. Kept so this table matches the box's.
    const val MEDIA_BTN_NAV_GUIDANCE: Byte = 6
    const val MEDIA_BTN_RANDOM_PLAY: Byte = 7
    const val MEDIA_BTN_REPEAT: Byte = 8

    // INPUT_NAV actions (uid-3 D-Pad) — lib.rs:216-222.
    const val NAV_UP: Byte = 1
    const val NAV_DOWN: Byte = 2
    const val NAV_LEFT: Byte = 3
    const val NAV_RIGHT: Byte = 4
    const val NAV_SELECT: Byte = 5
    const val NAV_HOME: Byte = 6
    const val NAV_BACK: Byte = 7

    // INPUT_TELEPHONY indices (uid-5) — lib.rs:201-202. DTMF digit d -> TEL_DIGIT0 + d.
    const val TEL_ANSWER: Byte = 1
    const val TEL_FLASH: Byte = 2
    const val TEL_END: Byte = 3
    const val TEL_MUTE: Byte = 4
    const val TEL_DIGIT0: Byte = 5
    const val TEL_STAR: Byte = 15
    const val TEL_POUND: Byte = 16
    const val TEL_DELETE: Byte = 17

    // ---- INPUT_COMMAND values ------------------------------------------------------------------
    const val CMD_REQUEST_UI: Byte = 0x01 // lib.rs:224

    @Deprecated("iOS ignores the bare requestSiri (validated 2026-07-11) — use CMD_SIRI_DOWN/UP")
    const val CMD_REQUEST_SIRI: Byte = 0x02 // lib.rs:227

    const val CMD_SIRI_DOWN: Byte = 0x03 // lib.rs:231
    const val CMD_SIRI_UP: Byte = 0x04 // lib.rs:232
    const val CMD_NAV_START: Byte = 0x05 // lib.rs:238
    const val CMD_NAV_STOP: Byte = 0x06 // lib.rs:239
    const val CMD_NAV_CARD: Byte = 0x07 // lib.rs:243
    const val CMD_LIMITED_UI_ON: Byte = 0x08 // lib.rs:247
    const val CMD_LIMITED_UI_OFF: Byte = 0x09 // lib.rs:248
    const val CMD_NAV_APP: Byte = 0x0A // lib.rs:249
    const val CMD_NAV_APPEARANCE: Byte = 0x0B // lib.rs:255 — `[cmd][flags]`

    const val NAV_APPEARANCE_SPEED_LIMIT: Int = 0x01 // lib.rs:256
    const val NAV_APPEARANCE_COMPASS: Int = 0x02 // lib.rs:257
    const val NAV_APPEARANCE_ETA: Int = 0x04 // lib.rs:258

    const val CMD_NAV_ZOOM_IN: Byte = 0x0C // lib.rs:261
    const val CMD_NAV_ZOOM_OUT: Byte = 0x0D // lib.rs:262

    /**
     * Display appearance. lib.rs:264-278. Wire forms differ per command:
     * ```
     *   [INPUT_COMMAND][CMD_UI_APPEARANCE ][stream][mode]  -> uiAppearanceUpdate
     *   [INPUT_COMMAND][CMD_MAP_APPEARANCE][stream][mode]  -> mapAppearanceUpdate
     *   [INPUT_COMMAND][CMD_NIGHT_MODE    ][on]            -> setNightMode
     * ```
     * `setNightMode` is GLOBAL and advisory — one input alongside iOS's own logic. The per-display
     * commands set a named display's appearance directly, but are silently dropped unless
     * `enablesUIAppearance`/`enablesMapAppearance` were advertised in the pushed config.
     */
    const val CMD_UI_APPEARANCE: Byte = 0x0E // lib.rs:272
    const val CMD_MAP_APPEARANCE: Byte = 0x0F // lib.rs:273
    const val CMD_NIGHT_MODE: Byte = 0x10 // lib.rs:274

    /**
     * `[INPUT_COMMAND][CMD_VIEW_AREA][index u8]` -> `updateViewArea {viewAreaIndex}` — the deterministic
     * form of the CarPlay Dock resize button. The box refuses an index the pushed config did not
     * declare. Index 1 is the split layout's CarPlay area (`VehicleConfigSpec.viewArea2`), 0 the
     * whole panel; `CarlinkManager.requestViewArea` sends it. lib.rs:493
     */
    const val CMD_VIEW_AREA: Byte = 0x11

    /**
     * `[INPUT_COMMAND][CMD_REQUEST_UI_URL][url…]` -> `{type:"requestUI", params:{url}}`: ask iOS to bring
     * a CarPlay app forward on the main screen. The URL is the remaining bytes, UTF-8, no terminator,
     * 1..[REQUEST_UI_URL_MAX] bytes; the box refuses anything else with a log line. Box commit cbdd60b
     * (2026-10-25). lib.rs:521
     */
    const val CMD_REQUEST_UI_URL: Byte = 0x12
    const val REQUEST_UI_URL_MAX: Int = 255

    /**
     * `[INPUT_COMMAND][CMD_VIDEO_ALLOWED][allowed 0|1][VIDEO_STATUS_*]` -> `setVideoPlaybackAllowed{
     * videoPlaybackAllowed, playbackStatus}` to the phone: route A's video may play only while the car is known
     * to be parked, and the phone hears it at the session's start, at play start and at each change, as LIVI
     * sends it. Kept for RECORD when it comes early; a box without route A drops it. Box commit on airplay-video
     * (2026-11-11). lib.rs:524
     */
    const val CMD_VIDEO_ALLOWED: Byte = 0x13

    /**
     * `[INPUT_COMMAND][CMD_VIDEO_SCREEN][borrow 0|1]` -> the box borrows the main screen for the phone's video, or gives
     * it back (`changeModes`, borrowID `VideoPlayback`, as LIVI does), while Carlink's video screen is up. lib.rs:545
     */
    const val CMD_VIDEO_SCREEN: Byte = 0x14

    /** playbackStatus, LIVI's words: nothing queued (`idle`), a queued video allowed to show (`video`), one that may not (`audioOnly`). lib.rs:526 */
    const val VIDEO_STATUS_IDLE: Byte = 0
    const val VIDEO_STATUS_VIDEO: Byte = 1
    const val VIDEO_STATUS_AUDIO_ONLY: Byte = 2

    const val APPEARANCE_STREAM_MAIN: Byte = 0x00 // lib.rs:275
    const val APPEARANCE_STREAM_ALT: Byte = 0x01 // lib.rs:276
    const val APPEARANCE_MODE_LIGHT: Byte = 0x00 // lib.rs:277
    const val APPEARANCE_MODE_DARK: Byte = 0x01 // lib.rs:278

    // ---- Audio seam v2 markers (CH_MEDIA_AUDIO / CH_ALT_AUDIO) ----------------------------------
    // `[u32 BE len][SEAM_MAGIC "SEAV"][marker]…`; 0x00 SEAM_KEY, 0x01 SEAM_PKT, 0x02 SEAM_FORMAT.

    // ---- CH_METADATA seam markers --------------------------------------------------------------
    // Framing is [u32 BE "META"][u32 BE len][marker][payload] where len = 1 + payload.size.
    // Magic per iap2-core/src/metadata.rs:42; markers per lib.rs:36-39. Payloads are PLAINTEXT —
    // the box decrypts the control connection and the host is the trusted consumer over USB.
    val META_SEAM_MAGIC: ByteArray = byteArrayOf(0x4D, 0x45, 0x54, 0x41) // "META"

    /** Raw binary plist of an inbound iPhone `POST /command` ({type, params}) — from airplayd. */
    const val META_CMD: Int = 0x01

    /** One JSON object `{"kind": …}` of iAP2 metadata — from iap2d. */
    const val META_JSON: Int = 0x02

    /** `[artwork id u8][JPEG bytes]` — album art via the iAP2 File-Transfer session. */
    const val META_ARTWORK: Int = 0x03

    /** `[u32 BE display_width_px][PNG bytes]` — iOS's per-resolution corner mask. */
    const val META_CORNERMASK: Int = 0x04

    // ---- timing --------------------------------------------------------------------------------

    /** Host sends CT_HEARTBEAT at this cadence once HELLO_ACK has arrived. */
    const val HEARTBEAT_MS: Long = 1000

    /** Box-side watchdog. Miss this and the box emits SEV_HOST_GONE and clears `subscribed`. */
    const val HEARTBEAT_GRACE_MS: Long = 10_000

    /**
     * Minimum spacing between keyframe requests, matching the macOS host's 500 ms throttle.
     *
     * Load-bearing, not decoration: `VideoSeam.onGap` fires per seq gap AND per decrypt failure, so a
     * wrong key produces one call per frame — 60/s at 60 fps — and a single IDR repaints the whole
     * screen anyway.
     */
    const val KEYFRAME_MIN_INTERVAL_MS: Long = 500

    /**
     * CH_MIC chunk size. Deliberately not MAX_PAYLOAD: 16 KiB is 4-byte aligned so a chunk boundary can
     * never split a 16-bit stereo sample frame, and it matches the transport's WRITE_CHUNK so one mic
     * chunk is exactly one bulk transfer.
     */
    const val MIC_CHUNK: Int = 16384
}
