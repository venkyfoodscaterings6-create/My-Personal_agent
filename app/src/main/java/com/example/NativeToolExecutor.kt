package com.example

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

object InstalledAppCache {

    @Volatile
    private var cachedApps: List<InstalledAppInfo> = emptyList()

    fun getCached(): List<InstalledAppInfo> = cachedApps

    suspend fun refresh(context: Context): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcherIntent, 0)
            }
        }.getOrElse { emptyList() }

        val deduped = LinkedHashMap<String, InstalledAppInfo>()
        for (info in resolved) {
            val pkg = info.activityInfo?.packageName ?: continue
            val label = runCatching { info.loadLabel(pm).toString().trim() }.getOrDefault(pkg)
            if (label.isBlank()) continue
            val normalized = AppNameFuzzyMatcher.normalize(label)
            deduped.putIfAbsent(
                pkg,
                InstalledAppInfo(
                    displayName = label,
                    packageName = pkg,
                    normalizedName = normalized
                )
            )
        }

        val sorted = deduped.values.sortedBy { it.displayName.lowercase() }
        cachedApps = sorted
        sorted
    }

    fun findBestMatch(context: Context, query: String, forceRefresh: Boolean = false): InstalledAppInfo? {
        if (cachedApps.isEmpty() || forceRefresh) {
            val pm = context.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(launcherIntent, 0)
                }
            }.getOrElse { emptyList() }
            cachedApps = resolved.mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                val label = runCatching { info.loadLabel(pm).toString().trim() }.getOrDefault(pkg)
                InstalledAppInfo(label, pkg, AppNameFuzzyMatcher.normalize(label))
            }.distinctBy { it.packageName }
        }
        return AppNameFuzzyMatcher.findBestMatch(query, cachedApps)
    }
}

object NativeToolExecutor {

    fun readPermissionsStatus(context: Context): PermissionsStatus {
        val mic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val accessibility = AgentAccessibilityService.isAccessibilityEnabled(context)

        val overlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }

