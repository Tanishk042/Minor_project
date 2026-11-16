package org.forestear.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.forestear.node.settings.KeystoreTokenCipher
import org.forestear.node.settings.MODE_KEYSTORE
import org.forestear.node.settings.PrefsKeyValueStore
import org.forestear.node.settings.TokenStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof that the backend token is encrypted with the Android
 * Keystore and that no plaintext copy remains in SharedPreferences.
 *
 * This test needs a device/emulator and has NOT been run in this workspace.
 */
@RunWith(AndroidJUnit4::class)
class TokenKeystoreTest {

    @Test
    fun tokenIsEncryptedAtRestAndRoundTrips() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("forestear_token_test", 0)
        prefs.edit().clear().commit()

        val store = TokenStore(PrefsKeyValueStore(prefs), KeystoreTokenCipher())
        val secret = "test-token-${System.nanoTime()}"
        store.set(secret)

        assertEquals(secret, store.get())
        assertEquals(MODE_KEYSTORE, store.storageMode)
        assertTrue(store.isEncrypted())

        val encrypted = prefs.getString(TokenStore.KEY_ENC, null)
        assertNotEquals("ciphertext must differ from plaintext", secret, encrypted)
        assertFalse("plaintext must not be stored", prefs.contains(TokenStore.KEY_PLAIN))
        // The raw plaintext must not appear anywhere in the prefs file.
        val all = prefs.all.values.joinToString("|") { it.toString() }
        assertFalse(all.contains(secret))

        prefs.edit().clear().commit()
    }

    @Test
    fun ciphertextSurvivesNewStoreInstance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("forestear_token_test", 0)
        prefs.edit().clear().commit()

        val secret = "persist-${System.nanoTime()}"
        TokenStore(PrefsKeyValueStore(prefs), KeystoreTokenCipher()).set(secret)

        val reopened = TokenStore(PrefsKeyValueStore(prefs), KeystoreTokenCipher())
        assertEquals(secret, reopened.get())

        prefs.edit().clear().commit()
    }
}
