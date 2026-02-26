package com.openwearables.health.sdk

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.*

class OpenWearablesHealthSdkPlugin : FlutterPlugin, MethodCallHandler, ActivityAware, EventChannel.StreamHandler, DefaultLifecycleObserver {

    companion object {
        private const val TAG = "OpenWearablesHealthSdk"
        private const val CHANNEL_NAME = "open_wearables_health_sdk"
        private const val LOG_CHANNEL_NAME = "open_wearables_health_sdk/logs"
        private const val AUTH_ERROR_CHANNEL_NAME = "open_wearables_health_sdk/auth_errors"
    }

    // Flutter channels
    private lateinit var methodChannel: MethodChannel
    private lateinit var logChannel: EventChannel
    private lateinit var authErrorChannel: EventChannel
    private var logEventSink: EventChannel.EventSink? = null
    private var authErrorEventSink: EventChannel.EventSink? = null

    // Context references
    private var context: Context? = null
    private var activity: Activity? = null
    private var activityBinding: ActivityPluginBinding? = null

    // Components
    private val secureStorage: SecureStorage by lazy { SecureStorage(context!!) }

    // Providers - lazy but always available for availability checks
    private val samsungHealthManager: SamsungHealthManager by lazy {
        SamsungHealthManager(context!!, activity, ::logMessage)
    }
    private val healthConnectManager: HealthConnectManager by lazy {
        HealthConnectManager(context!!, activity, ::logMessage)
    }

    // Active provider (selected by user or auto-detected)
    private var activeProvider: HealthDataProvider? = null

    // SyncManager is recreated when provider changes
    private var syncManager: SyncManager? = null

    // Configuration
    private var host: String? = null
    private var customSyncUrl: String? = null

    // Lifecycle
    private var isInForeground = true
    private var lifecycleObserverRegistered = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // -----------------------------------------------------------------------
    // Provider management
    // -----------------------------------------------------------------------

    private fun getOrCreateProvider(): HealthDataProvider {
        activeProvider?.let { return it }
        val storedId = secureStorage.getProvider()
        val provider = resolveProvider(storedId)
        activeProvider = provider
        rebuildSyncManager()
        return provider
    }

    private fun resolveProvider(providerId: String?): HealthDataProvider = when (providerId) {
        "health_connect" -> healthConnectManager
        "samsung_health" -> samsungHealthManager
        else -> autoSelectProvider()
    }

    private fun autoSelectProvider(): HealthDataProvider {
        if (samsungHealthManager.isAvailable()) return samsungHealthManager
        if (healthConnectManager.isAvailable()) return healthConnectManager
        return samsungHealthManager
    }

    private fun setActiveProvider(providerId: String): Boolean {
        val provider = when (providerId) {
            "samsung_health" -> samsungHealthManager
            "health_connect" -> healthConnectManager
            else -> {
                logMessage("Unknown provider: $providerId")
                return false
            }
        }

        if (!provider.isAvailable()) {
            logMessage("Provider $providerId is not available on this device")
            return false
        }

        activeProvider = provider
        secureStorage.saveProvider(providerId)
        rebuildSyncManager()
        logMessage("Active provider set to: ${provider.providerName}")
        return true
    }

    private fun rebuildSyncManager() {
        val provider = activeProvider ?: return
        syncManager = SyncManager(context!!, secureStorage, provider, ::logMessage, ::emitAuthError)
    }

    private fun ensureSyncManager(): SyncManager {
        if (syncManager == null) {
            getOrCreateProvider()
        }
        return syncManager!!
    }

    // -----------------------------------------------------------------------
    // FlutterPlugin
    // -----------------------------------------------------------------------

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext

        methodChannel = MethodChannel(flutterPluginBinding.binaryMessenger, CHANNEL_NAME)
        methodChannel.setMethodCallHandler(this)

        logChannel = EventChannel(flutterPluginBinding.binaryMessenger, LOG_CHANNEL_NAME)
        logChannel.setStreamHandler(this)

        authErrorChannel = EventChannel(flutterPluginBinding.binaryMessenger, AUTH_ERROR_CHANNEL_NAME)
        authErrorChannel.setStreamHandler(AuthErrorStreamHandler())

