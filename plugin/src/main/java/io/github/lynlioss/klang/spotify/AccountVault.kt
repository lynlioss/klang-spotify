package io.github.lynlioss.klang.spotify

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.util.UUID

/** Same AES-256-GCM/Tink approach as YumaPlayer; keyset protected by Android Keystore. */
internal class AccountVault private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("source_accounts", Context.MODE_PRIVATE)
    private val aead: Aead
    init {
        AeadConfig.register()
        aead = AndroidKeysetManager.Builder().withSharedPref(context, "keyset", "source_keyset")
            .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
            .withMasterKeyUri("android-keystore://klang_spotify_accounts")
            .build().keysetHandle.getPrimitive(Aead::class.java)
    }
    @Synchronized fun get(key: String): String? = prefs.getString(key, null)?.let {
        String(aead.decrypt(Base64.decode(it, Base64.NO_WRAP), key.toByteArray()), Charsets.UTF_8)
    }
    @Synchronized fun snapshot(keys: List<String>): Map<String, String?> = keys.associateWith(::get)
    fun put(key: String, value: String?) = putAll(mapOf(key to value))
    @Synchronized fun putAll(values: Map<String, String?>) {
        val edit = prefs.edit()
        values.forEach { (key, value) ->
            if (value == null) edit.remove(key) else edit.putString(key,
                Base64.encodeToString(aead.encrypt(value.toByteArray(Charsets.UTF_8), key.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
        }
        check(edit.commit()) { "Couldn't save the account on this device" }
    }
    fun revision(service: String) = get("${service}_generation") ?: "anonymous"
    suspend fun spotifyLogout() = SpotifySession.disconnect(context)
    fun qobuzLogout() = putAll(mapOf("qobuz_token" to null, "qobuz_profile" to null,
        "qobuz_app_secret" to null, "qobuz_generation" to UUID.randomUUID().toString()))
    companion object {
        private var instance: AccountVault? = null
        @Synchronized fun get(context: Context) = instance ?: AccountVault(context.applicationContext).also { instance = it }
    }
}
