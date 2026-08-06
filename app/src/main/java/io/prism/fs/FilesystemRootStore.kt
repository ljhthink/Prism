package io.prism.fs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 文件系统根目录授权持久化仓库（ADR-006 5.3 补充）。
 *
 * 持久化「逻辑目录名 → SAF 树 URI」映射，配合系统级 `takePersistableUriPermission`，
 * 使 [SafFileAccess] 的授权根目录在应用重启后仍有效。
 *
 * **实现**：DataStore Preferences 中以 JSON 字符串存储整张映射表（单键
 * `filesystem_roots_json`），统一读写避免多键并发；解码失败容错回退空表。
 */
class FilesystemRootStore(
    private val dataStore: DataStore<Preferences>
) {

    private companion object {
        val ROOTS_KEY = stringPreferencesKey("filesystem_roots_json")
        private val SERIALIZER =
            MapSerializer(String.serializer(), String.serializer())
    }

    /** 读取全部授权根目录（逻辑目录名 → 树 URI）。 */
    suspend fun loadRoots(): Map<String, String> = decode(dataStore.data.first()[ROOTS_KEY])

    /** 新增或覆盖一个授权根目录。 */
    suspend fun putRoot(name: String, treeUri: String) {
        dataStore.edit { prefs ->
            val current = decode(prefs[ROOTS_KEY]).toMutableMap()
            current[name] = treeUri
            prefs[ROOTS_KEY] = Json.encodeToString(SERIALIZER, current)
        }
    }

    /** 移除一个授权根目录。 */
    suspend fun removeRoot(name: String) {
        dataStore.edit { prefs ->
            val current = decode(prefs[ROOTS_KEY]).toMutableMap()
            current.remove(name)
            prefs[ROOTS_KEY] = Json.encodeToString(SERIALIZER, current)
        }
    }

    private fun decode(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching { Json.decodeFromString(SERIALIZER, json) }
            .getOrDefault(emptyMap())
    }
}