package org.forestear.node.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory KeyValueStore; avoids Android SharedPreferences in JVM tests. */
private class FakeKv : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

private class FakeCipher(
    override val mode: String = MODE_KEYSTORE,
    private val fails: Boolean = false
) : TokenCipher {
    override fun encrypt(plaintext: String): String? =
        if (fails) null else "enc:" + plaintext.reversed()

    override fun decrypt(blob: String): String? =
        if (blob.startsWith("enc:")) blob.removePrefix("enc:").reversed() else null
}

class TokenStoreTest {

    @Test
    fun `roundtrip stores ciphertext not plaintext`() {
        val kv = FakeKv()
        val store = TokenStore(kv, FakeCipher())

        store.set("  super-secret-token  ")

        assertEquals("super-secret-token", store.get())
        assertNull("plaintext key must not be written", kv.getString(TokenStore.KEY_PLAIN))
        assertEquals("enc:nekot-terces-repus", kv.getString(TokenStore.KEY_ENC))
        assertEquals(MODE_KEYSTORE, store.storageMode)
        assertTrue(store.isEncrypted())
    }

    @Test
    fun `legacy plaintext token is migrated on read`() {
        val kv = FakeKv()
        kv.map[TokenStore.KEY_PLAIN] = "old-token"
        val store = TokenStore(kv, FakeCipher())

        assertEquals("old-token", store.get())
        assertNull(kv.getString(TokenStore.KEY_PLAIN))
        assertEquals("enc:nekot-dlo", kv.getString(TokenStore.KEY_ENC))
    }

    @Test
    fun `encryption failure falls back to plaintext and says so`() {
        val kv = FakeKv()
        val store = TokenStore(kv, FakeCipher(fails = true))

        store.set("tok")

        assertEquals("tok", store.get())
        assertEquals("tok", kv.getString(TokenStore.KEY_PLAIN))
        assertNull(kv.getString(TokenStore.KEY_ENC))
        assertEquals(MODE_PLAINTEXT_FALLBACK, store.storageMode)
        assertFalse(store.isEncrypted())
    }

    @Test
    fun `unreadable ciphertext is dropped instead of looping`() {
        val kv = FakeKv()
        kv.map[TokenStore.KEY_ENC] = "garbage"
        val store = TokenStore(kv, FakeCipher())

        assertEquals("", store.get())
        assertNull(kv.getString(TokenStore.KEY_ENC))
    }

    @Test
    fun `unreadable ciphertext falls back to legacy plaintext`() {
        val kv = FakeKv()
        kv.map[TokenStore.KEY_ENC] = "garbage"
        kv.map[TokenStore.KEY_PLAIN] = "still-here"
        val store = TokenStore(kv, FakeCipher())

        assertEquals("still-here", store.get())
        assertEquals("enc:ereh-llits", kv.getString(TokenStore.KEY_ENC))
        assertNull(kv.getString(TokenStore.KEY_PLAIN))
    }

    @Test
    fun `empty value clears both keys`() {
        val kv = FakeKv()
        val store = TokenStore(kv, FakeCipher())
        store.set("tok")

        store.set("   ")

        assertEquals("", store.get())
        assertNull(kv.getString(TokenStore.KEY_ENC))
        assertNull(kv.getString(TokenStore.KEY_PLAIN))
    }

    @Test
    fun `unset mode before any token is stored`() {
        val store = TokenStore(FakeKv(), FakeCipher())
        assertEquals("unset", store.storageMode)
        assertFalse(store.isEncrypted())
    }

    @Test
    fun `plaintext cipher reports fallback mode`() {
        val store = TokenStore(FakeKv(), PlaintextTokenCipher())
        store.set("tok")

        assertEquals("tok", store.get())
        assertEquals(MODE_PLAINTEXT_FALLBACK, store.storageMode)
        assertFalse(store.isEncrypted())
    }
}
