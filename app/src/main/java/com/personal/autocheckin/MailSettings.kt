package com.personal.autocheckin

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@SuppressLint("ApplySharedPref")
class MailSettings(context: Context) {
    private val prefs = context.getSharedPreferences("mail_settings", Context.MODE_PRIVATE)
    val enabled: Boolean get() = prefs.getBoolean("enabled", false)
    val sender: String get() = prefs.getString("sender", "749990226@qq.com")!!
    val recipient: String get() = prefs.getString("recipient", "749990226@qq.com")!!
    val hasSecret: Boolean get() = prefs.contains("secret")
    val lowBattery: Boolean get() = prefs.getBoolean("lowBattery", true)
    var blocked: Boolean
        get() = prefs.getBoolean("blocked", false)
        set(value) { prefs.edit().putBoolean("blocked", value).commit() }
    var batteryReported: Boolean
        get() = prefs.getBoolean("batteryReported", false)
        set(value) { prefs.edit().putBoolean("batteryReported", value).commit() }
    var lastStatus: String
        get() = prefs.getString("status", "尚未发送邮件")!!
        set(value) { prefs.edit().putString("status", value).commit() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("autocheckin.smtp", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("autocheckin.smtp", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun secret(): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(prefs.getString("secret", ""), Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    fun save(sender: String, recipient: String, secret: String, enabled: Boolean, lowBattery: Boolean) {
        require(MailProtocol.validAddress(sender) && sender.endsWith("@qq.com", ignoreCase = true))
        require(MailProtocol.validAddress(recipient))
        require(!enabled || secret.isNotBlank() || hasSecret)
        require(sender == this.sender || secret.isNotBlank() || !hasSecret)
        val edit = prefs.edit().putString("sender", sender).putString("recipient", recipient)
            .putBoolean("enabled", enabled).putBoolean("lowBattery", lowBattery).putBoolean("blocked", false)
        if (secret.isNotBlank()) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            edit.putString("secret", Base64.encodeToString(cipher.doFinal(secret.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
        check(edit.commit())
    }
}
