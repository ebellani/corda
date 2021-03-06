package net.corda.node.utilities

import net.corda.core.utilities.CertificateAndKeyPair
import net.corda.core.crypto.Crypto
import net.corda.core.utilities.cert
import net.corda.core.internal.exists
import net.corda.core.internal.read
import net.corda.core.internal.toX509CertHolder
import net.corda.core.internal.write
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509CertificateHolder
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.security.*
import java.security.cert.CertPath
import java.security.cert.Certificate
import java.security.cert.CertificateFactory

val KEYSTORE_TYPE = "JKS"

/**
 * Helper method to either open an existing keystore for modification, or create a new blank keystore.
 * @param keyStoreFilePath location of KeyStore file.
 * @param storePassword password to open the store. This does not have to be the same password as any keys stored,
 * but for SSL purposes this is recommended.
 * @return returns the KeyStore opened/created.
 */
fun loadOrCreateKeyStore(keyStoreFilePath: Path, storePassword: String): KeyStore {
    val pass = storePassword.toCharArray()
    val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
    if (keyStoreFilePath.exists()) {
        keyStoreFilePath.read { keyStore.load(it, pass) }
    } else {
        keyStore.load(null, pass)
        keyStoreFilePath.write { keyStore.store(it, pass) }
    }
    return keyStore
}

/**
 * Helper method to open an existing keystore for modification/read.
 * @param keyStoreFilePath location of KeyStore file which must exist, or this will throw FileNotFoundException.
 * @param storePassword password to open the store. This does not have to be the same password as any keys stored,
 * but for SSL purposes this is recommended.
 * @return returns the KeyStore opened.
 * @throws IOException if there was an error reading the key store from the file.
 * @throws KeyStoreException if the password is incorrect or the key store is damaged.
 */
@Throws(KeyStoreException::class, IOException::class)
fun loadKeyStore(keyStoreFilePath: Path, storePassword: String): KeyStore {
    return keyStoreFilePath.read { loadKeyStore(it, storePassword) }
}

/**
 * Helper method to open an existing keystore for modification/read.
 * @param input stream containing a KeyStore e.g. loaded from a resource file.
 * @param storePassword password to open the store. This does not have to be the same password as any keys stored,
 * but for SSL purposes this is recommended.
 * @return returns the KeyStore opened.
 * @throws IOException if there was an error reading the key store from the stream.
 * @throws KeyStoreException if the password is incorrect or the key store is damaged.
 */
@Throws(KeyStoreException::class, IOException::class)
fun loadKeyStore(input: InputStream, storePassword: String): KeyStore {
    val pass = storePassword.toCharArray()
    val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
    input.use {
        keyStore.load(input, pass)
    }
    return keyStore
}

/**
 * Helper extension method to add, or overwrite any key data in store.
 * @param alias name to record the private key and certificate chain under.
 * @param key cryptographic key to store.
 * @param password password for unlocking the key entry in the future. This does not have to be the same password as any keys stored,
 * but for SSL purposes this is recommended.
 * @param chain the sequence of certificates starting with the public key certificate for this key and extending to the root CA cert.
 */
fun KeyStore.addOrReplaceKey(alias: String, key: Key, password: CharArray, chain: Array<out X509CertificateHolder>) {
    addOrReplaceKey(alias, key, password, chain.map { it.cert }.toTypedArray<Certificate>())
}

/**
 * Helper extension method to add, or overwrite any key data in store.
 * @param alias name to record the private key and certificate chain under.
 * @param key cryptographic key to store.
 * @param password password for unlocking the key entry in the future. This does not have to be the same password as any keys stored,
 * but for SSL purposes this is recommended.
 * @param chain the sequence of certificates starting with the public key certificate for this key and extending to the root CA cert.
 */
fun KeyStore.addOrReplaceKey(alias: String, key: Key, password: CharArray, chain: Array<out Certificate>) {
    if (containsAlias(alias)) {
        this.deleteEntry(alias)
    }
    this.setKeyEntry(alias, key, password, chain)
}

/**
 * Helper extension method to add, or overwrite any public certificate data in store.
 * @param alias name to record the public certificate under.
 * @param cert certificate to store.
 */
fun KeyStore.addOrReplaceCertificate(alias: String, cert: Certificate) {
    if (containsAlias(alias)) {
        this.deleteEntry(alias)
    }
    this.setCertificateEntry(alias, cert)
}

/**
 * Helper method save KeyStore to storage.
 * @param keyStoreFilePath the file location to save to.
 * @param storePassword password to access the store in future. This does not have to be the same password as any keys stored,
 * but for SSL purposes this is recommended.
 */
fun KeyStore.save(keyStoreFilePath: Path, storePassword: String) = keyStoreFilePath.write { store(it, storePassword) }

fun KeyStore.store(out: OutputStream, password: String) = store(out, password.toCharArray())

/**
 * Extract public and private keys from a KeyStore file assuming storage alias is known.
 * @param alias The name to lookup the Key and Certificate chain from.
 * @param keyPassword Password to unlock the private key entries.
 * @return The KeyPair found in the KeyStore under the specified alias.
 */
