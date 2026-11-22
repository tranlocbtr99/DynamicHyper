package com.ltm.dynamichyper.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dynamic_hyper_prefs")

class PreferencesManager(private val context: Context) {

    companion object {
        val KEY_ACTIVATED = booleanPreferencesKey("is_activated")
        val KEY_IDLE_ISLAND = booleanPreferencesKey("idle_island_enabled")
        val KEY_IDLE_WIDTH = intPreferencesKey("idle_island_width")
        val KEY_MEDIA_ISLAND = booleanPreferencesKey("media_island_enabled")
        val KEY_CHARGING_ISLAND = booleanPreferencesKey("charging_island_enabled")
        val KEY_MESSAGE_ISLAND = booleanPreferencesKey("message_island_enabled")
        val KEY_PERSISTENT_MODE = booleanPreferencesKey("persistent_mode")
    }

    val isActivated: Flow<Boolean> = context.dataStore.data.map { it[KEY_ACTIVATED] ?: false }
    val isIdleIslandEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_IDLE_ISLAND] ?: true }
    val idleIslandWidth: Flow<Int> = context.dataStore.data.map { it[KEY_IDLE_WIDTH] ?: 8 }
    val isMediaIslandEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_MEDIA_ISLAND] ?: true }
    val isChargingIslandEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_CHARGING_ISLAND] ?: true }
    val isMessageIslandEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_MESSAGE_ISLAND] ?: true }
    val isPersistentMode: Flow<Boolean> = context.dataStore.data.map { it[KEY_PERSISTENT_MODE] ?: true }

    // Aliases for UI
    val isMusicEnabled: Flow<Boolean> get() = isMediaIslandEnabled
    val isChatEnabled: Flow<Boolean> get() = isMessageIslandEnabled
    val isChargingEnabled: Flow<Boolean> get() = isChargingIslandEnabled

    suspend fun setActivated(value: Boolean) {
        context.dataStore.edit { it[KEY_ACTIVATED] = value }
    }

    suspend fun setIdleIslandEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_IDLE_ISLAND] = value }
    }

    suspend fun setIdleIslandWidth(width: Int) {
        context.dataStore.edit { it[KEY_IDLE_WIDTH] = width }
    }

    suspend fun setMediaIslandEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_MEDIA_ISLAND] = value }
    }

    suspend fun setMusicEnabled(value: Boolean) = setMediaIslandEnabled(value)

    suspend fun setChargingIslandEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_CHARGING_ISLAND] = value }
    }

    suspend fun setChargingEnabled(value: Boolean) = setChargingIslandEnabled(value)

    suspend fun setMessageIslandEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_MESSAGE_ISLAND] = value }
    }

    suspend fun setChatEnabled(value: Boolean) = setMessageIslandEnabled(value)

    suspend fun setPersistentMode(value: Boolean) {
        context.dataStore.edit { it[KEY_PERSISTENT_MODE] = value }
    }
}