        val contacts = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        val callPhone = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return PermissionsStatus(
            microphoneGranted = mic,
            accessibilityEnabled = accessibility,
            overlayGranted = overlay,
            contactsGranted = contacts,
            callPhoneGranted = callPhone,
            notificationsGranted = notifications
        )
    }

    fun executeDirectTool(context: Context, toolCall: RoutedToolCall): ActionExecutionResult {
        return when (toolCall.tool) {
            ToolName.OPEN_APP -> openApp(context, toolCall.appName.orEmpty())
            ToolName.SEARCH_YOUTUBE -> searchYouTube(context, toolCall.query.orEmpty())
            ToolName.SEARCH_WEB -> searchWeb(context, toolCall.query.orEmpty())
            ToolName.OPEN_MAPS -> openMaps(context, toolCall.destination ?: toolCall.query.orEmpty())
            ToolName.SET_ALARM -> setAlarm(
                context = context,
                hour = toolCall.hour ?: 7,
                minute = toolCall.minute ?: 0,
                label = toolCall.label
            )
            ToolName.SET_TIMER -> setTimer(
                context = context,
                seconds = toolCall.seconds ?: 60,
                label = toolCall.label
            )
            ToolName.OPEN_SETTING -> openSetting(context, toolCall.settingName.orEmpty())
            ToolName.CALL_CONTACT -> callContact(context, toolCall.contactOrNumber.orEmpty())
            ToolName.SEND_MESSAGE -> {
                val res = openMessageComposer(
                    context = context,
                    contactOrNumber = toolCall.contactOrNumber.orEmpty(),
                    app = toolCall.messagingApp.orEmpty(),
                    text = toolCall.messageText.orEmpty()
                )
                ActionExecutionResult(res.success, res.summary)
            }
            ToolName.PRESS_HOME -> performGoHome(context)
            ToolName.PRESS_BACK -> performGoBack()
            ToolName.OPEN_RECENTS -> performOpenRecents()
            ToolName.OPEN_NOTIFICATIONS -> performOpenNotifications()
            ToolName.OPEN_QUICK_SETTINGS -> performOpenQuickSettings()
            ToolName.SET_VOLUME -> setVolume(
                context = context,
                stream = toolCall.stream ?: "media",
                levelPercent = toolCall.levelPercent ?: 50
            )
            ToolName.BOOK_RIDE -> openRideApp(context, toolCall.appName ?: "Uber").first
            else -> ActionExecutionResult(false, "Tool \"${toolCall.tool}\" requires multi-step execution.")
        }
    }

    data class ResolvedAppLaunch(
        val displayName: String,
        val packageName: String
    )

    fun resolveAppLaunch(context: Context, rawQuery: String): ResolvedAppLaunch? {
        val query = rawQuery.trim()
        if (query.isBlank()) return null

        val pm = context.packageManager

        // Priority 1: Exact built-in package aliases (supports both English & Telugu script)
        for (hintPkg in AppNameFuzzyMatcher.aliasPackageHints(query)) {
            val directIntent = pm.getLaunchIntentForPackage(hintPkg)
            if (directIntent != null) {
                return ResolvedAppLaunch(
                    displayName = FastPathMatcher.prettifyAppName(query),
                    packageName = hintPkg
                )
            }
        }

        val matched = InstalledAppCache.findBestMatch(context, query, forceRefresh = false)
            ?: InstalledAppCache.findBestMatch(context, query, forceRefresh = true)
            ?: return null

        return if (pm.getLaunchIntentForPackage(matched.packageName) != null) {
            ResolvedAppLaunch(
                displayName = matched.displayName,
                packageName = matched.packageName
            )
        } else {
            null
        }
    }

    fun openRideApp(context: Context, rideAppName: String): Pair<ActionExecutionResult, String?> {
        val prettyApp = HybridToolRouting.normalizeRideAppName(rideAppName)
        val targetPkg = HybridToolRouting.ridePackageForApp(prettyApp)
        val pm = context.packageManager
        val directIntent = pm.getLaunchIntentForPackage(targetPkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        if (directIntent != null) {
            return runCatching {
                context.startActivity(directIntent)
                ActionExecutionResult(true, "Opened $prettyApp") to targetPkg
            }.getOrElse { err ->
                ActionExecutionResult(false, "Could not open $prettyApp: ${err.message}") to targetPkg
            }
        }

        val resolved = resolveAppLaunch(context, prettyApp)
            ?: return ActionExecutionResult(false, "$prettyApp is not installed on this device.") to targetPkg
        val launchIntent = pm.getLaunchIntentForPackage(resolved.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        } ?: return ActionExecutionResult(false, "$prettyApp cannot be launched.") to resolved.packageName

        return runCatching {
            context.startActivity(launchIntent)
            ActionExecutionResult(true, "Opened ${resolved.displayName}") to resolved.packageName
        }.getOrElse { err ->
            ActionExecutionResult(false, "Could not open ${resolved.displayName}: ${err.message}") to resolved.packageName
        }
    }

    fun openApp(context: Context, rawQuery: String): ActionExecutionResult {
        val query = rawQuery.trim()
        if (query.isBlank()) {
            return ActionExecutionResult(false, "No app name provided.")
        }

        val pm = context.packageManager
        val resolved = resolveAppLaunch(context, query)
            ?: return ActionExecutionResult(false, "Could not find installed app matching \"$query\".")

        val launchIntent = pm.getLaunchIntentForPackage(resolved.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        } ?: return ActionExecutionResult(false, "App \"${resolved.displayName}\" cannot be launched.")

        return runCatching {
            context.startActivity(launchIntent)
            ActionExecutionResult(true, "Opened ${resolved.displayName}")
        }.getOrElse { err ->
            ActionExecutionResult(false, "Failed to open ${resolved.displayName}: ${err.message}")
        }
    }

    fun searchYouTube(context: Context, rawQuery: String): ActionExecutionResult {
        val query = rawQuery.trim()
        if (query.isBlank()) {
            return ActionExecutionResult(false, "No YouTube search query provided.")
        }
        val uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))
        val targeted = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.youtube")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(targeted)
            ActionExecutionResult(true, "Opened YouTube search for \"$query\"")
        }.getOrElse {
            // Fallback to browser / any YouTube handler if official package isn't installed
            val generic = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                context.startActivity(generic)
                ActionExecutionResult(true, "Opened YouTube search for \"$query\"")
            }.getOrElse { err ->
                ActionExecutionResult(false, "Could not open YouTube search: ${err.message}")
            }
        }
    }

    fun searchWeb(context: Context, rawQuery: String): ActionExecutionResult {
        val query = rawQuery.trim()
        if (query.isBlank()) return ActionExecutionResult(false, "No search query provided.")
        val uri = Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            ActionExecutionResult(true, "Searched the web for \"$query\"")
        }.getOrElse { err ->
            ActionExecutionResult(false, "Could not open web search: ${err.message}")
        }
    }

    fun openMaps(context: Context, rawDestination: String): ActionExecutionResult {
        val destination = rawDestination.trim()
        if (destination.isBlank()) return ActionExecutionResult(false, "No destination provided.")

        val navIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("google.navigation:q=" + Uri.encode(destination))
        ).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(navIntent)
            ActionExecutionResult(true, "Opened Maps for \"$destination\"")
        }.getOrElse {
            val geoIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("geo:0,0?q=" + Uri.encode(destination))
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                context.startActivity(geoIntent)
                ActionExecutionResult(true, "Opened Maps for \"$destination\"")
            }.getOrElse { err ->
                ActionExecutionResult(false, "Could not open Maps: ${err.message}")
            }
        }
    }

    fun setAlarm(context: Context, hour: Int, minute: Int, label: String?): ActionExecutionResult {
        val safeHour = hour.coerceIn(0, 23)
        val safeMinute = minute.coerceIn(0, 59)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, safeHour)
            putExtra(AlarmClock.EXTRA_MINUTES, safeMinute)
            if (!label.isNullOrBlank()) {
                putExtra(AlarmClock.EXTRA_MESSAGE, label.trim())
            }
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val formatted = String.format("%02d:%02d", safeHour, safeMinute)
        return runCatching {
            context.startActivity(intent)
            ActionExecutionResult(true, "Set alarm for $formatted${label?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}")
        }.getOrElse { err ->
            ActionExecutionResult(false, "Could not set alarm: ${err.message}")
        }
    }

    fun setTimer(context: Context, seconds: Int, label: String?): ActionExecutionResult {
        val safeSeconds = seconds.coerceAtLeast(1)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, safeSeconds)
            if (!label.isNullOrBlank()) {
                putExtra(AlarmClock.EXTRA_MESSAGE, label.trim())
            }
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            ActionExecutionResult(true, "Started timer for ${safeSeconds}s${label?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}")
        }.getOrElse { err ->
            ActionExecutionResult(false, "Could not set timer: ${err.message}")
        }
    }

    fun openSetting(context: Context, rawName: String): ActionExecutionResult {
        val key = rawName.trim().lowercase()
        val (action, label) = when {
            key.contains("wi-fi") || key.contains("wifi") || key.contains("wi fi") ->
                Settings.ACTION_WIFI_SETTINGS to "Wi-Fi Settings"
            key.contains("bluetooth") ->
                Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth Settings"
            key.contains("sound") || key.contains("volume") || key.contains("ringtone") ->
                Settings.ACTION_SOUND_SETTINGS to "Sound Settings"
            key.contains("display") || key.contains("bright") || key.contains("screen") ->
                Settings.ACTION_DISPLAY_SETTINGS to "Display Settings"
            key.contains("battery") || key.contains("power") ->
                Intent.ACTION_POWER_USAGE_SUMMARY to "Battery Settings"
            key.contains("location") || key.contains("gps") ->
                Settings.ACTION_LOCATION_SOURCE_SETTINGS to "Location Settings"
            key.contains("airplane") ->
                Settings.ACTION_AIRPLANE_MODE_SETTINGS to "Airplane Mode Settings"
            key.contains("accessibility") ->
                Settings.ACTION_ACCESSIBILITY_SETTINGS to "Accessibility Settings"
            else ->
                Settings.ACTION_SETTINGS to "Settings"
        }

        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            ActionExecutionResult(true, "Opened $label")
        }.getOrElse {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionExecutionResult(true, "Opened Settings")
            }.getOrElse { err ->
                ActionExecutionResult(false, "Could not open $label: ${err.message}")
            }
        }
    }

    fun resolveContactNumber(context: Context, nameOrNumber: String): Pair<String, String>? {
        val trimmed = nameOrNumber.trim()
        if (trimmed.isBlank()) return null

        // Direct phone number entered
        val digitsOnly = trimmed.filter { it.isDigit() || it == '+' }
        if (digitsOnly.count { it.isDigit() } >= 6 && trimmed.none { it.isLetter() }) {
            return trimmed to digitsOnly
        }

        // Look up via ContactsContract if READ_CONTACTS is granted
        val hasContactsPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasContactsPerm) return null

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$trimmed%")

        return runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0).orEmpty().ifBlank { trimmed }
                    val number = cursor.getString(1).orEmpty().trim()
                    if (number.isNotBlank()) name to number else null
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    fun callContact(context: Context, nameOrNumber: String): ActionExecutionResult {
        val resolved = resolveContactNumber(context, nameOrNumber)
            ?: return ActionExecutionResult(
                false,
                "Could not find a phone number for \"$nameOrNumber\". Grant Contacts permission in Setup & Test or include the phone number."
            )

        val (displayName, rawNumber) = resolved
        val cleanNumber = rawNumber.filter { it.isDigit() || it == '+' }
        val hasCallPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val intent = Intent(
            if (hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL,
            Uri.parse("tel:" + Uri.encode(cleanNumber))
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // Immediately stop & block all TTS right before dialing/calling so agent never talks over calls
        AudioSilenceGuard.markCallStarting()
        AgentSpeechFeedback.stopSpeaking()

        return runCatching {
            context.startActivity(intent)
            if (hasCallPerm) {
                ActionExecutionResult(true, "Calling $displayName")
            } else {
                ActionExecutionResult(true, "Opened dialer for $displayName — grant Phone Calls permission in Setup & Test for direct dialing.")
            }
        }.getOrElse { err ->
            ActionExecutionResult(false, "Could not start call to $displayName: ${err.message}")
        }
    }

    data class MessageComposerLaunchResult(
        val success: Boolean,
        val displayName: String,
        val isWhatsApp: Boolean,
        val summary: String
    )

    fun openMessageComposer(
        context: Context,
        contactOrNumber: String,
        app: String,
        text: String
    ): MessageComposerLaunchResult {
        val resolved = resolveContactNumber(context, contactOrNumber)
        val displayName = resolved?.first ?: contactOrNumber.ifBlank { "recipient" }
        val number = resolved?.second?.filter { it.isDigit() || it == '+' }.orEmpty()
        val preferWhatsApp = app.lowercase().contains("whatsapp") || app.lowercase() == "wa"

        if (number.isBlank()) {
            return MessageComposerLaunchResult(
                success = false,
                displayName = displayName,
                isWhatsApp = preferWhatsApp,
                summary = "Could not find a phone number for \"$contactOrNumber\". Grant Contacts permission in Setup & Test or include the phone number."
            )
        }

        if (preferWhatsApp) {
            val digitsForWa = number.filter { it.isDigit() }
            val waUrl = "https://wa.me/$digitsForWa?text=" + Uri.encode(text)
            val uri = Uri.parse(waUrl)

            // Prefer official WhatsApp or WhatsApp Business directly so no browser chooser slows it down
            val directPackages = listOf("com.whatsapp", "com.whatsapp.w4b")
            for (pkg in directPackages) {
                val targeted = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val launched = runCatching {
                    context.startActivity(targeted)
                    true
                }.getOrDefault(false)
                if (launched) {
                    return MessageComposerLaunchResult(
                        success = true,
                        displayName = displayName,
                        isWhatsApp = true,
                        summary = "Opened WhatsApp chat for $displayName"
                    )
                }
            }

            val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return runCatching {
                context.startActivity(fallbackIntent)
                MessageComposerLaunchResult(
                    success = true,
                    displayName = displayName,
                    isWhatsApp = true,
                    summary = "Opened WhatsApp chat for $displayName"
                )
            }.getOrElse { err ->
                MessageComposerLaunchResult(
                    success = false,
                    displayName = displayName,
                    isWhatsApp = true,
                    summary = "Could not open WhatsApp chat: ${err.message}"
                )
            }
        }

        val smsUri = Uri.parse("smsto:" + Uri.encode(number))
        val smsIntent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
            putExtra("sms_body", text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(smsIntent)
            MessageComposerLaunchResult(
                success = true,
                displayName = displayName,
                isWhatsApp = false,
                summary = "Opened SMS chat for $displayName"
            )
        }.getOrElse { err ->
            MessageComposerLaunchResult(
                success = false,
                displayName = displayName,
                isWhatsApp = false,
                summary = "Could not open SMS app: ${err.message}"
            )
        }
    }

    fun performGoHome(context: Context): ActionExecutionResult {
        val service = AgentAccessibilityService.getInstance()
        if (service != null) {
            val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            if (ok) return ActionExecutionResult(true, "Returned to Home screen")
        }
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(homeIntent)
            ActionExecutionResult(true, "Returned to Home screen")
        }.getOrElse { err ->
            ActionExecutionResult(false, "Could not return to Home screen: ${err.message}")
        }
    }

    fun performGoBack(): ActionExecutionResult {
        val service = AgentAccessibilityService.getInstance()
            ?: return ActionExecutionResult(
                false,
                "Enable \"My Personal Agent\" in Android Accessibility Settings (Setup & Test -> Permissions Center) to use Go Back."
            )
        val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        return ActionExecutionResult(ok, if (ok) "Pressed Back" else "Could not press Back")
    }

    fun performOpenRecents(): ActionExecutionResult {
        val service = AgentAccessibilityService.getInstance()
            ?: return ActionExecutionResult(
                false,
                "Enable \"My Personal Agent\" in Android Accessibility Settings to open Recent Apps."
            )
        val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        return ActionExecutionResult(ok, if (ok) "Opened Recent Apps" else "Could not open Recent Apps")
    }

    fun performOpenNotifications(): ActionExecutionResult {
        val service = AgentAccessibilityService.getInstance()
            ?: return ActionExecutionResult(
                false,
                "Enable \"My Personal Agent\" in Android Accessibility Settings to open Notifications."
            )
        val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        return ActionExecutionResult(ok, if (ok) "Opened Notifications" else "Could not open Notifications")
    }

    fun performOpenQuickSettings(): ActionExecutionResult {
        val service = AgentAccessibilityService.getInstance()
            ?: return ActionExecutionResult(
                false,
                "Enable \"My Personal Agent\" in Android Accessibility Settings to open Quick Settings."
            )
        val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        return ActionExecutionResult(ok, if (ok) "Opened Quick Settings" else "Could not open Quick Settings")
    }

    fun setVolume(context: Context, stream: String, levelPercent: Int): ActionExecutionResult {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ActionExecutionResult(false, "Audio service is unavailable on this device.")
        val clamped = levelPercent.coerceIn(0, 100)
        val (streamType, streamLabel) = when (stream.trim().lowercase()) {
            "ring", "ringtone", "ringer" -> AudioManager.STREAM_RING to "ring"
            "alarm" -> AudioManager.STREAM_ALARM to "alarm"
            "notification", "notifications" -> AudioManager.STREAM_NOTIFICATION to "notification"
            "call", "voice" -> AudioManager.STREAM_VOICE_CALL to "call"
            else -> AudioManager.STREAM_MUSIC to "media"
        }
        return runCatching {
            val maxVol = audioManager.getStreamMaxVolume(streamType).coerceAtLeast(1)
            val targetVol = ((maxVol * clamped) / 100f).roundToInt().coerceIn(0, maxVol)
            audioManager.setStreamVolume(streamType, targetVol, AudioManager.FLAG_SHOW_UI)
            val label = if (clamped == 0) "Muted $streamLabel volume" else "Set $streamLabel volume to $clamped%"
            ActionExecutionResult(true, label)
        }.getOrElse { err ->
            ActionExecutionResult(false, "Could not change $streamLabel volume: ${err.message}")
        }
    }
}
