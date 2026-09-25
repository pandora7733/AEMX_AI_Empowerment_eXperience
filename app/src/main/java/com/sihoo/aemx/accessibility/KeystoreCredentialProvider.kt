package com.sihoo.aemx.accessibility

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** All callers use CredentialIo: neither disk IO nor Keystore work belongs on the UI thread. */
internal object CredentialIo {
    val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "aemx-credentials").apply { isDaemon = true }
    }
}

internal class KeystoreCredentialProvider(context: Context, private val namespace: String = "credentials-v1") : CredentialProvider {
    private val directory = File(context.noBackupFilesDir, namespace)

    private fun packageName(app: String) = requireNotNull(AppLauncher.packageFor(app))
    private fun file(pkg: String) = AtomicFile(File(directory, "$pkg.bin"))
    private fun alias(pkg: String) = "aemx.$namespace.$pkg"
    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(pkg: String, create: Boolean): SecretKey {
        (keystore().getKey(alias(pkg), null) as? SecretKey)?.let { return it }
        check(create) { "저장된 로그인 정보를 다시 등록해 주세요." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias(pkg), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    fun save(app: String, id: String, password: String) = synchronized(lock) {
        require(id.isNotBlank() && password.isNotEmpty() && id.length <= 2000 && password.length <= 2000)
        val pkg = packageName(app)
        check(directory.isDirectory || directory.mkdirs())
        val plain = JSONObject().put("id", id).put("password", password).toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(pkg, true))
            cipher.updateAAD("v1:$pkg".toByteArray(Charsets.UTF_8))
            check(cipher.iv.size == 12)
            val envelope = byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
            val destination = file(pkg)
            val stream = destination.startWrite()
            try {
                stream.write(envelope)
                destination.finishWrite(stream)
            } catch (error: Exception) {
                destination.failWrite(stream)
                throw error
            }
        } finally {
            plain.fill(0)
        }
    }

    override fun getCredential(app: String, field: CredentialField): CharSequence? = synchronized(lock) {
        val pkg = packageName(app)
        val source = file(pkg)
        if (!source.baseFile.exists() && !File(source.baseFile.path + ".bak").exists()) return@synchronized null
        val envelope = source.openRead().use { input ->
            val bytes = input.readBytes()
            require(bytes.size in 30..32768)
            bytes
        }
        require(envelope[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(pkg, false), GCMParameterSpec(128, envelope.copyOfRange(1, 13)))
        cipher.updateAAD("v1:$pkg".toByteArray(Charsets.UTF_8))
        val plain = cipher.doFinal(envelope.copyOfRange(13, envelope.size))
        try {
            JSONObject(String(plain, Charsets.UTF_8)).getString(if (field == CredentialField.ID) "id" else "password")
        } finally {
            plain.fill(0)
        }
    }

    fun delete(app: String) = synchronized(lock) {
        val pkg = packageName(app)
        // Destroy the key first, so even a failed file deletion leaves no usable credential.
        keystore().deleteEntry(alias(pkg))
        file(pkg).delete()
    }

    companion object { private val lock = Any() }
}
