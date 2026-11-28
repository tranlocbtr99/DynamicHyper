package com.ltm.dynamichyper.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dynamic_hyper_prefs")

class PreferencesManager(private val context: Context) {

    companion object {
        val KEY_ACTIVATED = booleanPreferencesKey("is_activated")
        val KEY_TERMS_ACCEPTED = booleanPreferencesKey("terms_accepted")
        val KEY_AUTOSTART_MARKED = booleanPreferencesKey("autostart_marked")
        val KEY_IDLE_ISLAND = booleanPreferencesKey("idle_island_enabled")
        val KEY_IDLE_WIDTH = intPreferencesKey("idle_island_width")
        val KEY_DEVICE_PRESET = stringPreferencesKey("device_preset")

        // Core events
        val KEY_MEDIA_ISLAND = booleanPreferencesKey("media_island_enabled")
        val KEY_CHARGING_ISLAND = booleanPreferencesKey("charging_island_enabled")
        val KEY_CHARGING_WATTAGE = booleanPreferencesKey("charging_wattage_enabled")
        val KEY_CHARGING_TIME_TO_FULL = booleanPreferencesKey("charging_time_to_full_enabled")
        val KEY_MESSAGE_ISLAND = booleanPreferencesKey("message_island_enabled")
        val KEY_PERSISTENT_MODE = booleanPreferencesKey("persistent_mode")

        // Advanced features from Dynamic Hyper
        val KEY_SCREEN_RECORDING_ISLAND = booleanPreferencesKey("screen_recording_island")
        val KEY_VPN_ISLAND = booleanPreferencesKey("vpn_island")
        val KEY_RINGER_ISLAND = booleanPreferencesKey("ringer_island_enabled")
        val KEY_BLUETOOTH_ISLAND = booleanPreferencesKey("bluetooth_island_enabled")
        val KEY_PALETTE_COLOR = booleanPreferencesKey("palette_color_enabled")

        // Idle Island Customization & Call Island
        val KEY_IDLE_ISLAND_STYLE = stringPreferencesKey("idle_island_style")
        val KEY_IDLE_ISLAND_MODE = stringPreferencesKey("idle_island_mode") // "compact" or "widget"
        val KEY_NOTIFICATION_GRADIENT_GLOW = booleanPreferencesKey("notification_gradient_glow")
        val KEY_PERSISTENT_NOTIFICATION_ISLAND = booleanPreferencesKey("persistent_notification_island")
        val KEY_CALL_ISLAND_ENABLED = booleanPreferencesKey("call_island_enabled")
        val KEY_BLOCK_FULLSCREEN_CALL = booleanPreferencesKey("block_fullscreen_call")
        val KEY_HIDE_ORIGINAL_NOTIFICATION = booleanPreferencesKey("hide_original_notification_v2")

        // Exact Bank Information: Techcombank - Tran Minh Loc - 4444444790
        val KEY_BANK_NAME = stringPreferencesKey("bank_name")
        val KEY_BANK_ACCOUNT = stringPreferencesKey("bank_account")
        val KEY_BANK_OWNER = stringPreferencesKey("bank_owner")

        // Per-App Controls & Floating Notification Suppress (Blacklist model: an app is allowed unless explicitly disabled)
        val KEY_DISABLED_PACKAGES = androidx.datastore.preferences.core.stringSetPreferencesKey("disabled_packages")
        val KEY_DISABLED_FLOATING_PACKAGES = androidx.datastore.preferences.core.stringSetPreferencesKey("disabled_floating_packages")
    }

    val disabledPackages: Flow<Set<String>> = context.dataStore.data.map { it[KEY_DISABLED_PACKAGES] ?: emptySet() }
    val disabledFloatingPackages: Flow<Set<String>> = context.dataStore.data.map { it[KEY_DISABLED_FLOATING_PACKAGES] ?: emptySet() }

