package tk.glucodata.rus.carelink

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import tk.glucodata.rus.carelink.CareLinkConfig.long
import tk.glucodata.rus.carelink.CareLinkConfig.str
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * JugglucoNG-RUS, CareLink (stage 4b): the session after sign-in - the access key (lives about 3 h) and the refresh
 * key for renewal without a password, region addresses and the user name the cloud gives data for
 * (`display/message` wants `username`). RUS never sees or keeps the password. [toString] shows no keys and no name,
 * so the session never reaches a log as a string.
 */
data class CareLinkSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
    val username: String,
    /** Role from `users/me`: PATIENT, CARE_PARTNER...; null - not asked yet. */
    val role: String?,
    /** Patient user name of a care partner account (`links/patients`); null for a patient account. */
    val patientId: String?,
    val country: String?,
    val region: String,
    val baseUrlCareLink: String,
    val baseUrlCumulus: String,
    val tokenUrl: String,
    val clientId: String,
    val loggedInAtMs: Long,
    val refreshedAtMs: Long? = null,
) {
    val carePartner: Boolean get() = role == "CARE_PARTNER" || role == "CARE_PARTNER_OUS"

    override fun toString(): String =
        "CareLinkSession(region=$region, country=$country, role=$role, expiresAtMs=$expiresAtMs, " +
            "loggedInAtMs=$loggedInAtMs, refreshedAtMs=$refreshedAtMs)"

    fun toJson(): JSONObject = JSONObject().apply {
        put("accessToken", accessToken)
        put("refreshToken", refreshToken)
        put("expiresAtMs", expiresAtMs)
        put("username", username)
        put("role", role ?: JSONObject.NULL)
        put("patientId", patientId ?: JSONObject.NULL)
        put("country", country ?: JSONObject.NULL)
        put("region", region)
        put("baseUrlCareLink", baseUrlCareLink)
        put("baseUrlCumulus", baseUrlCumulus)
        put("tokenUrl", tokenUrl)
        put("clientId", clientId)
        put("loggedInAtMs", loggedInAtMs)
        put("refreshedAtMs", refreshedAtMs ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): CareLinkSession? = CareLinkSession(
            accessToken = o.str("accessToken") ?: return null,
            refreshToken = o.str("refreshToken") ?: return null,
            expiresAtMs = o.long("expiresAtMs") ?: return null,
            username = o.str("username") ?: return null,
            role = o.str("role"),
            patientId = o.str("patientId"),
            country = o.str("country"),
            region = o.str("region") ?: return null,
            baseUrlCareLink = o.str("baseUrlCareLink") ?: return null,
            baseUrlCumulus = o.str("baseUrlCumulus") ?: return null,
            tokenUrl = o.str("tokenUrl") ?: return null,
            clientId = o.str("clientId") ?: return null,
            loggedInAtMs = o.long("loggedInAtMs") ?: return null,
            refreshedAtMs = o.long("refreshedAtMs"),
        )

        fun decode(text: String): CareLinkSession? = CareLinkConfig.parse(text)?.let(::fromJson)
    }
}

/** Seals and opens the session file. An interface: Robolectric has no Android Keystore, tests use a software key. */
interface SessionCipher {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/** AES-256-GCM, file = 12-byte IV + cipher text with the tag. The key comes from [keyProvider]. */
class AesGcmSessionCipher(private val keyProvider: () -> SecretKey) : SessionCipher {
    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > IV_BYTES) { "sealed data too short" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    companion object {
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "rus_carelink_session"

        /** The phone's key in Android Keystore: never leaves the phone, no backup carries it. */
        val keystore: SessionCipher by lazy { AesGcmSessionCipher(::keystoreKey) }

        @Synchronized
        private fun keystoreKey(): SecretKey {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return gen.generateKey()
        }
    }
}

/**
 * Protected session store: the file `noBackupFilesDir/rus-carelink/session.bin`, sealed by [cipher] (on the phone -
 * AES-256-GCM with the Android Keystore key `rus_carelink_session`). noBackupFilesDir is in no backup: not in Android's
 * (allowBackup=false anyway) and not in upstream's settings export, which takes every shared_prefs file. Written through
 * a temporary file and rename. Blocking file and Keystore work: never on the main thread.
 */
object CareLinkVault {

    /** The session file exists but did not open (Keystore not ready before unlock, broken file): keep it, report. */
    class StorageException(cause: Throwable?) : Exception("CareLink session not readable", cause)

    @Volatile
    internal var cipher: SessionCipher = AesGcmSessionCipher.keystore

    internal fun dir(context: Context): File = File(context.applicationContext.noBackupFilesDir, "rus-carelink")

    private fun file(context: Context): File = File(dir(context), "session.bin")

    /** Is there a session - by the file alone, without opening it (cheap; for scheduling decisions). */
    fun exists(context: Context): Boolean = file(context).exists()

    /** null - no session file; [StorageException] - a file that does not open (it is kept). */
    @Synchronized
    fun load(context: Context): CareLinkSession? {
        val f = file(context)
        if (!f.exists()) return null
        val session = try {
            CareLinkSession.decode(String(cipher.open(f.readBytes()), Charsets.UTF_8))
        } catch (t: Throwable) {
            throw StorageException(t)
        }
        return session ?: throw StorageException(null)
    }

    @Synchronized
    fun save(context: Context, session: CareLinkSession) {
        val sealed = cipher.seal(session.toJson().toString().toByteArray(Charsets.UTF_8))
        writeAtomically(file(context), sealed)
    }

    @Synchronized
    fun clear(context: Context) {
        val f = file(context)
        f.delete()
        File(f.parentFile, f.name + ".tmp").delete()
    }

    internal fun writeAtomically(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: throw IllegalStateException("no folder for ${target.name}")
        if (!parent.isDirectory && !parent.mkdirs()) throw IllegalStateException("folder not created")
        val tmp = File(parent, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IllegalStateException("${target.name} not replaced")
            }
        }
    }
}