fun KeyStore.getKeyPair(alias: String, keyPassword: String): KeyPair = getCertificateAndKeyPair(alias, keyPassword).keyPair

/**
 * Helper method to load a Certificate and KeyPair from their KeyStore.
 * The access details should match those of the createCAKeyStoreAndTrustStore call used to manufacture the keys.
 * @param alias The name to search for the data. Typically if generated with the methods here this will be one of
 * CERT_PRIVATE_KEY_ALIAS, ROOT_CA_CERT_PRIVATE_KEY_ALIAS, INTERMEDIATE_CA_PRIVATE_KEY_ALIAS defined above.
 * @param keyPassword The password for the PrivateKey (not the store access password).
 */
fun KeyStore.getCertificateAndKeyPair(alias: String, keyPassword: String): CertificateAndKeyPair {
    val cert = getX509Certificate(alias)
    val publicKey = Crypto.toSupportedPublicKey(cert.subjectPublicKeyInfo)
    return CertificateAndKeyPair(cert, KeyPair(publicKey, getSupportedKey(alias, keyPassword)))
}

/**
 * Extract public X509 certificate from a KeyStore file assuming storage alias is known.
 * @param alias The name to lookup the Key and Certificate chain from.
 * @return The X509Certificate found in the KeyStore under the specified alias.
 */
fun KeyStore.getX509Certificate(alias: String): X509CertificateHolder {
    val certificate = getCertificate(alias) ?: throw IllegalArgumentException("No certificate under alias \"$alias\"")
    return certificate.toX509CertHolder()
}

/**
 * Extract a private key from a KeyStore file assuming storage alias is known.
 * By default, a JKS keystore returns PrivateKey implementations supported by the SUN provider.
 * For instance, if one imports a BouncyCastle ECC key, JKS will return a SUN ECC key implementation on getKey.
 * To convert to a supported implementation, an encode->decode method is applied to the keystore's returned object.
 * @param alias The name to lookup the Key.
 * @param keyPassword Password to unlock the private key entries.
 * @return the requested private key in supported type.
 * @throws KeyStoreException if the keystore has not been initialized.
 * @throws NoSuchAlgorithmException if the algorithm for recovering the key cannot be found (not supported from the Keystore provider).
 * @throws UnrecoverableKeyException if the key cannot be recovered (e.g., the given password is wrong).
 * @throws IllegalArgumentException on not supported scheme or if the given key specification
 * is inappropriate for a supported key factory to produce a private key.
 */
fun KeyStore.getSupportedKey(alias: String, keyPassword: String): PrivateKey {
    val keyPass = keyPassword.toCharArray()
    val key = getKey(alias, keyPass) as PrivateKey
    return Crypto.toSupportedPrivateKey(key)
}

class KeyStoreWrapper(private val storePath: Path, private val storePassword: String) {
    private val keyStore = storePath.read { loadKeyStore(it, storePassword) }

    private fun createCertificate(serviceName: X500Name, pubKey: PublicKey): CertPath {
        val clientCertPath = keyStore.getCertificateChain(X509Utilities.CORDA_CLIENT_CA)
        // Assume key password = store password.
        val clientCA = certificateAndKeyPair(X509Utilities.CORDA_CLIENT_CA)
        // Create new keys and store in keystore.
        val cert = X509Utilities.createCertificate(CertificateType.IDENTITY, clientCA.certificate, clientCA.keyPair, serviceName, pubKey)
        val certPath = CertificateFactory.getInstance("X509").generateCertPath(listOf(cert.cert) + clientCertPath)
        require(certPath.certificates.isNotEmpty()) { "Certificate path cannot be empty" }
        return certPath
    }

    fun saveNewKeyPair(serviceName: X500Name, privateKeyAlias: String, keyPair: KeyPair) {
        val certPath = createCertificate(serviceName, keyPair.public)
        // Assume key password = store password.
        keyStore.addOrReplaceKey(privateKeyAlias, keyPair.private, storePassword.toCharArray(), certPath.certificates.toTypedArray())
        keyStore.save(storePath, storePassword)
    }

    fun savePublicKey(serviceName: X500Name, pubKeyAlias: String, pubKey: PublicKey) {
        val certPath = createCertificate(serviceName, pubKey)
        // Assume key password = store password.
        keyStore.addOrReplaceCertificate(pubKeyAlias, certPath.certificates.first())
        keyStore.save(storePath, storePassword)
    }

    // Delegate methods to keystore. Sadly keystore doesn't have an interface.
    fun containsAlias(alias: String) = keyStore.containsAlias(alias)

    fun getX509Certificate(alias: String) = keyStore.getX509Certificate(alias)

    fun getCertificateChain(alias: String): Array<out Certificate> = keyStore.getCertificateChain(alias)

    fun getCertificate(alias: String): Certificate = keyStore.getCertificate(alias)

    fun certificateAndKeyPair(alias: String): CertificateAndKeyPair = keyStore.getCertificateAndKeyPair(alias, storePassword)
}
