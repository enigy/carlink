package com.enigy.carprobe

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyConfig
import android.car.hardware.property.CarPropertyManager
import android.car.media.CarAudioManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.lang.reflect.Modifier

/**
 * The one-shot questions, asked at each start and on "Snapshot now". Every answer is logged whether it is a yes, a
 * no, or an exception: a refusal is the data point. Nothing here writes to the car; properties are only read.
 */
object Snapshots {
    fun all(
        context: Context,
        car: Car?,
    ) {
        meta(context, car)
        permissions(context)
        car?.let { vehicle(it) }
        cameras(context, car)
        display(context)
        audio(context, car)
        codecs()
        network(context)
        packages(context)
    }

    private fun meta(
        context: Context,
        car: Car?,
    ) {
        ProbeLog.log(
            "meta",
            "what" to "device",
            "fingerprint" to Build.FINGERPRINT,
            "sdk" to Build.VERSION.SDK_INT,
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "automotive" to context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE),
            "carApi" to runCatching { "${Car.API_VERSION_MAJOR_INT}.${Car.API_VERSION_MINOR_INT}" }.getOrElse { it },
            "carConnected" to (car?.isConnected ?: false),
        )
    }

    /** Each permission the probe asks for: its protection level and whether this head unit granted it. */
    fun permissions(context: Context) {
        val pm = context.packageManager
        val asked = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        for (name in asked) {
            val info = runCatching { pm.getPermissionInfo(name, 0) }.getOrNull()
            ProbeLog.log(
                "perm",
                "name" to name,
                "defined" to (info != null),
                "protection" to info?.let { protection(it) },
                "granted" to (context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED),
            )
        }
    }

    private fun protection(info: PermissionInfo): String {
        val base =
            when (info.protection) {
                PermissionInfo.PROTECTION_NORMAL -> "normal"
                PermissionInfo.PROTECTION_DANGEROUS -> "dangerous"
                PermissionInfo.PROTECTION_SIGNATURE -> "signature"
                else -> "level ${info.protection}"
            }
        val flags = info.protectionFlags
        val extra =
            listOfNotNull(
                "privileged".takeIf { flags and PermissionInfo.PROTECTION_FLAG_PRIVILEGED != 0 },
                "appop".takeIf { flags and PermissionInfo.PROTECTION_FLAG_APPOP != 0 },
            )
        return (listOf(base) + extra).joinToString("|")
    }

    /**
     * Item 3: every vehicle property this app can see, with how it can be read and its value now; then every id the car
     * library names, visible or not, so a property the car has but won't show this app can be told from one it lacks
     * (with the permission rows above).
     */
    private fun vehicle(car: Car) {
        val props = runCatching { car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager }.getOrNull()
        if (props == null) {
            ProbeLog.log("vehicle", "what" to "no property service")
            return
        }
        val configs = runCatching { props.propertyList }.getOrElse { emptyList() }
        ProbeLog.log("vehicle", "what" to "visible", "count" to configs.size)
        for (c in configs) {
            val values =
                c.areaIds.associate { area ->
                    area.toString() to runCatching { props.getProperty<Any>(c.propertyId, area)?.let { "${shown(it.value)} (status ${it.status})" } }.getOrElse { it }
                }
            ProbeLog.log(
                "vehicle",
                "what" to "property",
                "id" to c.propertyId,
                "name" to VehiclePropertyIds.toString(c.propertyId),
                "access" to access(c.access),
                "change" to change(c.changeMode),
                "type" to c.propertyType.simpleName,
                "areas" to c.areaIds,
                "rateHz" to "${c.minSampleRate}..${c.maxSampleRate}",
                "values" to values,
            )
        }
        val visible = configs.map { it.propertyId }.toSet()
        val named =
            VehiclePropertyIds::class.java.fields
                .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
                .mapNotNull { f -> runCatching { f.name to f.getInt(null) }.getOrNull() }
        val hidden = named.filter { (_, id) -> id !in visible }.map { (name, id) -> name to runCatching { props.getCarPropertyConfig(id) != null }.getOrElse { it } }
        ProbeLog.log("vehicle", "what" to "named", "count" to named.size, "notVisible" to hidden.map { (n, r) -> "$n=$r" })
    }

    private fun shown(v: Any?): Any? =
        when (v) {
            is Array<*> -> v.toList()
            is IntArray -> v.toList()
            is FloatArray -> v.toList()
            is LongArray -> v.toList()
            is ByteArray -> v.size.toString() + " B"
            else -> v
        }

    private fun access(a: Int): String =
        when (a) {
            CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ -> "read"
            CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_WRITE -> "write"
            CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ_WRITE -> "read_write"
            else -> "none"
        }

    private fun change(m: Int): String =
        when (m) {
            CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_STATIC -> "static"
            CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_ONCHANGE -> "onchange"
            CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS -> "continuous"
            else -> "mode $m"
        }

    /** The camera question (6 Nov): what Camera2 lists, whether EVS answers, and GM's camera apps and what they export. */
    private fun cameras(
        context: Context,
        car: Car?,
    ) {
        val pm = context.packageManager
        val features = listOf("android.hardware.camera", "android.hardware.camera.any", "android.hardware.camera.external", "android.hardware.camera.front")
        ProbeLog.log("camera", "what" to "features", "has" to features.associateWith { pm.hasSystemFeature(it) })
        val cm = context.getSystemService(CameraManager::class.java)
        val ids = runCatching { cm.cameraIdList.toList() }.getOrElse { listOf("error: ${it.message}") }
        ProbeLog.log("camera", "what" to "camera2", "ids" to ids)
        for (id in ids) {
            runCatching {
                val ch = cm.getCameraCharacteristics(id)
                ProbeLog.log(
                    "camera",
                    "what" to "camera2 id",
                    "id" to id,
                    "facing" to ch.get(CameraCharacteristics.LENS_FACING),
                    "level" to ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
                    "capabilities" to ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
                    "pixels" to ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.toString(),
                )
            }.onFailure { ProbeLog.log("camera", "what" to "camera2 id", "id" to id, "error" to it) }
        }
        val evs = runCatching { car?.getCarManager("car_evs_service") }
        ProbeLog.log("camera", "what" to "evs", "manager" to (evs.getOrNull()?.javaClass?.name ?: evs.exceptionOrNull() ?: "null"))
        val apps =
            pm.getInstalledPackages(PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES).filter { p ->
                val n = p.packageName.lowercase()
                listOf("camera", "evs", "rvc", "rearview", "surround", "video").any { it in n }
            }
        for (p in apps) {
            ProbeLog.log(
                "camera",
                "what" to "package",
                "package" to p.packageName,
                "activities" to p.activities?.map { "${it.name}${if (it.exported) " (exported${it.permission?.let { q -> ", needs $q" }.orEmpty()})" else ""}" },
                "services" to p.services?.map { "${it.name}${if (it.exported) " (exported)" else ""}" },
            )
        }
    }

    /** Item 7: what the panel says it can do (modes, HDR, wide colour), as a base for colour calibration. */
    private fun display(context: Context) {
        val dm = context.getSystemService(DisplayManager::class.java)
        for (d in dm.displays) {
            ProbeLog.log(
                "display",
                "what" to "display",
                "id" to d.displayId,
                "name" to d.name,
                "mode" to d.mode.let { "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}" },
                "modes" to d.supportedModes.map { "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}" },
                "hdr" to d.hdrCapabilities?.supportedHdrTypes,
                "wideColorGamut" to d.isWideColorGamut,
                "preferredWideGamut" to runCatching { d.preferredWideGamutColorSpace?.name }.getOrNull(),
                "state" to d.state,
                "flags" to d.flags,
            )
        }
    }

    /** Item 6: the volumes the head unit shows an app, and whether the car's own volume groups answer. */
    fun audio(
        context: Context,
        car: Car?,
    ) {
        val am = context.getSystemService(AudioManager::class.java)
        ProbeLog.log("audio", "what" to "streams", "fixed" to am.isVolumeFixed, "volumes" to volumes(am))
        ProbeLog.log(
            "audio",
            "what" to "outputs",
            "devices" to am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { "${it.type}:${it.productName}:${it.address}" },
        )
        val cam = runCatching { car?.getCarManager(Car.AUDIO_SERVICE) as? CarAudioManager }.getOrNull()
        // System APIs, hidden from the stubs: called by name, so the refusal (or an answer) shows at run time.
        val groups = runCatching { cam?.javaClass?.getMethod("getVolumeGroupCount")?.invoke(cam) }.getOrElse { it.cause ?: it }
        val first =
            runCatching {
                cam?.let { m ->
                    val now = m.javaClass.getMethod("getGroupVolume", Int::class.javaPrimitiveType).invoke(m, 0)
                    val max = m.javaClass.getMethod("getGroupMaxVolume", Int::class.javaPrimitiveType).invoke(m, 0)
                    "$now/$max"
                }
            }.getOrElse { it.cause ?: it }
        ProbeLog.log("audio", "what" to "car audio", "manager" to (cam != null), "groups" to groups, "group0" to first)
    }

    fun volumes(am: AudioManager): Map<String, String> =
        mapOf(
            "music" to AudioManager.STREAM_MUSIC,
            "call" to AudioManager.STREAM_VOICE_CALL,
            "ring" to AudioManager.STREAM_RING,
            "notification" to AudioManager.STREAM_NOTIFICATION,
            "alarm" to AudioManager.STREAM_ALARM,
            "system" to AudioManager.STREAM_SYSTEM,
            "accessibility" to AudioManager.STREAM_ACCESSIBILITY,
        ).mapValues { (_, s) -> runCatching { "${am.getStreamVolume(s)}/${am.getStreamMaxVolume(s)}" }.getOrElse { "error" } }

    /** Items 5 and 7: the video decoders a parked HLS player or a calibration pass would run on. */
    private fun codecs() {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        for (mime in listOf("video/avc", "video/hevc", "audio/mp4a-latm")) {
            val decoders =
                list.filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }.map { c ->
                    val v = runCatching { c.getCapabilitiesForType(mime).videoCapabilities }.getOrNull()
                    val size = v?.let { " ${it.supportedWidths.upper}x${it.supportedHeights.upper}" }.orEmpty()
                    "${c.name}${if (c.isHardwareAccelerated) " hw" else " sw"}$size"
                }
            ProbeLog.log("video", "what" to "decoders", "mime" to mime, "decoders" to decoders)
        }
    }

    /** Item 5: whether an app on this head unit has the internet a parked HLS stream would need. */
    fun network(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val nets =
            cm.allNetworks.map { n ->
                val caps = cm.getNetworkCapabilities(n)
                mapOf(
                    "wifi" to caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    "cellular" to caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                    "ethernet" to caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                    "internet" to caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    "validated" to caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    "notMetered" to caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    "downKbps" to caps?.linkDownstreamBandwidthKbps,
                )
            }
        ProbeLog.log("video", "what" to "networks", "active" to (cm.activeNetwork != null), "networks" to nets)
    }

    private fun packages(context: Context) {
        val gm = context.packageManager.getInstalledPackages(0).map { it.packageName }.filter { it.startsWith("com.gm") }.sorted()
        ProbeLog.log("meta", "what" to "gm packages", "count" to gm.size, "packages" to gm)
    }
}