        registerLifecycleObserver()
        Log.d(TAG, "Plugin attached to engine")
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        logChannel.setStreamHandler(null)
        authErrorChannel.setStreamHandler(null)
        unregisterLifecycleObserver()
        scope.cancel()
        context = null
        Log.d(TAG, "Plugin detached from engine")
    }

    // Auth Error Stream Handler
    private inner class AuthErrorStreamHandler : EventChannel.StreamHandler {
        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) { authErrorEventSink = events }
        override fun onCancel(arguments: Any?) { authErrorEventSink = null }
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    private fun registerLifecycleObserver() {
        if (!lifecycleObserverRegistered) {
            try {
                ProcessLifecycleOwner.get().lifecycle.addObserver(this)
                lifecycleObserverRegistered = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register lifecycle observer: ${e.message}")
            }
        }
    }

    private fun unregisterLifecycleObserver() {
        if (lifecycleObserverRegistered) {
            try {
                ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
                lifecycleObserverRegistered = false
            } catch (_: Exception) {}
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        isInForeground = true
        logMessage("App came to foreground")

        if (secureStorage.isSyncActive() && secureStorage.hasSession()) {
            logMessage("Checking for pending sync...")
            scope.launch {
                val sm = ensureSyncManager()
                if (sm.hasResumableSyncSession()) {
                    logMessage("Found interrupted sync, resuming...")
                    host?.let { sm.syncNow(it, customSyncUrl, fullExport = false) }
                }
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        isInForeground = false
        logMessage("App went to background")

        if (secureStorage.isSyncActive() && secureStorage.hasSession()) {
            host?.let { h ->
                logMessage("Scheduling background sync...")
                ensureSyncManager().scheduleExpeditedSync(h, customSyncUrl)
            }
        }
    }

    // -----------------------------------------------------------------------
    // ActivityAware
    // -----------------------------------------------------------------------

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityBinding = binding
        samsungHealthManager.setActivity(activity)
        healthConnectManager.setActivity(activity)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        healthConnectManager.unregisterPermissionLauncher()
        activity = null
        activityBinding = null
        samsungHealthManager.setActivity(null)
        healthConnectManager.setActivity(null)
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityBinding = binding
        samsungHealthManager.setActivity(activity)
        healthConnectManager.setActivity(activity)
    }

    override fun onDetachedFromActivity() {
        healthConnectManager.unregisterPermissionLauncher()
        activity = null
        activityBinding = null
        samsungHealthManager.setActivity(null)
        healthConnectManager.setActivity(null)
    }

    // EventChannel.StreamHandler (Log channel)
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) { logEventSink = events }
    override fun onCancel(arguments: Any?) { logEventSink = null }

    // -----------------------------------------------------------------------
    // MethodCallHandler
    // -----------------------------------------------------------------------

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "configure" -> handleConfigure(call, result)
            "signIn" -> handleSignIn(call, result)
            "signOut" -> handleSignOut(result)
            "restoreSession" -> handleRestoreSession(result)
            "isSessionValid" -> result.success(secureStorage.hasSession())
            "isSyncActive" -> result.success(secureStorage.isSyncActive())
            "updateTokens" -> handleUpdateTokens(call, result)
            "getStoredCredentials" -> handleGetStoredCredentials(result)
            "requestAuthorization" -> handleRequestAuthorization(call, result)
            "startBackgroundSync" -> handleStartBackgroundSync(result)
            "stopBackgroundSync" -> handleStopBackgroundSync(result)
            "syncNow" -> handleSyncNow(result)
            "resetAnchors" -> handleResetAnchors(result)
            "getSyncStatus" -> handleGetSyncStatus(result)
            "resumeSync" -> handleResumeSync(result)
            "clearSyncSession" -> handleClearSyncSession(result)
            "setProvider" -> handleSetProvider(call, result)
            "getAvailableProviders" -> handleGetAvailableProviders(result)
            else -> result.notImplemented()
        }
    }

    // -----------------------------------------------------------------------
    // Handler methods
    // -----------------------------------------------------------------------

    private fun handleConfigure(call: MethodCall, result: Result) {
        val host = call.argument<String>("host")
        if (host == null) { result.error("bad_args", "Missing host", null); return }

        secureStorage.clearIfReinstalled()
        this.host = host
        secureStorage.saveHost(host)

        this.customSyncUrl = call.argument<String>("customSyncUrl") ?: secureStorage.getCustomSyncUrl()
        customSyncUrl?.let { secureStorage.saveCustomSyncUrl(it) }

        // Restore provider and tracked types
        val provider = getOrCreateProvider()
        val storedTypes = secureStorage.getTrackedTypes()
        if (storedTypes.isNotEmpty()) {
            provider.setTrackedTypes(storedTypes)
            logMessage("Restored ${storedTypes.size} tracked types for ${provider.providerName}")
        }

        logMessage("Configured: host=$host, provider=${provider.providerName}")

        val isSyncActive = secureStorage.isSyncActive() && secureStorage.hasSession()
        if (isSyncActive && storedTypes.isNotEmpty()) {
            logMessage("Auto-restoring background sync...")
            scope.launch { autoRestoreSync() }
        }

        result.success(isSyncActive)
    }

    private suspend fun autoRestoreSync() {
        if (secureStorage.getUserId() == null || !secureStorage.hasAuth) {
            logMessage("Cannot auto-restore: no session")
            return
        }
        ensureSyncManager().startBackgroundSync(host!!, customSyncUrl)
        logMessage("Background sync auto-restored")
    }

    private fun handleSignIn(call: MethodCall, result: Result) {
        val userId = call.argument<String>("userId")
        if (userId == null) { result.error("bad_args", "Missing userId", null); return }

        val accessToken = call.argument<String>("accessToken")
        val refreshToken = call.argument<String>("refreshToken")
        val apiKey = call.argument<String>("apiKey")

        if ((accessToken == null || refreshToken == null) && apiKey == null) {
            result.error("bad_args", "Provide (accessToken + refreshToken) or (apiKey)", null)
            return
        }

        scope.launch {
            val sm = ensureSyncManager()
            sm.clearSyncSession()
            sm.resetAnchors()
        }

        secureStorage.saveCredentials(userId, accessToken, refreshToken)
        if (apiKey != null) { secureStorage.saveApiKey(apiKey); logMessage("API key saved") }

        logMessage("Signed in: userId=$userId, mode=${if (accessToken != null) "token" else "apiKey"}")
        result.success(null)
    }

    private fun handleSignOut(result: Result) {
        logMessage("Signing out")
        scope.launch {
            val sm = ensureSyncManager()
            sm.stopBackgroundSync()
            sm.resetAnchors()
            sm.clearSyncSession()
            secureStorage.clearAll()
            activeProvider = null
            syncManager = null
            logMessage("Sign out complete")
            result.success(null)
        }
    }

    private fun handleRestoreSession(result: Result) {
        if (secureStorage.hasSession()) {
            val userId = secureStorage.getUserId()
            logMessage("Session restored: userId=$userId")
            result.success(userId)
        } else {
            result.success(null)
        }
    }

    private fun handleUpdateTokens(call: MethodCall, result: Result) {
        val accessToken = call.argument<String>("accessToken")
        if (accessToken == null) { result.error("bad_args", "Missing accessToken", null); return }
        secureStorage.updateTokens(accessToken, call.argument<String>("refreshToken"))
        logMessage("Tokens updated")
        ensureSyncManager().retryOutboxIfPossible()
        result.success(null)
    }

    private fun handleGetStoredCredentials(result: Result) {
        result.success(mapOf(
            "userId" to secureStorage.getUserId(),
            "accessToken" to secureStorage.getAccessToken(),
            "refreshToken" to secureStorage.getRefreshToken(),
            "apiKey" to secureStorage.getApiKey(),
            "host" to secureStorage.getHost(),
            "customSyncUrl" to secureStorage.getCustomSyncUrl(),
            "isSyncActive" to secureStorage.isSyncActive(),
            "provider" to (activeProvider?.providerId ?: secureStorage.getProvider())
        ))
    }

    private fun handleRequestAuthorization(call: MethodCall, result: Result) {
        val types = call.argument<List<String>>("types")
        if (types == null) { result.error("bad_args", "Missing types", null); return }

        secureStorage.saveTrackedTypes(types)
        val provider = getOrCreateProvider()
        provider.setTrackedTypes(types)
        logMessage("Requesting auth for ${types.size} types via ${provider.providerName}")

        scope.launch {
            try {
                val authorized = provider.requestAuthorization(types)
                result.success(authorized)
            } catch (e: Exception) {
                logMessage("Authorization failed: ${e.message}")
                result.error("auth_failed", e.message, null)
            }
        }
    }

    private fun handleStartBackgroundSync(result: Result) {
        if (!secureStorage.hasSession()) { result.error("not_signed_in", "Not signed in", null); return }
        if (host == null) { result.error("not_configured", "Not configured", null); return }

        scope.launch {
            try {
                val started = ensureSyncManager().startBackgroundSync(host!!, customSyncUrl)
                if (started) {
                    secureStorage.setSyncActive(true)
                    logMessage("Background sync started (${getOrCreateProvider().providerName})")
                }
                result.success(started)
            } catch (e: Exception) {
                logMessage("Failed to start sync: ${e.message}")
                result.error("sync_failed", e.message, null)
            }
        }
    }

    private fun handleStopBackgroundSync(result: Result) {
        scope.launch(Dispatchers.Main) {
            ensureSyncManager().stopBackgroundSync()
            secureStorage.setSyncActive(false)
            logMessage("Background sync stopped")
            result.success(null)
        }
    }

    private fun handleSyncNow(result: Result) {
        scope.launch {
            try {
                ensureSyncManager().syncNow(host!!, customSyncUrl, fullExport = false)
                result.success(null)
            } catch (e: Exception) {
                logMessage("Sync failed: ${e.message}")
                result.error("sync_failed", e.message, null)
            }
        }
    }

    private fun handleResetAnchors(result: Result) {
        val sm = ensureSyncManager()
        sm.resetAnchors()
        sm.clearSyncSession()
        logMessage("Anchors reset")

        if (secureStorage.isSyncActive() && secureStorage.hasAuth) {
            logMessage("Triggering full export after reset...")
            scope.launch {
                sm.syncNow(host!!, customSyncUrl, fullExport = true)
                logMessage("Full export after reset completed")
            }
        }
        result.success(null)
    }

    private fun handleGetSyncStatus(result: Result) {
        result.success(ensureSyncManager().getSyncStatus())
    }

    private fun handleResumeSync(result: Result) {
        val sm = ensureSyncManager()
        if (!sm.hasResumableSyncSession()) {
            result.error("no_session", "No resumable sync session", null); return
        }
        scope.launch {
            try {
                sm.syncNow(host!!, customSyncUrl, fullExport = false)
                result.success(null)
            } catch (e: Exception) {
                result.error("sync_failed", e.message, null)
            }
        }
    }

    private fun handleClearSyncSession(result: Result) {
        ensureSyncManager().clearSyncSession()
        result.success(null)
    }

    // -----------------------------------------------------------------------
    // Provider selection
    // -----------------------------------------------------------------------

    private fun handleSetProvider(call: MethodCall, result: Result) {
        val providerId = call.argument<String>("provider")
        if (providerId == null) { result.error("bad_args", "Missing provider", null); return }

        val success = setActiveProvider(providerId)
        if (success) {
            result.success(null)
        } else {
            result.error("provider_unavailable", "Provider '$providerId' is not available on this device", null)
        }
    }

    private fun handleGetAvailableProviders(result: Result) {
        val providers = mutableListOf<Map<String, Any>>()

        if (samsungHealthManager.isAvailable()) {
            providers.add(mapOf(
                "id" to "samsung_health",
                "displayName" to "Samsung Health",
                "isAvailable" to true
            ))
        }

        if (healthConnectManager.isAvailable()) {
            providers.add(mapOf(
                "id" to "health_connect",
                "displayName" to "Health Connect",
                "isAvailable" to true
            ))
        }

        result.success(providers)
    }

    // -----------------------------------------------------------------------
    // Logging / Auth Error
    // -----------------------------------------------------------------------

    private fun emitAuthError(statusCode: Int, message: String) {
        logMessage("Auth error: HTTP $statusCode - $message")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            authErrorEventSink?.success(mapOf("statusCode" to statusCode, "message" to message))
        }
    }

    private fun logMessage(message: String) {
        Log.d(TAG, message)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            logEventSink?.success(message)
        }
    }
}
