package com.example

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.abs

data class ScreenNode(
    val index: Int,
    val text: String,
    val contentDescription: String,
    val aggregatedLabel: String,
    val className: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val centerX: Int,
    val centerY: Int,
    val topY: Int,
    val heightPx: Int,
    val viewIdResourceName: String = ""
)

data class ScreenSnapshot(
    val packageName: String,
    val nodes: List<ScreenNode>,
    val compactSummary: String,
    val treeHash: Int
)

data class ActionExecutionResult(
    val success: Boolean,
    val summary: String
)

data class ServiceCompletionEvent(
    val eventId: String = UUID.randomUUID().toString(),
    val actionId: String,
    val status: ApprovalStatus,
    val completedSteps: Int,
    val message: String,
    val notes: String,
    val durationLabel: String?,
    val blockedReason: String? = null,
    val isError: Boolean = false
)

data class ServiceExecutionState(
    val isRunning: Boolean = false,
    val actionId: String? = null,
    val phaseLabel: String = "",
    val phaseStartMillis: Long = 0L,
    val completedSteps: Int = 0,
    val activeStep: Int = -1,
    val completionEvent: ServiceCompletionEvent? = null
)

class AgentStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_STOP_AGENT) {
            AgentAccessibilityService.stopActiveExecution(context)
        }
    }

    companion object {
        const val ACTION_STOP_AGENT = "com.example.ACTION_STOP_AGENT"
    }
}

