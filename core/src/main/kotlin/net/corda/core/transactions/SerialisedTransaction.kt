package net.corda.core.transactions

import net.corda.core.contracts.PrivacySalt
import net.corda.core.crypto.MerkleTree
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.sha256
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.SerializationDefaults
import net.corda.core.serialization.serialize
import net.corda.core.utilities.OpaqueBytes
import java.nio.ByteBuffer

@CordaSerializable
data class SerialisedTransaction(val componentGroups: List<ComponentGroup>, val privacySalt: PrivacySalt?) {

    /**
     * Builds whole Merkle tree for a transaction.
     */
    val merkleTree: MerkleTree by lazy {
        if (privacySalt != null) {
            MerkleTree.getMerkleTree(listOf(privacySalt.sha256()) + groupsMerkleRoots)
        } else {
            MerkleTree.getMerkleTree(groupsMerkleRoots)
        }
    }

    /**
     * Calculate the hashes of the sub-components of the transaction, that are used to build its Merkle tree.
     * The root of the tree is the transaction identifier. The tree structure is helpful for privacy, please
     * see the user-guide section "Transaction tear-offs" to learn more about this topic.
     */
    val groupsMerkleRoots: List<SecureHash> get() = componentGroups.mapIndexed { index, it ->
        if (it.components.isNotEmpty()) {
            MerkleTree.getMerkleTree(it.components.mapIndexed { indexInternal, itInternal ->
                serializedHash(itInternal, privacySalt, index, indexInternal) }).hash
        } else {
            SecureHash.zeroHash
        }
    }

    // If a privacy salt is provided, the resulted output (Merkle-leaf) is computed as
    // Hash(serializedObject || nonce), where nonce is computed from privacy salt and the path indices.
    private fun <T : Any> serializedHash(x: T, privacySalt: PrivacySalt?, index: Int, indexInternal: Int): SecureHash {
        return if (privacySalt != null)
            serializedHash(x, computeNonce(privacySalt, index, indexInternal))
        else
            serializedHash(x)
    }

    private fun <T : Any> serializedHash(x: T, nonce: SecureHash): SecureHash {
        return if (x !is PrivacySalt) // PrivacySalt is not required to have an accompanied nonce.
            (x.serialize(context = SerializationDefaults.P2P_CONTEXT.withoutReferences()).bytes + nonce.bytes).sha256()
        else
            serializedHash(x)
    }

    private fun <T : Any> serializedHash(x: T): SecureHash = x.serialize(context = SerializationDefaults.P2P_CONTEXT.withoutReferences()).bytes.sha256()

    // TODO: Use HMAC or even SHA256d Vs SHA256
    // see https://crypto.stackexchange.com/questions/7895/weaknesses-in-sha-256d
    // see https://crypto.stackexchange.com/questions/779/hashing-or-encrypting-twice-to-increase-security?rq=1
    // see https://crypto.stackexchange.com/questions/9369/how-is-input-message-for-sha-2-padded
    // see https://security.stackexchange.com/questions/79577/whats-the-difference-between-hmac-sha256key-data-and-sha256key-data

    // The nonce is computed as Hash(privacySalt || index || indexInternal).
    private fun computeNonce(privacySalt: PrivacySalt, index: Int, indexInternal: Int) = (privacySalt.bytes + ByteBuffer.allocate(4).putInt(index).array() + ByteBuffer.allocate(4).putInt(indexInternal).array()).sha256()
}

/**
 * A ComponentGroup is used to store the full list of transaction components of the same type in serialised form.
 * Practically, a group per component type of a transaction is required; thus, there will be a group for input states,
 * a group for all attachments (if there are any) etc.
 */
@CordaSerializable
data class ComponentGroup(val components: List<OpaqueBytes>)