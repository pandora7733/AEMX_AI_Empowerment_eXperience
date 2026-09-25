package com.sihoo.aemx.accessibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CredentialStorageDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "credential-test-only"
    private val store = KeystoreCredentialProvider(context, namespace)
    private val kyoboFile get() = File(context.noBackupFilesDir, "$namespace/mok.android.bin")

    @After fun cleanup() {
        store.delete("교보문고")
        store.delete("알라딘")
    }

    @Test fun encryptedRoundTripSurvivesNewProviderAndDelete() {
        store.save("교보문고", "test-user-가", "synthetic-secret-123!")
        val first = kyoboFile.readBytes()
        assertFalse(String(first, Charsets.ISO_8859_1).contains("synthetic-secret"))
        assertFalse(String(first, Charsets.ISO_8859_1).contains("test-user"))
        val reopened = KeystoreCredentialProvider(context, namespace)
        assertEquals("test-user-가", reopened.getCredential("kyobobook", CredentialField.ID))
        assertEquals("synthetic-secret-123!", reopened.getCredential("교보문고", CredentialField.PASSWORD))
        assertNull(reopened.getCredential("알라딘", CredentialField.PASSWORD))
        store.save("교보문고", "test-user-가", "synthetic-secret-123!")
        assertFalse(first.contentEquals(kyoboFile.readBytes()))
        store.delete("교보문고")
        assertNull(reopened.getCredential("교보문고", CredentialField.ID))
    }

    @Test fun tamperingAndCrossAppCopyCannotDecrypt() {
        store.save("교보문고", "synthetic-user", "synthetic-password")
        store.save("알라딘", "other-user", "other-password")
        val bytes = kyoboFile.readBytes()
        File(context.noBackupFilesDir, "$namespace/kr.co.aladin.third_shop.bin").writeBytes(bytes)
        assertTrue(runCatching { store.getCredential("알라딘", CredentialField.PASSWORD) }.isFailure)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        kyoboFile.writeBytes(bytes)
        assertTrue(runCatching { store.getCredential("교보문고", CredentialField.PASSWORD) }.isFailure)
    }
}