    suspend fun setAppAllowed(packageName: String, allowed: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_DISABLED_PACKAGES]?.toMutableSet() ?: mutableSetOf()
            if (allowed) {
                current.remove(packageName)
            } else {
                current.add(packageName)
            }
            prefs[KEY_DISABLED_PACKAGES] = current
        }
    }

    suspend fun setAppFloatingDisabled(packageName: String, disabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_DISABLED_FLOATING_PACKAGES]?.toMutableSet() ?: mutableSetOf()
            if (disabled) current.add(packageName) else current.remove(packageName)
            prefs[KEY_DISABLED_FLOATING_PACKAGES] = current
        }
    }

    fun isPackageAllowed(packageName: String, disabledSet: Set<String>): Boolean {
        // App được phép hiển thị nếu KHÔNG nằm trong danh sách bị tắt
        return !disabledSet.contains(packageName)
    }

    val isActivated: Flow<Boolean> = context.dataStore.data.map { it[KEY_ACTIVATED] ?: false }
    val isTermsAccepted: Flow<Boolean> = context.dataStore.data.map { it[KEY_TERMS_ACCEPTED] ?: false }
    val isAutoStartMarked: Flow<Boolean> = context.dataStore.data.map { it[KEY_AUTOSTART_MARKED] ?: false }
    val isIdleIslandEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_IDLE_ISLAND] ?: true }
    val idleIslandWidth: Flow<Int> = context.dataStore.data.map { it[KEY_IDLE_WIDTH] ?: 10 }
    val devicePreset: Flow<String> = context.dataStore.data.map { it[KEY_DEVICE_PRESET] ?: "redmi_k" }

    val isMediaIslandEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_MEDIA_ISLAND] ?: true }
    val isChargingIslandEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_CHARGING_ISLAND] ?: true }
    val isChargingWattageEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_CHARGING_WATTAGE] ?: true }
    val isChargingTimeToFullEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_CHARGING_TIME_TO_FULL] ?: true }
    val isMessageIslandEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_MESSAGE_ISLAND] ?: true }
    val isPersistentMode: Flow<Boolean> = context.dataStore.data.map { it[KEY_PERSISTENT_MODE] ?: true }

    // Advanced features
    val isScreenRecordingIslandEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_SCREEN_RECORDING_ISLAND] ?: true }
    val isVpnIslandEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_VPN_ISLAND] ?: true }
    val isRingerIslandEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_RINGER_ISLAND] ?: true }
    val isBluetoothIslandEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_BLUETOOTH_ISLAND] ?: true }
    val isPaletteColorEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_PALETTE_COLOR] ?: true }

    // Idle Island Customization & Call Island
    val idleIslandStyle: Flow<String> = context.dataStore.data.map { it[KEY_IDLE_ISLAND_STYLE] ?: "minimal" }
    val idleIslandMode: Flow<String> = context.dataStore.data.map { it[KEY_IDLE_ISLAND_MODE] ?: "compact" }
    val isNotificationGradientGlow: Flow<Boolean> = context.dataStore.data.map { it[KEY_NOTIFICATION_GRADIENT_GLOW] ?: true }
    val isPersistentNotificationIsland: Flow<Boolean> = context.dataStore.data.map { it[KEY_PERSISTENT_NOTIFICATION_ISLAND] ?: true }
    val isCallIslandEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_CALL_ISLAND_ENABLED] ?: true }
    val isBlockFullscreenCall: Flow<Boolean> = context.dataStore.data.map { it[KEY_BLOCK_FULLSCREEN_CALL] ?: true }
    val isHideOriginalNotification: Flow<Boolean> = context.dataStore.data.map { it[KEY_HIDE_ORIGINAL_NOTIFICATION] ?: true }

    suspend fun setHideOriginalNotification(enabled: Boolean) {
        context.dataStore.edit { it[KEY_HIDE_ORIGINAL_NOTIFICATION] = enabled }
    }

    // Aliases
    val isMusicEnabled: Flow<Boolean> get() = isMediaIslandEnabled
    val isChatEnabled: Flow<Boolean> get() = isMessageIslandEnabled
    val isChargingEnabled: Flow<Boolean> get() = isChargingIslandEnabled

    // Bank Information Defaults
    val bankName: Flow<String> = context.dataStore.data.map { it[KEY_BANK_NAME] ?: "Techcombank" }
    val bankAccount: Flow<String> = context.dataStore.data.map { it[KEY_BANK_ACCOUNT] ?: "4444444790" }
    val bankOwner: Flow<String> = context.dataStore.data.map { it[KEY_BANK_OWNER] ?: "Tran Minh Loc" }

    suspend fun setTermsAccepted(value: Boolean) {
        context.dataStore.edit { it[KEY_TERMS_ACCEPTED] = value }
    }

    suspend fun setAutoStartMarked(value: Boolean) {
        context.dataStore.edit { it[KEY_AUTOSTART_MARKED] = value }
    }

    suspend fun setActivated(value: Boolean) {
        context.dataStore.edit { it[KEY_ACTIVATED] = value }
    }

    suspend fun setIdleIslandEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_IDLE_ISLAND] = value }
    }

    suspend fun setIdleIslandWidth(width: Int) {
        context.dataStore.edit { it[KEY_IDLE_WIDTH] = width }
    }

    suspend fun setDevicePreset(preset: String) {
        context.dataStore.edit { it[KEY_DEVICE_PRESET] = preset }
    }

    suspend fun setMediaIslandEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_MEDIA_ISLAND] = value }
    }

    suspend fun setMusicEnabled(value: Boolean) = setMediaIslandEnabled(value)

    suspend fun setChargingIslandEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_CHARGING_ISLAND] = value }
    }

    suspend fun setChargingWattageEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_CHARGING_WATTAGE] = value }
    }

    suspend fun setChargingTimeToFullEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_CHARGING_TIME_TO_FULL] = value }
    }

    suspend fun setChargingEnabled(value: Boolean) = setChargingIslandEnabled(value)

    suspend fun setMessageIslandEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_MESSAGE_ISLAND] = value }
    }

    suspend fun setChatEnabled(value: Boolean) = setMessageIslandEnabled(value)

    suspend fun setPersistentMode(value: Boolean) {
        context.dataStore.edit { it[KEY_PERSISTENT_MODE] = value }
    }

    suspend fun setScreenRecordingIslandEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_SCREEN_RECORDING_ISLAND] = value }
    }

    suspend fun setVpnIslandEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_VPN_ISLAND] = value }
    }

    suspend fun setRingerIslandEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_RINGER_ISLAND] = value }
    }

    suspend fun setBluetoothIslandEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_BLUETOOTH_ISLAND] = value }
    }

    suspend fun setPaletteColorEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_PALETTE_COLOR] = value }
    }

    suspend fun setIdleIslandStyle(style: String) {
        context.dataStore.edit { it[KEY_IDLE_ISLAND_STYLE] = style }
    }

    suspend fun setIdleIslandMode(mode: String) {
        context.dataStore.edit { it[KEY_IDLE_ISLAND_MODE] = mode }
    }

    suspend fun setNotificationGradientGlow(value: Boolean) {
        context.dataStore.edit { it[KEY_NOTIFICATION_GRADIENT_GLOW] = value }
    }

    suspend fun setPersistentNotificationIsland(value: Boolean) {
        context.dataStore.edit { it[KEY_PERSISTENT_NOTIFICATION_ISLAND] = value }
    }

    suspend fun setCallIslandEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_CALL_ISLAND_ENABLED] = value }
    }

    suspend fun setBlockFullscreenCall(value: Boolean) {
        context.dataStore.edit { it[KEY_BLOCK_FULLSCREEN_CALL] = value }
    }
}