object AgentNotificationHelper {
    private const val CHANNEL_ID = "agent_control_status"
    private const val NOTIFICATION_ID = 4107

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun ensureChannel(context: Context): NotificationManager? {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Personal Agent Quick Trigger & Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows the Ask Agent trigger and live Stop Agent controls."
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }
        return manager
    }

    private fun buildAskAgentPendingIntent(context: Context): PendingIntent {
        val askIntent = Intent(context, VoiceOverlayActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        }
        return PendingIntent.getActivity(
            context,
            104,
            askIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun showReadyNotification(context: Context) {
        if (!canPostNotifications(context)) return
        val manager = ensureChannel(context) ?: return
        val askPendingIntent = buildAskAgentPendingIntent(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_robot_tile)
            .setContentTitle("My Personal Agent")
            .setContentText("Tap Ask Agent to control your phone from any app.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(askPendingIntent)
            .addAction(
                R.drawable.ic_robot_tile,
                "Ask Agent",
                askPendingIntent
            )
            .build()

        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    fun showRunningNotification(context: Context, statusText: String) {
        if (!canPostNotifications(context)) return
        val manager = ensureChannel(context) ?: return

        val askPendingIntent = buildAskAgentPendingIntent(context)
        val stopIntent = Intent(context, AgentStopReceiver::class.java).apply {
            action = AgentStopReceiver.ACTION_STOP_AGENT
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_robot_tile)
            .setContentTitle("My Personal Agent Running")
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(askPendingIntent)
            .addAction(
                R.drawable.ic_robot_tile,
                "Ask Agent",
                askPendingIntent
            )
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop Agent",
                stopPendingIntent
            )
            .build()

        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    fun syncPersistentNotification(
        context: Context,
        enabled: Boolean,
        isRunning: Boolean = false,
        runningText: String = ""
    ) {
        when {
            isRunning -> showRunningNotification(
                context,
                runningText.ifBlank { "Running on-device task…" }
            )
            enabled -> showReadyNotification(context)
            else -> cancelRunningNotification(context)
        }
    }

    fun cancelRunningNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }
}

class AgentAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val networkClient = NetworkClient()
    private val localStorage by lazy { LocalStorage(applicationContext) }
    private var activeTaskJob: Job? = null
    private var lastCapturedNodes: List<ScreenNode> = emptyList()
    @Volatile
    private var latestVoiceSettings: VoiceSettings = VoiceSettings()
    @Volatile
    private var lastCompletedActionId: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
        _isServiceConnected.value = true

        serviceScope.launch {
            localStorage.stateFlow.collect { persisted ->
                latestVoiceSettings = persisted.voiceSettings
                AgentNotificationHelper.syncPersistentNotification(
                    context = this@AgentAccessibilityService,
                    enabled = persisted.voiceSettings.persistentAskNotificationEnabled,
                    isRunning = _executionState.value.isRunning,
                    runningText = _executionState.value.phaseLabel
                )
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()?.trim().orEmpty()
            if (isTrackableExternalPackage(pkg, packageName)) {
                lastExternalForegroundPackage = pkg
            }
        }
    }

    private fun isTrackableExternalPackage(pkg: String, ownPkg: String): Boolean {
        if (pkg.isBlank() || pkg == ownPkg) return false
        val lower = pkg.lowercase()
        if (lower == "android" || lower == "com.android.systemui") return false
        if (lower.contains("inputmethod") || lower.contains("keyboard") || lower.contains("gboard") || lower.contains("swiftkey")) {
            return false
        }
        return true
    }

    private fun currentVisibleExternalPackage(): String? {
        val ownPkg = packageName
        val rootPkg = runCatching { rootInActiveWindow?.packageName?.toString()?.trim() }.getOrNull().orEmpty()
        if (isTrackableExternalPackage(rootPkg, ownPkg)) {
            lastExternalForegroundPackage = rootPkg
            return rootPkg
        }
        val windowPkg = runCatching {
            windows?.firstOrNull { win ->
                val p = win.root?.packageName?.toString()?.trim().orEmpty()
                isTrackableExternalPackage(p, ownPkg)
            }?.root?.packageName?.toString()?.trim()
        }.getOrNull().orEmpty()
        if (windowPkg.isNotBlank()) {
            lastExternalForegroundPackage = windowPkg
            return windowPkg
        }
        return lastExternalForegroundPackage.takeIf { it.isNotBlank() }
            ?: rootPkg.takeIf { it.isNotBlank() }
    }

    private fun speakImmediateAck(phrase: String) {
        if (phrase.isBlank()) return
        if (!latestVoiceSettings.speakRepliesAloud) return
        AgentSpeechFeedback.speak(
            context = applicationContext,
            text = phrase,
            voiceSettings = latestVoiceSettings
        )
    }

    override fun onInterrupt() {
        stopExecutionInternal()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopExecutionInternal()
        if (instanceRef.get() === this) {
            instanceRef = WeakReference(null)
        }
        _isServiceConnected.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopExecutionInternal()
        serviceScope.cancel()
        if (instanceRef.get() === this) {
            instanceRef = WeakReference(null)
        }
        _isServiceConnected.value = false
        super.onDestroy()
    }

    private fun updateOverlayAndNotification(statusText: String) {
        AgentNotificationHelper.showRunningNotification(this, statusText)
    }

    private fun dismissOverlayAndNotification() {
        AgentNotificationHelper.syncPersistentNotification(
            context = this,
            enabled = latestVoiceSettings.persistentAskNotificationEnabled,
            isRunning = false
        )
    }

    internal fun stopExecutionInternal() {
        stopFlag.set(true)
        activeTaskJob?.cancel()
        activeTaskJob = null
        dismissOverlayAndNotification()
    }

    /**
     * Background-safe WhatsApp & SMS Message Sending workflow (runs ONLY after user taps Approve):
     * 1. Open WhatsApp (wa.me) or SMS (smsto:) chat with recipient & exact text.
     * 2. Poll up to 4 seconds (every 500ms) for the messaging app to finish loading.
     * 3. Locate the Send button by text, contentDescription, or viewIdResourceName (e.g., com.whatsapp:id/send).
     * 4. Tap via ACTION_CLICK with dispatchGesture center (x, y) fallback.
     * 5. Wait 1 second and verify the input box cleared before reporting "Message sent to <name>".
     */
    fun startSendMessage(
        plan: ActionPlan,
        contactOrNumber: String,
        messagingApp: String,
        messageText: String,
        blockSensitive: Boolean
    ) {
        activeTaskJob?.cancel()
        clearStopRequest()

        val startMillis = System.currentTimeMillis()
        val totalPlanSteps = plan.steps.size.coerceAtLeast(3)

        activeTaskJob = serviceScope.launch {
            val appLabel = if (messagingApp.contains("whatsapp", ignoreCase = true) || messagingApp.equals("wa", ignoreCase = true)) {
                "WhatsApp"
            } else {
                "Messages"
            }
            val openPhase = "Opening chat for $contactOrNumber…"
            publishRunningState(plan.actionId, openPhase, startMillis, completedSteps = 0, activeStep = 0)
            updateOverlayAndNotification(openPhase)
            speakImmediateAck("On it, opening $appLabel…")

            try {
                val launch = withContext(Dispatchers.Main) {
                    NativeToolExecutor.openMessageComposer(
                        context = this@AgentAccessibilityService,
                        contactOrNumber = contactOrNumber,
                        app = messagingApp,
                        text = messageText
                    )
                }

                if (!launch.success) {
                    val elapsed = formatElapsed(System.currentTimeMillis() - startMillis)
                    emitToolResult(
                        actionId = plan.actionId,
                        result = ToolExecutionResult.Failed(launch.summary),
                        completedSteps = 0,
                        elapsed = elapsed
                    )
                    return@launch
                }

                val waitPhase = "Waiting for ${if (launch.isWhatsApp) "WhatsApp" else "SMS"} to load…"
                publishRunningState(plan.actionId, waitPhase, startMillis, completedSteps = 1, activeStep = 1)
                updateOverlayAndNotification(waitPhase)

                var sendNode: ScreenNode? = null
                var messagingAppOpened = false
                val ownPkg = packageName

                // Poll every 500ms up to 4 seconds (8 ticks)
                for (poll in 0 until 8) {
                    if (!isActive || isStopRequested()) break
                    delay(500L)
                    if (!isActive || isStopRequested()) break

                    val snap = withContext(Dispatchers.Main) { captureCurrentScreenSnapshot() }
                    if (blockSensitive) {
                        val violation = SafetyGuardrail.detectSensitiveScreen(
                            packageName = snap.packageName,
                            ownPackageName = ownPkg,
                            nodes = snap.nodes
                        )
                        if (violation != null) {
                            emitBlocked(plan.actionId, violation)
                            return@launch
                        }
                    }

                    if (snap.packageName == ownPkg || snap.packageName == "unknown") continue
                    messagingAppOpened = true

                    val candidate = SendButtonMatcher.findBestSendButton(snap.nodes)
                        ?: withContext(Dispatchers.Main) { findSendButtonInLiveTree() }
                    if (candidate != null) {
                        val hasLoadedDraft = hasUnsentDraftText(snap, messageText) ||
                            withContext(Dispatchers.Main) { hasUnsentDraftTextInLiveTree(messageText) }
                        if (hasLoadedDraft || poll >= 2) {
                            sendNode = candidate
                            break
                        }
                    }
                }

                if (!isActive || isStopRequested()) {
                    emitCancelled(plan.actionId, "Stopped before sending message.")
                    return@launch
                }

                if (!messagingAppOpened) {
                    val elapsed = formatElapsed(System.currentTimeMillis() - startMillis)
                    emitToolResult(
                        actionId = plan.actionId,
                        result = ToolExecutionResult.Failed("Messaging app did not open on screen"),
                        completedSteps = 0,
                        elapsed = elapsed
                    )
                    return@launch
                }

                if (sendNode == null) {
                    val elapsed = formatElapsed(System.currentTimeMillis() - startMillis)
                    emitToolResult(
                        actionId = plan.actionId,
                        result = ToolExecutionResult.PartialSuccess(
                            whatWorked = "typed the message",
                            whatFailed = "tap the Send button"
                        ),
                        completedSteps = 1,
                        elapsed = elapsed,
                        durationTagOverride = "Needs manual tap · $elapsed"
                    )
                    return@launch
                }

                val tapPhase = "Tapping Send…"
                publishRunningState(plan.actionId, tapPhase, startMillis, completedSteps = 2, activeStep = 2)
                updateOverlayAndNotification(tapPhase)

                val tapRes = performSmartTap(sendNode, sendNode.centerX, sendNode.centerY)
                if (!tapRes.success && !isStopRequested()) {
                    dispatchTapGesture(sendNode.centerX.toFloat(), sendNode.centerY.toFloat())
                }

                // Wait 1 second (10 x 100ms interruptible ticks) and verify editable box cleared
                for (tick in 0 until 10) {
                    if (!isActive || isStopRequested()) break
                    delay(100L)
                }
                if (!isActive || isStopRequested()) {
                    emitCancelled(plan.actionId, "Stopped while verifying message delivery.")
                    return@launch
                }

                var afterSnap = withContext(Dispatchers.Main) { captureCurrentScreenSnapshot() }
                var textCleared = !hasUnsentDraftText(afterSnap, messageText) &&
                    !withContext(Dispatchers.Main) { hasUnsentDraftTextInLiveTree(messageText) }

                // If draft text is still sitting in the composer box, retry once with direct coordinate gesture
                if (!textCleared && !isStopRequested()) {
                    val retrySend = SendButtonMatcher.findBestSendButton(afterSnap.nodes)
                        ?: withContext(Dispatchers.Main) { findSendButtonInLiveTree() }
                        ?: sendNode
                    dispatchTapGesture(retrySend.centerX.toFloat(), retrySend.centerY.toFloat())
                    for (tick in 0 until 10) {
                        if (!isActive || isStopRequested()) break
                        delay(100L)
                    }
                    afterSnap = withContext(Dispatchers.Main) { captureCurrentScreenSnapshot() }
                    textCleared = !hasUnsentDraftText(afterSnap, messageText) &&
                        !withContext(Dispatchers.Main) { hasUnsentDraftTextInLiveTree(messageText) }
                }

                val elapsed = formatElapsed(System.currentTimeMillis() - startMillis)
                if (textCleared) {
                    emitToolResult(
                        actionId = plan.actionId,
                        result = ToolExecutionResult.VerifiedSuccess("Message sent to ${launch.displayName}"),
                        completedSteps = totalPlanSteps,
                        elapsed = elapsed,
                        durationTagOverride = "Sent · $elapsed"
                    )
                } else {
                    emitToolResult(
                        actionId = plan.actionId,
                        result = ToolExecutionResult.PartialSuccess(
                            whatWorked = "typed the message",
                            whatFailed = "tap the Send button"
                        ),
                        completedSteps = 2,
                        elapsed = elapsed,
                        durationTagOverride = "Unverified · $elapsed"
                    )
                }
            } catch (_: CancellationException) {
                emitCancelled(plan.actionId, "Stopped by user.")
            } catch (e: Exception) {
                val elapsed = formatElapsed(System.currentTimeMillis() - startMillis)
                emitToolResult(
                    actionId = plan.actionId,
                    result = ToolExecutionResult.Failed(e.localizedMessage ?: "Unexpected error sending message"),
                    completedSteps = 0,
                    elapsed = elapsed
                )
            } finally {
                dismissOverlayAndNotification()
            }
        }
    }

    private fun hasUnsentDraftText(snapshot: ScreenSnapshot, expectedMessage: String): Boolean {
        val needle = expectedMessage.trim().lowercase().take(24)
        if (needle.isBlank()) return false
        val editables = snapshot.nodes.filter { it.isEditable }
        if (editables.isEmpty()) return false
        return editables.any { node ->
            val current = node.text.trim().lowercase()
            current.isNotBlank() && (
                current.contains(needle) ||
                    (needle.length >= 6 && current.contains(needle.take(6)))
                )
        }
    }

    /**
     * Full uncapped tree scan specifically for editable composer text so long WhatsApp/SMS chats
     * with dozens of prior message bubbles never hide the bottom composer field.
     */
    private fun hasUnsentDraftTextInLiveTree(expectedMessage: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val needle = expectedMessage.trim().lowercase().take(24)
        if (needle.isBlank()) return false

        fun walk(node: AccessibilityNodeInfo?, depth: Int): Boolean {
            if (node == null || depth > 24 || !node.isVisibleToUser) return false
            if (node.isEditable) {
                val current = node.text?.toString()?.trim()?.lowercase().orEmpty()
                if (current.isNotBlank() && (
                        current.contains(needle) ||
                            (needle.length >= 6 && current.contains(needle.take(6)))
                        )
                ) {
                    return true
                }
            }
            for (i in 0 until node.childCount) {
                if (walk(node.getChild(i), depth + 1)) return true
            }
            return false
        }

        return walk(root, 0)
    }

    /**
     * Full uncapped tree scan specifically for WhatsApp/SMS Send buttons (by text, contentDescription,
     * or viewIdResourceName such as com.whatsapp:id/send) so long chats never miss the Send button.
     */
    private fun findSendButtonInLiveTree(): ScreenNode? {
        val root = rootInActiveWindow ?: return null
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels.coerceAtLeast(1)
        val screenH = metrics.heightPixels.coerceAtLeast(1)
        val rect = Rect()
        var bestNode: ScreenNode? = null
        var bestScore = 64

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 24 || !node.isVisibleToUser) return
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val viewId = runCatching { node.viewIdResourceName?.toString().orEmpty() }.getOrDefault("")
            val score = SendButtonMatcher.scoreCandidate(
                text = text,
                contentDescription = desc,
                viewIdResourceName = viewId,
                isClickable = node.isClickable
            )
            if (score > bestScore) {
                node.getBoundsInScreen(rect)
                val validBounds = rect.width() > 6 && rect.height() > 6 &&
                    rect.right > 0 && rect.bottom > 0 &&
                    rect.left < screenW && rect.top < screenH
                if (validBounds) {
                    bestScore = score
                    bestNode = ScreenNode(
                        index = 0,
                        text = text.take(64),
                        contentDescription = desc.take(64),
                        aggregatedLabel = desc.ifBlank { text }.ifBlank { "Send" },
                        className = node.className?.toString()?.substringAfterLast('.').orEmpty(),
                        isClickable = node.isClickable,
                        isEditable = node.isEditable,
                        centerX = rect.centerX().coerceIn(1, screenW - 1),
                        centerY = rect.centerY().coerceIn(1, screenH - 1),
                        topY = rect.top,
                        heightPx = rect.height(),
                        viewIdResourceName = viewId
                    )
                }
            }
            for (i in 0 until node.childCount) {
                walk(node.getChild(i), depth + 1)
            }
        }

        walk(root, 0)
        return bestNode
    }

    /**
     * Direct on-screen tap tool: searches the full visible Accessibility tree for a matching text or
     * contentDescription (case-insensitive partial match), respecting SafetyGuardrail, then taps it.
     */
    fun startTapOnScreen(
        plan: ActionPlan,
        targetText: String,
        blockSensitive: Boolean,
        initialDelayMs: Long = 240L
    ) {
        activeTaskJob?.cancel()
        clearStopRequest()
        val startMillis = System.currentTimeMillis()

        activeTaskJob = serviceScope.launch {
            val phase = "Finding \"$targetText\" on screen…"
            publishRunningState(plan.actionId, phase, startMillis, 0, 0)
            updateOverlayAndNotification(phase)
            try {
                if (initialDelayMs > 0L) delay(initialDelayMs)
                if (isStopRequested()) {
                    emitCancelled(plan.actionId, "Stopped by user.")
                    return@launch
                }

                val beforeSnap = withContext(Dispatchers.Main) { captureCurrentScreenSnapshot() }
                if (blockSensitive) {
                    val violation = SafetyGuardrail.detectSensitiveViolation(targetText)
                        ?: SafetyGuardrail.detectSensitiveScreen(
                            packageName = beforeSnap.packageName,
                            ownPackageName = packageName,
                            nodes = beforeSnap.nodes
                        )
                    if (violation != null) {
                        emitBlocked(plan.actionId, violation)
                        return@launch
                    }
                }

                val match = withContext(Dispatchers.Main) { findNodeMatchingTargetText(targetText) }
                    ?: beforeSnap.nodes.firstOrNull { node ->
                        node.text.contains(targetText, ignoreCase = true) ||
                            node.contentDescription.contains(targetText, ignoreCase = true) ||
                            node.aggregatedLabel.contains(targetText, ignoreCase = true)
                    }

                if (match == null) {
                    val elapsed = formatElapsed(System.currentTimeMillis() - startMillis)
                    emitToolResult(
                        actionId = plan.actionId,
                        result = ToolExecutionResult.Failed("Couldn't find or interact with that element on screen"),
                        completedSteps = 0,
                        elapsed = elapsed,
                        durationTagOverride = "Not found · $elapsed"
                    )
                    return@launch
                }

                val tapRes = performSmartTap(match, match.centerX, match.centerY)
                delay(450L)
                val afterSnap = withContext(Dispatchers.Main) { captureCurrentScreenSnapshot() }
                val screenChanged = afterSnap.treeHash != beforeSnap.treeHash ||
                    afterSnap.packageName != beforeSnap.packageName
                val finalElapsed = formatElapsed(System.currentTimeMillis() - startMillis)
                val matchedLabel = match.text.ifBlank { match.contentDescription }
                    .ifBlank { match.aggregatedLabel }.ifBlank { targetText }.take(44)

                if (tapRes.success && screenChanged) {
                    emitToolResult(
                        actionId = plan.actionId,
                        result = ToolExecutionResult.VerifiedSuccess("Tapped \"$matchedLabel\""),
                        completedSteps = plan.steps.size.coerceAtLeast(1),
                        elapsed = finalElapsed,
                        durationTagOverride = "Tapped · $finalElapsed"
                    )
                } else {
                    emitToolResult(
                        actionId = plan.actionId,
                        result = ToolExecutionResult.Failed("Couldn't find or interact with that element on screen"),
                        completedSteps = 0,
                        elapsed = finalElapsed
                    )
                }
            } catch (_: CancellationException) {
                emitCancelled(plan.actionId, "Stopped by user.")
            } catch (e: Exception) {
                val elapsed = formatElapsed(System.currentTimeMillis() - startMillis)
                emitToolResult(
                    actionId = plan.actionId,
                    result = ToolExecutionResult.Failed(e.localizedMessage ?: "Couldn't interact with that element on screen"),
                    completedSteps = 0,
                    elapsed = elapsed
                )
            } finally {
                dismissOverlayAndNotification()
            }
        }
    }

    private fun findNodeMatchingTargetText(targetText: String): ScreenNode? {
        val root = rootInActiveWindow ?: return null
        val needle = targetText.trim().lowercase()
        if (needle.isBlank()) return null

        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels.coerceAtLeast(1)
        val screenH = metrics.heightPixels.coerceAtLeast(1)
        val rect = Rect()
        var bestNode: ScreenNode? = null
        var bestScore = 0

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 24 || !node.isVisibleToUser) return
            val text = node.text?.toString()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            val tLower = text.lowercase()
            val dLower = desc.lowercase()

            var score = 0
            when {
                tLower == needle || dLower == needle -> score = 120
                tLower.startsWith(needle) || dLower.startsWith(needle) -> score = 95
                tLower.contains(needle) || dLower.contains(needle) -> score = 75
            }

            if (score > 0) {
                if (node.isClickable) score += 25
                node.getBoundsInScreen(rect)
                val validBounds = rect.width() > 6 && rect.height() > 6 &&
                    rect.right > 0 && rect.bottom > 0 &&
                    rect.left < screenW && rect.top < screenH
                if (validBounds && score > bestScore) {
                    bestScore = score
                    bestNode = ScreenNode(
                        index = 0,
                        text = text.take(64),
                        contentDescription = desc.take(64),
                        aggregatedLabel = text.ifBlank { desc }.ifBlank { targetText },
                        className = node.className?.toString()?.substringAfterLast('.').orEmpty(),
                        isClickable = node.isClickable,
                        isEditable = node.isEditable,
                        centerX = rect.centerX().coerceIn(1, screenW - 1),
                        centerY = rect.centerY().coerceIn(1, screenH - 1),
                        topY = rect.top,
                        heightPx = rect.height(),
                        viewIdResourceName = runCatching { node.viewIdResourceName?.toString().orEmpty() }.getOrDefault("")
                    )
                }
            }
            for (i in 0 until node.childCount) {
                walk(node.getChild(i), depth + 1)
            }
        }

        walk(root, 0)
        return bestNode
    }

    /**
     * Background-safe direct scroll gesture (waits briefly so VoiceOverlayActivity finishes closing).
     */
    fun startDirectScroll(plan: ActionPlan, direction: String) {
        activeTaskJob?.cancel()
        clearStopRequest()
        val startMillis = System.currentTimeMillis()

        activeTaskJob = serviceScope.launch {
            publishRunningState(plan.actionId, "Scrolling $direction…", startMillis, 0, 0)
            try {
                delay(240L)
                if (isStopRequested()) {
                    emitCancelled(plan.actionId, "Stopped by user.")
                    return@launch
                }
                val beforeSnap = withContext(Dispatchers.Main) { captureCurrentScreenSnapshot() }
                val res = performScroll(direction)
                delay(450L)
                val afterSnap = withContext(Dispatchers.Main) { captureCurrentScreenSnapshot() }
                val screenChanged = afterSnap.treeHash != beforeSnap.treeHash ||
                    afterSnap.packageName != beforeSnap.packageName
                val elapsed = formatElapsed(System.currentTimeMillis() - startMillis)

                if (res.success && screenChanged) {
                    emitToolResult(
                        actionId = plan.actionId,
                        result = ToolExecutionResult.VerifiedSuccess(res.summary),
                        completedSteps = plan.steps.size.coerceAtLeast(1),
                        elapsed = elapsed,
                        durationTagOverride = "${res.summary} · $elapsed"
                    )
                } else {
                    emitToolResult(
                        actionId = plan.actionId,
                        result = ToolExecutionResult.Failed("Couldn't find or interact with that element on screen"),
                        completedSteps = 0,
                        elapsed = elapsed
                    )
                }
            } catch (_: CancellationException) {
                emitCancelled(plan.actionId, "Stopped by user.")
            } catch (e: Exception) {
                val elapsed = formatElapsed(System.currentTimeMillis() - startMillis)
                emitToolResult(
                    actionId = plan.actionId,
                    result = ToolExecutionResult.Failed(e.localizedMessage ?: "Scroll failed"),
                    completedSteps = 0,
                    elapsed = elapsed
                )
            } finally {
                dismissOverlayAndNotification()
            }
        }
    }

    private suspend fun waitForForegroundPackage(
        expectedPackage: String?,
        appDisplayName: String? = null,
        timeoutMs: Long = 2500L
    ): Boolean {
        val ticks = (timeoutMs / 250L).toInt().coerceAtLeast(1)
        for (i in 0 until ticks) {
            if (isStopRequested()) return false
            val activePkg = withContext(Dispatchers.Main) {
                rootInActiveWindow?.packageName?.toString()?.trim().orEmpty()
            }
            if (activePkg.isNotBlank() && activePkg != packageName) {
                val exactMatch = !expectedPackage.isNullOrBlank() &&
                    activePkg.equals(expectedPackage, ignoreCase = true)
                val nameMatch = !appDisplayName.isNullOrBlank() &&
                    HybridToolRouting.isPackageMatchingAppName(activePkg, appDisplayName)
                if (exactMatch || nameMatch) {
                    lastExternalForegroundPackage = activePkg
                    return true
                }
            }
            delay(250L)
        }
        return false
    }

    private suspend fun executeVerifiedOpenApp(appName: String): ToolExecutionResult {
        val resolved = withContext(Dispatchers.Main) {
            NativeToolExecutor.resolveAppLaunch(this@AgentAccessibilityService, appName)
        } ?: return ToolExecutionResult.Failed("Could not find installed app matching \"$appName\"")

        val currentPkg = withContext(Dispatchers.Main) {
            rootInActiveWindow?.packageName?.toString()?.trim().orEmpty()
        }
        if (currentPkg.equals(resolved.packageName, ignoreCase = true)) {
            return ToolExecutionResult.VerifiedSuccess("${resolved.displayName} is already open")
        }

        val launchRes = withContext(Dispatchers.Main) {
            NativeToolExecutor.openApp(this@AgentAccessibilityService, appName)
        }
        if (!launchRes.success) {
            return ToolExecutionResult.Failed(launchRes.summary)
        }

        val switched = waitForForegroundPackage(
            expectedPackage = resolved.packageName,
            appDisplayName = resolved.displayName,
            timeoutMs = 2500L
        )
        return if (switched) {
            ToolExecutionResult.VerifiedSuccess("Opened ${resolved.displayName}")
        } else {
            ToolExecutionResult.Failed("App did not open on screen")
        }
    }

    /**
     * Background-safe direct native tool runner with strict 3-state post-action verification.
     */
    fun startDirectAction(plan: ActionPlan, toolCall: RoutedToolCall) {
        activeTaskJob?.cancel()
        clearStopRequest()
        val startMillis = System.currentTimeMillis()

        activeTaskJob = serviceScope.launch {
            val label = toolCall.reason.ifBlank { plan.title }
            publishRunningState(plan.actionId, label, startMillis, 0, 0)
            try {
                delay(180L)
                if (isStopRequested()) {
                    emitCancelled(plan.actionId, "Stopped by user.")
                    return@launch
                }

                val result: ToolExecutionResult = if (toolCall.tool == ToolName.OPEN_APP) {
                    executeVerifiedOpenApp(toolCall.appName.orEmpty())
                } else {
                    val res = withContext(Dispatchers.Main) {
                        NativeToolExecutor.executeDirectTool(this@AgentAccessibilityService, toolCall)
                    }
                    if (res.success) {
                        ToolExecutionResult.VerifiedSuccess(res.summary)
                    } else {
                        ToolExecutionResult.Failed(res.summary)
                    }
                }

                val elapsed = formatElapsed(System.currentTimeMillis() - startMillis)
                emitToolResult(
                    actionId = plan.actionId,
                    result = result,
                    completedSteps = if (result.isVerifiedSuccess) plan.steps.size.coerceAtLeast(1) else 0,
                    elapsed = elapsed
                )
            } catch (_: CancellationException) {
                emitCancelled(plan.actionId, "Stopped by user.")
            } catch (e: Exception) {
                val elapsed = formatElapsed(System.currentTimeMillis() - startMillis)
                emitToolResult(
                    actionId = plan.actionId,
                    result = ToolExecutionResult.Failed(e.localizedMessage ?: "Unexpected error"),
                    completedSteps = 0,
                    elapsed = elapsed
                )
            } finally {
                dismissOverlayAndNotification()
            }
        }
    }

    /**
     * Ride booking workflow (Uber / Ola / Rapido) with mandatory Safe Final Check:
     * 1. Launch Uber / Ola / Rapido directly and verify foreground switch.
     * 2. Hand off to ui_automate to tap "Where to?" / destination box, type destination,
     *    select matching drop location, and reach ride-selection screen.
     * 3. STOP strictly before tapping Confirm / Book / Pay and speak aloud the safe handoff prompt.
     */
    fun startBookRide(
        plan: ActionPlan,
        rideApp: String?,
        destination: String?,
        provider: AiProvider?,
        blockSensitive: Boolean,
        conversationHistory: List<ChatMessage> = emptyList(),
        initialDelayMs: Long = 160L
    ) {
        activeTaskJob?.cancel()
        clearStopRequest()

        val startMillis = System.currentTimeMillis()
        val prettyApp = HybridToolRouting.normalizeRideAppName(rideApp)
        val targetPkg = HybridToolRouting.ridePackageForApp(prettyApp)
        val cleanDest = destination?.trim().orEmpty()

        activeTaskJob = serviceScope.launch {
            val openPhase = "Opening $prettyApp…"
            publishRunningState(plan.actionId, openPhase, startMillis, completedSteps = 0, activeStep = 0)
            updateOverlayAndNotification(openPhase)
            speakImmediateAck("On it, opening $prettyApp…")

            try {
                if (initialDelayMs > 0L) delay(initialDelayMs)
                if (isStopRequested()) {
                    emitCancelled(plan.actionId, "Stopped by user.")
                    return@launch
                }

                val alreadyInRideApp = withContext(Dispatchers.Main) {
                    val cur = rootInActiveWindow?.packageName?.toString()?.trim().orEmpty()
                    cur.equals(targetPkg, ignoreCase = true) ||
                        HybridToolRouting.isPackageMatchingAppName(cur, prettyApp)
                }

                var openedPkg: String? = targetPkg
                if (!alreadyInRideApp) {
                    val (launchRes, pkg) = withContext(Dispatchers.Main) {
                        NativeToolExecutor.openRideApp(this@AgentAccessibilityService, prettyApp)
                    }
                    openedPkg = pkg
                    if (!launchRes.success) {
                        val elapsed = formatElapsed(System.currentTimeMillis() - startMillis)
                        emitToolResult(
                            actionId = plan.actionId,
                            result = ToolExecutionResult.Failed(launchRes.summary),
                            completedSteps = 0,
                            elapsed = elapsed
                        )
                        return@launch
                    }

                    val switched = waitForForegroundPackage(
                        expectedPackage = openedPkg,
                        appDisplayName = prettyApp,
                        timeoutMs = 2500L
                    )
                    if (!switched) {
                        val elapsed = formatElapsed(System.currentTimeMillis() - startMillis)
                        emitToolResult(
                            actionId = plan.actionId,
                            result = ToolExecutionResult.Failed("$prettyApp did not open on screen"),
                            completedSteps = 0,
                            elapsed = elapsed
                        )
                        return@launch
                    }
                }

                if (cleanDest.isBlank()) {
                    val elapsed = formatElapsed(System.currentTimeMillis() - startMillis)
                    emitToolResult(
                        actionId = plan.actionId,
                        result = ToolExecutionResult.PartialSuccess(
                            whatWorked = "opened $prettyApp",
                            whatFailed = "set a destination because no drop location was specified"
                        ),
                        completedSteps = 1,
                        elapsed = elapsed,
                        durationTagOverride = "Destination needed · $elapsed"
                    )
                    return@launch
                }

                // Wait briefly for Uber/Ola/Rapido home screen widgets to render
                delay(700L)
                if (isStopRequested()) {
                    emitCancelled(plan.actionId, "Stopped by user.")
                    return@launch
                }

                val automationGoal = "You are inside $prettyApp ($openedPkg). Do NOT relaunch $prettyApp. " +
                    "Tap the 'Where to?' / destination input box, type \"$cleanDest\", select the matching drop location suggestion, " +
                    "and navigate to the ride-selection / fare preview screen. " +
                    "STOP immediately once fares/ride options appear and return done — NEVER tap Confirm, Book, Request, or Pay."

                runAiAutomationLoop(
                    plan = plan,
                    goal = automationGoal,
                    provider = provider,
                    blockSensitive = blockSensitive,
                    maxSteps = 12,
                    loopStartMillis = startMillis,
                    initialHistory = listOf("Opened $prettyApp on screen"),
                    conversationHistory = conversationHistory,
                    stopBeforeConfirmButton = true,
                    rideBookingAppName = prettyApp,
                    fallbackPartialOnFail = "opened $prettyApp" to "set your destination to $cleanDest"
                )
            } catch (_: CancellationException) {
                emitCancelled(plan.actionId, "Stopped by user.")
            } catch (e: Exception) {
                val elapsed = formatElapsed(System.currentTimeMillis() - startMillis)
                emitToolResult(
                    actionId = plan.actionId,
                    result = ToolExecutionResult.Failed(e.localizedMessage ?: "Unexpected error while opening $prettyApp"),
                    completedSteps = 0,
                    elapsed = elapsed
                )
            } finally {
                dismissOverlayAndNotification()
            }
        }
    }

    /**
     * Background-safe dedicated YouTube Play workflow:
     * 1. Open YouTube search results directly.
     * 2. Poll every 500ms up to 4s until video cards appear.
     * 3. Skip Sponsored/Ad/filter chips/nav tabs and tap the first real playable video.
     * 4. Wait 1.5s and verify video player opened (or fall back seamlessly into AI loop).
     */
    fun startPlayYouTubeVideo(
        plan: ActionPlan,
        query: String,
        provider: AiProvider?,
        blockSensitive: Boolean,
        conversationHistory: List<ChatMessage> = emptyList()
    ) {
        activeTaskJob?.cancel()
        clearStopRequest()

        val startMillis = System.currentTimeMillis()
        val totalPlanSteps = plan.steps.size.coerceAtLeast(4)

        activeTaskJob = serviceScope.launch {
            val launchPhase = "Opening YouTube for \"$query\"…"
            publishRunningState(plan.actionId, launchPhase, startMillis, completedSteps = 0, activeStep = 0)
            updateOverlayAndNotification(launchPhase)
            speakImmediateAck("Playing that on YouTube…")

            try {
                val launchRes = withContext(Dispatchers.Main) {
                    NativeToolExecutor.searchYouTube(this@AgentAccessibilityService, query)
                }
                if (!launchRes.success) {
                    val elapsed = formatElapsed(System.currentTimeMillis() - startMillis)
                    emitToolResult(
                        actionId = plan.actionId,
                        result = ToolExecutionResult.Failed(launchRes.summary),
                        completedSteps = 0,
                        elapsed = elapsed
                    )
                    return@launch
                }

                // Step 2: Poll every 500ms up to 4s (8 ticks) until YouTube search results load
                val waitPhase = "Waiting for YouTube results…"
                publishRunningState(plan.actionId, waitPhase, startMillis, completedSteps = 1, activeStep = 1)
                updateOverlayAndNotification(waitPhase)

                var candidateVideo: ScreenNode? = null
                var latestSnapshot: ScreenSnapshot? = null
                var youtubeOpened = false
                val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(1)

                for (poll in 0 until 8) {
                    if (!isActive || isStopRequested()) break
                    delay(500L)
                    if (!isActive || isStopRequested()) break

                    val snap = withContext(Dispatchers.Main) { captureCurrentScreenSnapshot() }
                    latestSnapshot = snap

                    if (blockSensitive) {
                        val violation = SafetyGuardrail.detectSensitiveScreen(
                            packageName = snap.packageName,
                            ownPackageName = packageName,
                            nodes = snap.nodes
                        )
                        if (violation != null) {
                            emitBlocked(plan.actionId, violation)
                            return@launch
                        }
                    }

                    val isYouTubeOrBrowser = snap.packageName.contains("youtube", ignoreCase = true) ||
                        snap.packageName.contains("chrome", ignoreCase = true) ||
                        snap.packageName.contains("browser", ignoreCase = true)

                    if (isYouTubeOrBrowser) {
                        youtubeOpened = true
                        if (snap.nodes.size >= 4) {
                            candidateVideo = findFirstPlayableYouTubeVideo(snap, screenH)
                            if (candidateVideo != null) break
                        }
                    }
                }

                if (!isActive || isStopRequested()) {
                    emitCancelled(plan.actionId, "Stopped while waiting for YouTube results.")
                    return@launch
                }

                if (!youtubeOpened) {
                    val elapsed = formatElapsed(System.currentTimeMillis() - startMillis)
                    emitToolResult(
                        actionId = plan.actionId,
                        result = ToolExecutionResult.Failed("YouTube did not open on screen"),
                        completedSteps = 0,
                        elapsed = elapsed
                    )
                    return@launch
                }

                var playbackConfirmed = false
                var tappedLabel = ""

                if (candidateVideo != null) {
                    tappedLabel = candidateVideo.aggregatedLabel
                        .ifBlank { candidateVideo.text }
                        .ifBlank { candidateVideo.contentDescription }
                        .take(56)
                    val tapPhase = "Tapping video: ${tappedLabel.take(28)}…"
                    publishRunningState(plan.actionId, tapPhase, startMillis, completedSteps = 2, activeStep = 2)
                    updateOverlayAndNotification(tapPhase)

                    val tapResult = performSmartTap(
                        targetNode = candidateVideo,
                        fallbackX = candidateVideo.centerX,
                        fallbackY = candidateVideo.centerY
                    )

                    if (tapResult.success && !isStopRequested()) {
                        val verifyPhase = "Confirming video playback…"
                        publishRunningState(plan.actionId, verifyPhase, startMillis, completedSteps = 3, activeStep = 3)
                        updateOverlayAndNotification(verifyPhase)

                        // Wait 1.5s interruptibly for player screen to settle
                        for (tick in 0 until 15) {
                            if (!isActive || isStopRequested()) break
                            delay(100L)
                        }
                        if (!isActive || isStopRequested()) {
                            emitCancelled(plan.actionId, "Stopped after tapping video.")
                            return@launch
                        }

                        val afterSnap = withContext(Dispatchers.Main) { captureCurrentScreenSnapshot() }
                        playbackConfirmed = isYouTubePlayerVisible(afterSnap, latestSnapshot)
                    }
                }

                if (playbackConfirmed) {
                    val elapsed = formatElapsed(System.currentTimeMillis() - startMillis)
                    val detail = if (tappedLabel.isNotBlank()) {
                        "Playing \"$tappedLabel\" on YouTube"
                    } else {
                        "Playing \"$query\" on YouTube"
                    }
                    emitToolResult(
                        actionId = plan.actionId,
                        result = ToolExecutionResult.VerifiedSuccess(detail),
                        completedSteps = totalPlanSteps,
                        elapsed = elapsed,
                        durationTagOverride = "Playing · $elapsed"
                    )
                    return@launch
                }

                // Fallback into AI UI loop (supports both custom provider and Built-in Gemini when provider == null)
                if (isActive && !isStopRequested()) {
                    runAiAutomationLoop(
                        plan = plan,
                        goal = "YouTube search results for \"$query\" are open. Do NOT relaunch YouTube. Tap the first playable non-ad video and verify the video player opens.",
                        provider = provider,
                        blockSensitive = blockSensitive,
                        maxSteps = 5,
                        loopStartMillis = startMillis,
                        initialHistory = listOf(
                            "Opened YouTube search for \"$query\""
                        ) + if (tappedLabel.isNotBlank()) listOf("Attempted tap on \"$tappedLabel\"") else emptyList(),
                        conversationHistory = conversationHistory,
                        fallbackPartialOnFail = "opened YouTube search" to "tap the video to play it"
                    )
                }
            } catch (_: CancellationException) {
                emitCancelled(plan.actionId, "Stopped by user.")
            } catch (e: Exception) {
                val elapsed = formatElapsed(System.currentTimeMillis() - startMillis)
                emitToolResult(
                    actionId = plan.actionId,
                    result = ToolExecutionResult.Failed(e.localizedMessage ?: "Unexpected error playing YouTube video"),
                    completedSteps = 0,
                    elapsed = elapsed
                )
            } finally {
                dismissOverlayAndNotification()
            }
        }
    }

    /**
     * Background-safe multi-step AI Accessibility loop running in serviceScope so it never
     * pauses when another app comes to the foreground.
     */
    private val firebaseAiService by lazy { FirebaseAiService() }

    fun startAiUiAutomation(
        plan: ActionPlan,
        goal: String,
        provider: AiProvider?,
        blockSensitive: Boolean,
        maxSteps: Int = 15,
        conversationHistory: List<ChatMessage> = emptyList(),
        initialDelayMs: Long = 0L
    ) {
        activeTaskJob?.cancel()
        clearStopRequest()

        val loopStartMillis = System.currentTimeMillis()
        activeTaskJob = serviceScope.launch {
            try {
                if (initialDelayMs > 0L) {
                    delay(initialDelayMs)
                }
                runAiAutomationLoop(
                    plan = plan,
                    goal = goal,
                    provider = provider,
                    blockSensitive = blockSensitive,
                    maxSteps = maxSteps,
                    loopStartMillis = loopStartMillis,
                    initialHistory = emptyList(),
                    conversationHistory = conversationHistory
                )
            } catch (_: CancellationException) {
                emitCancelled(plan.actionId, "Stopped by user.")
            } catch (e: Exception) {
                val elapsed = formatElapsed(System.currentTimeMillis() - loopStartMillis)
                emitToolResult(
                    actionId = plan.actionId,
                    result = ToolExecutionResult.Failed(e.localizedMessage ?: "Unexpected UI automation error"),
                    completedSteps = 0,
                    elapsed = elapsed
                )
            } finally {
                dismissOverlayAndNotification()
            }
        }
    }

    private suspend fun runAiAutomationLoop(
        plan: ActionPlan,
        goal: String,
        provider: AiProvider?,
        blockSensitive: Boolean,
        maxSteps: Int,
        loopStartMillis: Long,
        initialHistory: List<String>,
        conversationHistory: List<ChatMessage> = emptyList(),
        stopBeforeConfirmButton: Boolean = false,
        rideBookingAppName: String? = null,
        fallbackPartialOnFail: Pair<String, String>? = null
    ) {
        val stepLog = initialHistory.toMutableList()
        val totalPlanSteps = plan.steps.size.coerceAtLeast(1)
        var lastActionFeedback: String? = null
        var finishedSummary: String? = null
        var rideReadyStoppedSafely = false
        var failedMessage: String? = null
        var blockedReason: String? = null
        var consecutiveStagnantSteps = 0
        var lastAutoScrollDir = "down"
        var anyRealActionPerformed = false
        var anyScreenChanged = false
        var lastActionChangedScreen = false

        val firstBanner = "Step 1/$maxSteps · Reading screen…"
        publishRunningState(plan.actionId, firstBanner, loopStartMillis, completedSteps = 0, activeStep = 0)
        updateOverlayAndNotification(firstBanner)

        for (step in 1..maxSteps) {
            if (!serviceScope.isActive || isStopRequested()) break

            val readBanner = "Step $step/$maxSteps · Reading screen…"
            publishRunningState(
                plan.actionId,
                readBanner,
                loopStartMillis,
                completedSteps = ((step - 1) * totalPlanSteps / maxSteps).coerceIn(0, totalPlanSteps - 1),
                activeStep = ((step - 1) * totalPlanSteps / maxSteps).coerceIn(0, totalPlanSteps - 1)
            )
            updateOverlayAndNotification(readBanner)

            // Loading patience: if fewer than 3 meaningful nodes, wait 1.2s and re-read up to 3x
            var snapshot = withContext(Dispatchers.Main) { captureCurrentScreenSnapshot() }
            var loadingRetries = 0
            while (snapshot.nodes.size < 3 && loadingRetries < 3 && !isStopRequested()) {
                loadingRetries++
                val waitBanner = "Step $step/$maxSteps · Waiting for screen…"
                publishRunningState(plan.actionId, waitBanner, loopStartMillis, completedSteps = 0, activeStep = 0)
                updateOverlayAndNotification(waitBanner)
                for (tick in 0 until 12) {
                    if (isStopRequested()) break
                    delay(100L)
                }
                if (isStopRequested()) break
                snapshot = withContext(Dispatchers.Main) { captureCurrentScreenSnapshot() }
            }

            if (isStopRequested()) break

            if (blockSensitive) {
                val screenViolation = SafetyGuardrail.detectSensitiveScreen(
                    packageName = snapshot.packageName,
                    ownPackageName = packageName,
                    nodes = snapshot.nodes
                )
                if (screenViolation != null) {
                    blockedReason = screenViolation
                    break
                }
            }

            val thinkBanner = "Step $step/$maxSteps · Deciding…"
            publishRunningState(
                plan.actionId,
                thinkBanner,
                loopStartMillis,
                completedSteps = ((step - 1) * totalPlanSteps / maxSteps).coerceIn(0, totalPlanSteps - 1),
                activeStep = ((step - 1) * totalPlanSteps / maxSteps).coerceIn(0, totalPlanSteps - 1)
            )
            updateOverlayAndNotification(thinkBanner)

            val nextResult = try {
                if (provider != null) {
                    networkClient.requestNextOnDeviceAction(
                        provider = provider,
                        goal = goal,
                        plannedSteps = plan.steps,
                        screenSnapshot = snapshot,
                        stepHistory = stepLog,
                        stepNumber = step,
                        maxSteps = maxSteps,
                        lastActionFeedback = lastActionFeedback,
                        conversationHistory = conversationHistory
                    )
                } else {
                    firebaseAiService.requestNextOnDeviceActionWithGemini(
                        goal = goal,
                        screenSnapshot = snapshot,
                        stepHistory = stepLog,
                        stepNumber = step,
                        maxSteps = maxSteps,
                        lastActionFeedback = lastActionFeedback,
                        conversationHistory = conversationHistory
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NextActionApiResult.Error(e.localizedMessage ?: "AI step request failed")
            }

            if (isStopRequested()) break

            when (nextResult) {
                is NextActionApiResult.Error -> {
                    failedMessage = nextResult.message
                    break
                }

                is NextActionApiResult.Success -> {
                    val nextAction = nextResult.action

                    if (blockSensitive) {
                        val actionViolation = SafetyGuardrail.detectSensitiveViolation(
                            nextAction.appName,
                            nextAction.text,
                            nextAction.reason
                        )
                        if (actionViolation != null) {
                            blockedReason = actionViolation
                            break
                        }
                    }

                    // Safe Final Check for ride booking: NEVER tap Confirm / Book / Request / Pay!
                    if (stopBeforeConfirmButton && nextAction.action == "tap") {
                        val candidateNode = nextAction.index?.let { idx ->
                            snapshot.nodes.firstOrNull { it.index == idx }
                        }
                        val candidateLabels = listOfNotNull(
                            candidateNode?.text,
                            candidateNode?.contentDescription,
                            candidateNode?.aggregatedLabel,
                            nextAction.reason
                        )
                        if (candidateLabels.any { HybridToolRouting.isRideConfirmButton(it) }) {
                            rideReadyStoppedSafely = true
                            finishedSummary = HybridToolRouting.buildRideReadyMessage(rideBookingAppName ?: "Uber")
                            break
                        }
                    }

                    if (nextAction.action == "done") {
                        when (
                            val validation = UiAutomationVerification.validateDoneAction(
                                stepNumber = step,
                                maxSteps = maxSteps,
                                anyRealActionPerformed = anyRealActionPerformed,
                                anyScreenChanged = anyScreenChanged,
                                lastActionChangedScreen = lastActionChangedScreen,
                                evidence = nextAction.evidence,
                                visibleScreenText = snapshot.compactSummary
                            )
                        ) {
                            is DoneValidationResult.Accepted -> {
                                if (rideBookingAppName != null) {
                                    rideReadyStoppedSafely = true
                                    finishedSummary = HybridToolRouting.buildRideReadyMessage(rideBookingAppName)
                                } else {
                                    finishedSummary = nextAction.summary
                                        ?.takeIf { it.isNotBlank() }
                                        ?: "Completed \"${plan.title}\" (${validation.evidence.take(40)})"
                                }
                                break
                            }

                            is DoneValidationResult.Rejected -> {
                                if (!anyScreenChanged && (step >= 2 || !validation.shouldRetry)) {
                                    failedMessage = "No action took effect on the screen"
                                    break
                                }
                                if (validation.shouldRetry && step < maxSteps) {
                                    lastActionFeedback = "REJECTED done: ${validation.reason} Take a real visible action first and include exact visible text in \"evidence\"."
                                    stepLog.add("Step $step (done rejected): ${validation.reason}")
                                    continue
                                } else {
                                    failedMessage = validation.reason
                                    break
                                }
                            }
                        }
                    }

                    val execBanner = "Step $step/$maxSteps · ${nextAction.reason.take(28)}…"
                    val mappedDone = ((step * totalPlanSteps) / maxSteps).coerceIn(0, totalPlanSteps - 1)
                    publishRunningState(plan.actionId, execBanner, loopStartMillis, mappedDone, mappedDone)
                    updateOverlayAndNotification(execBanner)

                    val beforeHash = snapshot.treeHash
                    val beforePkg = snapshot.packageName
                    val execResult = performOnDeviceAction(nextAction)
                    if (execResult.success) {
                        anyRealActionPerformed = true
                    }
                    val outcomeTag = if (execResult.success) "OK" else "FAILED"
                    stepLog.add("Step $step (${nextAction.action}) [$outcomeTag]: ${execResult.summary}")

                    // Fast 450ms interruptible pause for UI to settle + instant Stop response
                    val settleTicks = if (nextAction.action == "open_app") 10 else 5
                    for (tick in 0 until settleTicks) {
                        if (isStopRequested()) break
                        delay(100L)
                    }
                    if (isStopRequested()) break

                    // Anti-stuck UI-tree hash & package comparison + automatic scroll-retry recovery
                    val afterSnap = withContext(Dispatchers.Main) { captureCurrentScreenSnapshot() }
                    val changed = execResult.success && (
                        afterSnap.treeHash != beforeHash ||
                            afterSnap.packageName != beforePkg
                        )
                    lastActionChangedScreen = changed
                    if (changed) {
                        anyScreenChanged = true
                    }

                    if (!changed) {
                        consecutiveStagnantSteps++
                        if (consecutiveStagnantSteps >= 2 && !isStopRequested()) {
                            val recoveryDir = if (nextAction.action == "scroll" && nextAction.direction == "down") {
                                "up"
                            } else {
                                lastAutoScrollDir.also { lastAutoScrollDir = if (it == "down") "up" else "down" }
                            }
                            val retryBanner = "Step $step/$maxSteps · Retrying (scrolling $recoveryDir)…"
                            publishRunningState(plan.actionId, retryBanner, loopStartMillis, mappedDone, mappedDone)
                            updateOverlayAndNotification(retryBanner)
                            performScroll(recoveryDir)
                            for (tick in 0 until 4) {
                                if (isStopRequested()) break
                                delay(100L)
                            }
                            stepLog.add("Step $step (auto-retry scroll $recoveryDir): nudged screen after $consecutiveStagnantSteps stagnant steps")
                            lastActionFeedback = "Screen was unchanged for $consecutiveStagnantSteps steps, so I auto-scrolled $recoveryDir to reveal fresh controls. Pick a different visible element now."
                            consecutiveStagnantSteps = 0
                        } else {
                            val retryBanner = "Step $step/$maxSteps · Retrying alternative…"
                            publishRunningState(plan.actionId, retryBanner, loopStartMillis, mappedDone, mappedDone)
                            updateOverlayAndNotification(retryBanner)
                            lastActionFeedback = if (!execResult.success) {
                                "Last action failed (${execResult.summary}) — try a different element or scroll."
                            } else {
                                "Last action had no visual effect — pick a different element or scroll."
                            }
                        }
                    } else {
                        consecutiveStagnantSteps = 0
                        lastActionFeedback = null
                    }
                }
            }
        }

        val totalElapsed = formatElapsed(System.currentTimeMillis() - loopStartMillis)
        when {
            blockedReason != null -> emitBlocked(plan.actionId, blockedReason)
            isStopRequested() -> emitCancelled(
                plan.actionId,
                "Agent stopped by user after ${stepLog.size} step(s)."
            )
            rideReadyStoppedSafely && finishedSummary != null -> {
                emitToolResult(
                    actionId = plan.actionId,
                    result = ToolExecutionResult.VerifiedSuccess(
                        summary = "Set destination in ${rideBookingAppName ?: "Uber"}",
                        customUserMessage = finishedSummary
                    ),
                    completedSteps = totalPlanSteps,
                    elapsed = totalElapsed,
                    durationTagOverride = "Ready to confirm · $totalElapsed"
                )
            }
            finishedSummary != null && anyScreenChanged -> {
                emitToolResult(
                    actionId = plan.actionId,
                    result = ToolExecutionResult.VerifiedSuccess(finishedSummary),
                    completedSteps = totalPlanSteps,
                    elapsed = totalElapsed,
                    durationTagOverride = "Completed in $totalElapsed"
                )
            }
            fallbackPartialOnFail != null -> {
                emitToolResult(
                    actionId = plan.actionId,
                    result = ToolExecutionResult.PartialSuccess(
                        whatWorked = fallbackPartialOnFail.first,
                        whatFailed = fallbackPartialOnFail.second
                    ),
                    completedSteps = 1,
                    elapsed = totalElapsed,
                    durationTagOverride = "Partial · $totalElapsed"
                )
            }
            failedMessage != null -> {
                emitToolResult(
                    actionId = plan.actionId,
                    result = ToolExecutionResult.Failed(failedMessage),
                    completedSteps = 0,
                    elapsed = totalElapsed
                )
            }
            !anyScreenChanged -> {
                emitToolResult(
                    actionId = plan.actionId,
                    result = ToolExecutionResult.Failed("No action took effect on the screen"),
                    completedSteps = 0,
                    elapsed = totalElapsed
                )
            }
            else -> {
                emitToolResult(
                    actionId = plan.actionId,
                    result = ToolExecutionResult.Failed(
                        "Reached the $maxSteps-step limit before verifying completion of \"${plan.title}\""
                    ),
                    completedSteps = (totalPlanSteps - 1).coerceAtLeast(0),
                    elapsed = totalElapsed,
                    durationTagOverride = "Stopped at $maxSteps steps · $totalElapsed"
                )
            }
        }
    }

    private fun findFirstPlayableYouTubeVideo(snapshot: ScreenSnapshot, screenH: Int): ScreenNode? {
        val ignoredExactLabels = setOf(
            "all", "shorts", "videos", "unwatched", "watched", "recently uploaded",
            "live", "new to you", "posts", "for you", "channels", "playlists",
            "home", "subscriptions", "library", "you", "create", "notifications",
            "search", "search youtube", "voice search", "cast", "more options",
            "action menu", "navigate up", "back", "clear", "filter", "filters",
            "close", "dismiss", "skip", "install", "open", "learn more", "visit advertiser"
        )

        val minY = (screenH * 0.16f).toInt()
        val maxY = (screenH * 0.88f).toInt()

        fun isAdOrSponsored(labelLower: String): Boolean {
            return labelLower.contains("sponsored") ||
                labelLower.contains("promoted") ||
                labelLower.contains("visit advertiser") ||
                labelLower.startsWith("ad ") ||
                labelLower.contains(" · ad") ||
                labelLower.contains(" ad ·") ||
                labelLower.contains("advertisement") ||
                labelLower.contains("shop now")
        }

        fun isLikelyVideoCard(node: ScreenNode): Boolean {
            if (!node.isClickable) return false
            if (node.centerY < minY || node.centerY > maxY) return false
            val label = node.aggregatedLabel.ifBlank { node.text.ifBlank { node.contentDescription } }.trim()
            if (label.length < 10) return false
            val lower = label.lowercase()
            if (lower in ignoredExactLabels) return false
            if (isAdOrSponsored(lower)) return false
            if (lower.startsWith("search ") || lower.startsWith("clear ")) return false
            if (lower.contains("subscribe") && label.length < 22) return false
            if (lower.contains("action menu") || lower.contains("more options")) return false
            // Video cards generally have substantial height or rich title/duration metadata
            val hasVideoMetadata = lower.contains("views") ||
                lower.contains("minute") ||
                lower.contains("second") ||
                lower.contains("hour") ||
                lower.contains("ago") ||
                lower.contains("play video") ||
                node.heightPx >= (screenH * 0.08f).toInt()
            return hasVideoMetadata || label.length >= 22
        }

        return snapshot.nodes.firstOrNull { isLikelyVideoCard(it) }
            ?: snapshot.nodes.firstOrNull { node ->
                node.isClickable &&
                    node.centerY in minY..maxY &&
                    node.heightPx >= (screenH * 0.09f).toInt() &&
                    !isAdOrSponsored(node.aggregatedLabel.lowercase()) &&
                    node.aggregatedLabel.lowercase() !in ignoredExactLabels
            }
    }

    private fun isYouTubePlayerVisible(after: ScreenSnapshot, before: ScreenSnapshot?): Boolean {
        val lowerSummary = after.compactSummary.lowercase()
        val playerCues = listOf(
            "pause video", "play video", "enter fullscreen", "fullscreen",
            "minimize", "chapters", "autoplay", "captions", "next video",
            "previous video", "subscribe", "share", "comments", "video player",
            "seek slider", "player controls"
        )
        val cueHits = playerCues.count { lowerSummary.contains(it) }
        if (cueHits >= 2) return true
        if (cueHits >= 1 && before != null && after.treeHash != before.treeHash) return true
        return false
    }

    fun captureCurrentScreenSnapshot(): ScreenSnapshot {
        val root = rootInActiveWindow
            ?: return ScreenSnapshot(
                packageName = "unknown",
                nodes = emptyList(),
                compactSummary = "Screen unavailable (no active window).",
                treeHash = 0
            )

        val pkg = root.packageName?.toString().orEmpty().ifBlank { "unknown" }
        val collected = mutableListOf<ScreenNode>()
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels.coerceAtLeast(1)
        val screenH = metrics.heightPixels.coerceAtLeast(1)
        val maxNodes = 45

        fun collectChildLabels(parent: AccessibilityNodeInfo, maxDepth: Int = 3): String {
            val parts = LinkedHashSet<String>()
            fun walk(n: AccessibilityNodeInfo?, depth: Int) {
                if (n == null || depth > maxDepth || parts.size >= 5) return
                val t = n.text?.toString()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                val d = n.contentDescription?.toString()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                if (t.isNotBlank()) parts.add(t.take(60))
                if (d.isNotBlank() && d != t) parts.add(d.take(60))
                for (i in 0 until n.childCount) {
                    if (parts.size >= 5) break
                    walk(n.getChild(i), depth + 1)
                }
            }
            for (i in 0 until parent.childCount) {
                if (parts.size >= 5) break
                walk(parent.getChild(i), 1)
            }
            return parts.joinToString(" · ").take(110)
        }

        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 16 || collected.size >= maxNodes) return
            if (!node.isVisibleToUser) return

            val text = node.text?.toString()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            val clickable = node.isClickable
            val editable = node.isEditable
            val rect = Rect()
            node.getBoundsInScreen(rect)

            val hasValidBounds = rect.width() > 6 && rect.height() > 6 &&
                rect.right > 0 && rect.bottom > 0 &&
                rect.left < screenW && rect.top < screenH

            // Aggregate child text inside clickable ViewGroup containers (essential for YouTube video cards)
            val childAggregate = if (clickable && text.isBlank() && desc.isBlank() && node.childCount > 0) {
                collectChildLabels(node)
            } else {
                ""
            }

            val combinedLabel = when {
                text.isNotBlank() && desc.isNotBlank() && !desc.equals(text, ignoreCase = true) ->
                    "$text ($desc)".take(90)
                text.isNotBlank() -> text.take(90)
                desc.isNotBlank() -> desc.take(90)
                childAggregate.isNotBlank() -> childAggregate
                else -> ""
            }

            val viewId = runCatching { node.viewIdResourceName?.toString().orEmpty() }.getOrDefault("")
            val isSendButtonHint = viewId.contains("send", ignoreCase = true)

            val useful = hasValidBounds && (
                editable ||
                    isSendButtonHint ||
                    (clickable && (combinedLabel.isNotBlank() || node.childCount == 0)) ||
                    combinedLabel.isNotBlank()
                )

            if (useful) {
                val cx = rect.centerX().coerceIn(1, screenW - 1)
                val cy = rect.centerY().coerceIn(1, screenH - 1)
                val shortClass = node.className?.toString()?.substringAfterLast('.').orEmpty()

                val isNearDuplicate = collected.any { existing ->
                    abs(existing.centerX - cx) <= 12 &&
                        abs(existing.centerY - cy) <= 12 &&
                        existing.aggregatedLabel.equals(combinedLabel, ignoreCase = true) &&
                        existing.isClickable == clickable &&
                        existing.viewIdResourceName == viewId
                }

                if (!isNearDuplicate) {
                    collected.add(
                        ScreenNode(
                            index = collected.size,
                            text = text.take(64),
                            contentDescription = desc.take(64),
                            aggregatedLabel = combinedLabel.ifBlank { shortClass.ifBlank { "Element" } },
                            className = shortClass,
                            isClickable = clickable,
                            isEditable = editable,
                            centerX = cx,
                            centerY = cy,
                            topY = rect.top,
                            heightPx = rect.height(),
                            viewIdResourceName = viewId
                        )
                    )
                }
            }

            for (i in 0 until node.childCount) {
                if (collected.size >= maxNodes) break
                traverse(node.getChild(i), depth + 1)
            }
        }

        traverse(root, 0)
        lastCapturedNodes = collected

        val summary = buildString {
            appendLine("App: $pkg")
            if (collected.isEmpty()) {
                appendLine("(no visible nodes)")
            } else {
                collected.forEach { n ->
                    val textField = n.text.ifBlank { n.aggregatedLabel }.replace("|", " ")
                    val descField = n.contentDescription.replace("|", " ")
                    val flags = buildList {
                        if (n.isClickable) add("clickable")
                        if (n.isEditable) add("editable")
                    }.joinToString(",")
                    val coord = "@(${n.centerX},${n.centerY})"
                    appendLine("[${n.index}] $textField | $descField | $flags $coord")
                }
            }
        }.trim()

        val hash = (pkg + "|" + collected.joinToString("|") {
            "${it.aggregatedLabel}:${it.centerX / 16}:${it.centerY / 16}:${it.isClickable}"
        }).hashCode()

        return ScreenSnapshot(
            packageName = pkg,
            nodes = collected,
            compactSummary = summary,
            treeHash = hash
        )
    }

    suspend fun performOnDeviceAction(action: OnDeviceAgentAction): ActionExecutionResult {
        return when (action.action.lowercase().trim()) {
            "open_app" -> withContext(Dispatchers.Main) {
                NativeToolExecutor.openApp(this@AgentAccessibilityService, action.appName.orEmpty())
            }
            "tap" -> {
                val targetNode = action.index?.let { idx -> lastCapturedNodes.firstOrNull { it.index == idx } }
                performSmartTap(targetNode, action.x, action.y)
            }
            "type_text" -> performSmartTypeText(action.index, action.text.orEmpty(), action.submit)
            "scroll" -> performScroll(action.direction ?: "down")
            "back" -> {
                val ok = performGlobalAction(GLOBAL_ACTION_BACK)
                ActionExecutionResult(ok, if (ok) "Pressed Back" else "Could not press Back")
            }
            "home" -> {
                val ok = performGlobalAction(GLOBAL_ACTION_HOME)
                ActionExecutionResult(ok, if (ok) "Returned to Home screen" else "Could not go Home")
            }
            "done" -> ActionExecutionResult(
                true,
                action.summary?.takeIf { it.isNotBlank() } ?: "Task completed."
            )
            else -> ActionExecutionResult(false, "Unknown action: ${action.action}")
        }
    }

    /**
     * 3-Tier Smart Tap Fallback:
     * 1) Semantic ACTION_CLICK on node at (x,y)
     * 2) Walk up parent chain to nearest isClickable ancestor and call ACTION_CLICK
     * 3) Coordinate dispatchGesture tap at center (x,y)
     */
    private suspend fun performSmartTap(
        targetNode: ScreenNode?,
        fallbackX: Int?,
        fallbackY: Int?
    ): ActionExecutionResult {
        val tapX = targetNode?.centerX ?: fallbackX
        val tapY = targetNode?.centerY ?: fallbackY
        if (tapX == null || tapY == null) {
            return ActionExecutionResult(false, "Tap action missing valid index or coordinates.")
        }

        val label = targetNode?.aggregatedLabel?.take(42) ?: "($tapX, $tapY)"

        val clickedSemantically = withContext(Dispatchers.Main) {
            trySemanticClickNear(tapX, tapY)
        }
        if (clickedSemantically) {
            return ActionExecutionResult(true, "Clicked $label")
        }

        val dispatched = dispatchTapGesture(tapX.toFloat(), tapY.toFloat())
        return ActionExecutionResult(
            dispatched,
            if (dispatched) "Tapped $label" else "Failed to tap $label"
        )
    }

    private fun trySemanticClickNear(targetX: Int, targetY: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val rect = Rect()
        var closestNode: AccessibilityNodeInfo? = null
        var bestDist = Int.MAX_VALUE

        fun search(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return
            node.getBoundsInScreen(rect)
            if (rect.contains(targetX, targetY)) {
                val dist = abs(rect.centerX() - targetX) + abs(rect.centerY() - targetY)
                if (dist < bestDist) {
                    bestDist = dist
                    closestNode = node
                }
            }
            for (i in 0 until node.childCount) {
                search(node.getChild(i))
            }
        }

        search(root)
        var current = closestNode
        var hops = 0
        while (current != null && hops < 6) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
            hops++
        }
        return false
    }

    /**
     * Smart Typing:
     * Focus/tap editable field -> ACTION_SET_TEXT -> Clipboard ACTION_PASTE fallback -> optional IME_ENTER submit.
     */
    private suspend fun performSmartTypeText(
        index: Int?,
        textToType: String,
        submit: Boolean
    ): ActionExecutionResult {
        val targetInfo = index?.let { idx -> lastCapturedNodes.firstOrNull { it.index == idx } }
        if (targetInfo != null) {
            performSmartTap(targetInfo, targetInfo.centerX, targetInfo.centerY)
            delay(180L)
        }

        return withContext(Dispatchers.Main) {
            val root = rootInActiveWindow
                ?: return@withContext ActionExecutionResult(false, "No active window available to type text.")

            val editableNode = (targetInfo?.let { findEditableNodeNear(root, it) }
                ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findFirstEditableNode(root))
                ?: return@withContext ActionExecutionResult(false, "No editable text field found on screen.")

            editableNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            editableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
            }
            var ok = editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            // Fallback to clipboard paste if ACTION_SET_TEXT is rejected by custom WebView / search bar
            if (!ok) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("agent_input", textToType))
                    ok = editableNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }
            }

            if (ok && submit && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                editableNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }

            ActionExecutionResult(
                ok,
                if (ok) {
                    "Typed \"${textToType.take(40)}\"" + if (submit) " & submitted" else ""
                } else {
                    "Could not enter text into field."
                }
            )
        }
    }

    private suspend fun performScroll(direction: String): ActionExecutionResult {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat().coerceAtLeast(100f)
        val h = metrics.heightPixels.toFloat().coerceAtLeast(100f)

        val (startX, startY, endX, endY) = when (direction.lowercase().trim()) {
            "up" -> listOf(w * 0.5f, h * 0.30f, w * 0.5f, h * 0.72f)
            "left" -> listOf(w * 0.80f, h * 0.5f, w * 0.20f, h * 0.5f)
            "right" -> listOf(w * 0.20f, h * 0.5f, w * 0.80f, h * 0.5f)
            else -> listOf(w * 0.5f, h * 0.72f, w * 0.5f, h * 0.28f)
        }

        val ok = dispatchSwipeGesture(startX, startY, endX, endY, durationMs = 340L)
        return ActionExecutionResult(
            ok,
            if (ok) "Scrolled $direction" else "Could not scroll $direction"
        )
    }

    private suspend fun dispatchTapGesture(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val path = Path().apply { moveTo(x, y) }
                val stroke = GestureDescription.StrokeDescription(path, 0L, 65L)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                val accepted = dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null
                )
                if (!accepted && cont.isActive) {
                    cont.resume(false)
                }
            }
        }
    }

    private suspend fun dispatchSwipeGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val path = Path().apply {
                    moveTo(startX, startY)
                    lineTo(endX, endY)
                }
                val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                val accepted = dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null
                )
                if (!accepted && cont.isActive) {
                    cont.resume(false)
                }
            }
        }
    }

    private fun findEditableNodeNear(root: AccessibilityNodeInfo, target: ScreenNode): AccessibilityNodeInfo? {
        val rect = Rect()
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            node.getBoundsInScreen(rect)
            if (node.isEditable &&
                abs(rect.centerX() - target.centerX) <= 60 &&
                abs(rect.centerY() - target.centerY) <= 60
            ) {
                return node
            }
            for (i in 0 until node.childCount) {
                val found = search(node.getChild(i))
                if (found != null) return found
            }
            return null
        }
        return search(root)
    }

    private fun findFirstEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isEditable && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) {
                val found = search(node.getChild(i))
                if (found != null) return found
            }
            return null
        }
        return search(root)
    }

    private fun publishRunningState(
        actionId: String,
        phaseLabel: String,
        phaseStartMillis: Long,
        completedSteps: Int,
        activeStep: Int
    ) {
        _executionState.update {
            it.copy(
                isRunning = true,
                actionId = actionId,
                phaseLabel = phaseLabel,
                phaseStartMillis = phaseStartMillis,
                completedSteps = completedSteps,
                activeStep = activeStep
            )
        }
    }

    private fun emitBlocked(actionId: String, violation: String) {
        emitCompletion(
            actionId = actionId,
            status = ApprovalStatus.BLOCKED,
            completedSteps = 0,
            message = "Stopped immediately by Safety Guardrail ($violation) while inspecting the live screen.",
            notes = "Stopped live: $violation",
            durationLabel = "Blocked",
            blockedReason = violation
        )
    }

    private fun emitCancelled(actionId: String, reason: String) {
        emitCompletion(
            actionId = actionId,
            status = ApprovalStatus.CANCELLED,
            completedSteps = 0,
            message = reason,
            notes = reason,
            durationLabel = "Stopped"
        )
    }

    private fun emitToolResult(
        actionId: String,
        result: ToolExecutionResult,
        completedSteps: Int,
        elapsed: String,
        durationTagOverride: String? = null
    ) {
        val message = result.toUserMessage(elapsed)
        val status = if (result.isVerifiedSuccess) ApprovalStatus.COMPLETED else ApprovalStatus.FAILED
        val notes = when (result) {
            is ToolExecutionResult.VerifiedSuccess -> result.customUserMessage ?: result.summary
            is ToolExecutionResult.PartialSuccess -> "Partial: ${result.whatWorked} / couldn't ${result.whatFailed}"
            is ToolExecutionResult.Failed -> result.reason
        }
        val duration = durationTagOverride ?: when (result) {
            is ToolExecutionResult.VerifiedSuccess -> "Verified · $elapsed"
            is ToolExecutionResult.PartialSuccess -> "Partial · $elapsed"
            is ToolExecutionResult.Failed -> "Failed · $elapsed"
        }
        emitCompletion(
            actionId = actionId,
            status = status,
            completedSteps = completedSteps,
            message = message,
            notes = notes,
            durationLabel = duration,
            isError = !result.isVerifiedSuccess
        )
    }

    private fun emitCompletion(
        actionId: String,
        status: ApprovalStatus,
        completedSteps: Int,
        message: String,
        notes: String,
        durationLabel: String?,
        blockedReason: String? = null,
        isError: Boolean = false
    ) {
        if (lastCompletedActionId == actionId) return
        lastCompletedActionId = actionId

        _executionState.value = ServiceExecutionState(
            isRunning = false,
            actionId = null,
            phaseLabel = "",
            phaseStartMillis = 0L,
            completedSteps = 0,
            activeStep = -1,
            completionEvent = ServiceCompletionEvent(
                actionId = actionId,
                status = status,
                completedSteps = completedSteps,
                message = message,
                notes = notes,
                durationLabel = durationLabel,
                blockedReason = blockedReason,
                isError = isError
            )
        )

        // Persist outcome directly to DataStore AND singleton ConversationRepository so conversation & action logs
        // stay 100% synced even when triggered from Quick Settings Tile or Notification while MainActivity is closed!
        val chatMsg = ChatMessage(
            sender = MessageSender.AGENT,
            text = message,
            isError = isError,
            isBlockedBySafety = status == ApprovalStatus.BLOCKED,
            blockedReason = blockedReason,
            excludeFromContext = status == ApprovalStatus.BLOCKED
        )
        ConversationRepository.recordActionLog(
            summary = notes.ifBlank { message },
            status = status.label
        )
        ConversationRepository.appendMessagesInMemory(listOf(chatMsg))
        serviceScope.launch {
            localStorage.updateActionStatusInStorage(
                actionId = actionId,
                status = status,
                completedSteps = completedSteps,
                activeStep = -1,
                blockedReason = blockedReason,
                notes = notes,
                durationLabel = durationLabel,
                appendChatMessage = chatMsg
            )
        }

        // Speak outcome aloud & display brief non-blocking Toast when outside the main app
        if (status == ApprovalStatus.COMPLETED || status == ApprovalStatus.FAILED || status == ApprovalStatus.BLOCKED) {
            AgentSpeechFeedback.announceOutcome(
                context = applicationContext,
                message = message,
                isSuccess = status == ApprovalStatus.COMPLETED,
                voiceSettings = latestVoiceSettings,
                showToastWhenOutsideApp = true
            )
        }
        dismissOverlayAndNotification()
    }

    private fun formatElapsed(elapsedMillis: Long): String {
        val seconds = (elapsedMillis.coerceAtLeast(100L)) / 1000.0
        return String.format(Locale.US, "%.1fs", seconds)
    }

    companion object {
        private var instanceRef: WeakReference<AgentAccessibilityService> = WeakReference(null)

        @Volatile
        private var lastExternalForegroundPackage: String = ""

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        private val _executionState = MutableStateFlow(ServiceExecutionState())
        val executionState: StateFlow<ServiceExecutionState> = _executionState.asStateFlow()

        private val _stopSignal = MutableStateFlow(0L)
        val stopSignal: StateFlow<Long> = _stopSignal.asStateFlow()

        private val stopFlag = AtomicBoolean(false)

        fun getInstance(): AgentAccessibilityService? = instanceRef.get()

        fun getCurrentForegroundApp(ownPackageName: String? = null): String? {
            val svc = instanceRef.get()
            if (svc != null) {
                val visible = svc.currentVisibleExternalPackage()
                if (!visible.isNullOrBlank() && visible != ownPackageName) {
                    return visible
                }
            }
            return lastExternalForegroundPackage.takeIf { it.isNotBlank() && it != ownPackageName }
        }

        fun clearStopRequest() {
            stopFlag.set(false)
        }

        fun requestStop() {
            stopFlag.set(true)
            _stopSignal.value = System.currentTimeMillis()
        }

        fun stopActiveExecution(context: Context? = null) {
            requestStop()
            val activeActionId = _executionState.value.takeIf { it.isRunning }?.actionId
            val svc = instanceRef.get()
            svc?.stopExecutionInternal()
            if (svc == null) {
                context?.let { AgentNotificationHelper.cancelRunningNotification(it) }
            }
            if (activeActionId != null) {
                if (svc != null) {
                    svc.emitCancelled(activeActionId, "Agent stopped by user.")
                } else {
                    _executionState.update { current ->
                        current.copy(
                            isRunning = false,
                            phaseLabel = "",
                            phaseStartMillis = 0L,
                            completionEvent = ServiceCompletionEvent(
                                actionId = activeActionId,
                                status = ApprovalStatus.CANCELLED,
                                completedSteps = 0,
                                message = "Agent stopped by user.",
                                notes = "Stopped by user.",
                                durationLabel = "Stopped"
                            )
                        )
                    }
                }
            } else {
                _executionState.update { current ->
                    current.copy(isRunning = false, phaseLabel = "", phaseStartMillis = 0L)
                }
            }
        }

        fun consumeCompletionEvent(eventId: String) {
            _executionState.update { current ->
                if (current.completionEvent?.eventId == eventId) {
                    current.copy(completionEvent = null)
                } else {
                    current
                }
            }
        }

        fun isStopRequested(): Boolean = stopFlag.get()

        fun isAccessibilityEnabled(context: Context): Boolean {
            if (instanceRef.get() != null) return true
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val expectedComponent = ComponentName(context, AgentAccessibilityService::class.java)
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledServices)
            while (splitter.hasNext()) {
                val componentNameString = splitter.next()
                val enabledComponent = ComponentName.unflattenFromString(componentNameString)
                if (enabledComponent != null && enabledComponent == expectedComponent) {
                    return true
                }
            }
            return false
        }
    }
}
