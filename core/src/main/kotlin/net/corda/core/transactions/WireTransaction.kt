package net.corda.core.transactions

import net.corda.core.contracts.*
import net.corda.core.contracts.ComponentGroupEnum.*
import net.corda.core.crypto.*
import net.corda.core.identity.Party
import net.corda.core.internal.Emoji
import net.corda.core.internal.VisibleForTesting
import net.corda.core.node.ServicesForResolution
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.SerializedBytes
import net.corda.core.serialization.deserialize
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.createComponentGroups
import java.security.PublicKey
import java.security.SignatureException
import java.util.function.Predicate

/**
 * A transaction ready for serialisation, without any signatures attached. A WireTransaction is usually wrapped
 * by a [SignedTransaction] that carries the signatures over this payload.
 * The identity of the transaction is the Merkle tree root of its components (see [MerkleTree]).
 */
@CordaSerializable
data class WireTransaction(val componentGroups: List<ComponentGroup>, override val privacySalt: PrivacySalt = PrivacySalt()) : CoreTransaction(), TraversableTransaction {

    @Deprecated("Required only in some unit-tests and for backwards compatibility purposes.", ReplaceWith("WireTransaction(val componentGroups: List<ComponentGroup>, override val privacySalt: PrivacySalt)"), DeprecationLevel.WARNING)
    constructor(inputs: List<StateRef>,
                attachments: List<SecureHash>,
                outputs: List<TransactionState<ContractState>>,
                commands: List<Command<*>>,
                notary: Party?,
                timeWindow: TimeWindow?,
                privacySalt: PrivacySalt = PrivacySalt()
    ) : this(createComponentGroups(inputs, outputs, commands, attachments, notary, timeWindow), privacySalt)

    /** Pointers to the input states on the ledger, identified by (tx identity hash, output index). */
    override val inputs: List<StateRef> by lazy { componentGroups[INPUTS_GROUP.ordinal].components.map { SerializedBytes<StateRef>(it.bytes).deserialize() } }

    override val outputs: List<TransactionState<ContractState>> by lazy { componentGroups[OUTPUTS_GROUP.ordinal].components.map { SerializedBytes<TransactionState<ContractState>>(it.bytes).deserialize() } }

    /** Ordered list of ([CommandData], [PublicKey]) pairs that instruct the contracts what to do. */
    override val commands: List<Command<*>> by lazy { componentGroups[COMMANDS_GROUP.ordinal].components.map { SerializedBytes<Command<*>>(it.bytes).deserialize() } }

    /** Hashes of the ZIP/JAR files that are needed to interpret the contents of this wire transaction. */
    override val attachments: List<SecureHash> by lazy { componentGroups[ATTACHMENTS_GROUP.ordinal].components.map { SerializedBytes<SecureHash>(it.bytes).deserialize() } }

    override val notary: Party? by lazy {
        val notaries: List<Party> = componentGroups[NOTARY_GROUP.ordinal].components.map { SerializedBytes<Party>(it.bytes).deserialize() }
        check(notaries.size <= 1) { "Invalid Transaction. More than 1 notary party detected." }
        if (notaries.isNotEmpty()) notaries[0] else null
    }
    override val timeWindow: TimeWindow? by lazy {
        val timeWindows: List<TimeWindow> = componentGroups[TIMEWINDOW_GROUP.ordinal].components.map { SerializedBytes<TimeWindow>(it.bytes).deserialize() }
        check(timeWindows.size <= 1) { "Invalid Transaction. More than 1 time-window detected." }
        if (timeWindows.isNotEmpty()) timeWindows[0] else null
    }

    init {
        checkAllFields()
        checkBaseInvariants()
        check(inputs.isNotEmpty() || outputs.isNotEmpty()) { "A transaction must contain at least one input or output state" }
        check(commands.isNotEmpty()) { "A transaction must contain at least one command" }
        if (timeWindow != null) check(notary != null) { "Transactions with time-windows must be notarised" }
    }

    // Check if after deserialisation all fields are casted to the correct type.
    private fun checkAllFields() {
        try {
            inputs; outputs; commands; attachments; notary; timeWindow
        } catch (cce: ClassCastException) {
            throw ClassCastException("Malformed WireTransaction, one of the components cannot be deserialised - ${cce.message}")
        }
    }

    /** The transaction id is represented by the root hash of Merkle tree over the transaction components. */
    override val id: SecureHash get() = merkleTree.hash

    /** Public keys that need to be fulfilled by signatures in order for the transaction to be valid. */
    val requiredSigningKeys: Set<PublicKey> get() {
        val commandKeys = commands.flatMap { it.signers }.toSet()
        // TODO: prevent notary field from being set if there are no inputs and no timestamp
        return if (notary != null && (inputs.isNotEmpty() || timeWindow != null)) {
            commandKeys + notary!!.owningKey
        } else {
            commandKeys
        }
    }

    /**
     * Looks up identities and attachments from storage to generate a [LedgerTransaction]. A transaction is expected to
     * have been fully resolved using the resolution flow by this point.
     *
     * @throws AttachmentResolutionException if a required attachment was not found in storage.
     * @throws TransactionResolutionException if an input points to a transaction not found in storage.
     */
    @Throws(AttachmentResolutionException::class, TransactionResolutionException::class)
    fun toLedgerTransaction(services: ServicesForResolution): LedgerTransaction {
        return toLedgerTransaction(
                resolveIdentity = { services.identityService.partyFromKey(it) },
                resolveAttachment = { services.attachments.openAttachment(it) },
                resolveStateRef = { services.loadState(it) }
        )
    }

    /**
     * Looks up identities, attachments and dependent input states using the provided lookup functions in order to
     * construct a [LedgerTransaction]. Note that identity lookup failure does *not* cause an exception to be thrown.
     *
     * @throws AttachmentResolutionException if a required attachment was not found using [resolveAttachment].
     * @throws TransactionResolutionException if an input was not found not using [resolveStateRef].
     */
    @Throws(AttachmentResolutionException::class, TransactionResolutionException::class)
    fun toLedgerTransaction(
            resolveIdentity: (PublicKey) -> Party?,
            resolveAttachment: (SecureHash) -> Attachment?,
            resolveStateRef: (StateRef) -> TransactionState<*>?
    ): LedgerTransaction {
        // Look up public keys to authenticated identities. This is just a stub placeholder and will all change in future.
        val authenticatedArgs = commands.map {
            val parties = it.signers.mapNotNull { pk -> resolveIdentity(pk) }
            AuthenticatedObject(it.signers, parties, it.value)
        }
        // Open attachments specified in this transaction. If we haven't downloaded them, we fail.
        val attachments = attachments.map { resolveAttachment(it) ?: throw AttachmentResolutionException(it) }
        val resolvedInputs = inputs.map { ref ->
            resolveStateRef(ref)?.let { StateAndRef(it, ref) } ?: throw TransactionResolutionException(ref.txhash)
        }
        return LedgerTransaction(resolvedInputs, outputs, authenticatedArgs, attachments, id, notary, timeWindow, privacySalt)
    }

    /**
     * Build filtered transaction using provided filtering functions.
     */
    fun buildFilteredTransaction(filtering: Predicate<Any>): FilteredTransaction {
        return FilteredTransaction.buildMerkleTransaction(this, filtering)
    }

    /**
     * Builds whole Merkle tree for a transaction.
     */
    val merkleTree: MerkleTree by lazy { MerkleTree.getMerkleTree(listOf(privacySalt.sha256()) + groupsMerkleRoots) }

    /**
     * Calculate the hashes of the sub-components of the transaction, that are used to build its Merkle tree.
     * The root of the tree is the transaction identifier. The tree structure is helpful for privacy, please
     * see the user-guide section "Transaction tear-offs" to learn more about this topic.
     */
    @VisibleForTesting
    val groupsMerkleRoots: List<SecureHash> get() = componentGroups.mapIndexed { index, it ->
        if (it.components.isNotEmpty()) {
            MerkleTree.getMerkleTree(it.components.mapIndexed { indexInternal, itInternal ->
                serializedHash(itInternal, privacySalt, index, indexInternal) }).hash
        } else {
            SecureHash.zeroHash
        }
    }

    /**
     * Construction of partial transaction from WireTransaction based on filtering.
     * Note that list of nonces to be sent is updated on the fly, based on the index of the filtered tx component.
     * @param filtering filtering over the whole WireTransaction
     * @returns FilteredLeaves used in PartialMerkleTree calculation and verification.
     */
    fun filterWithFun(filtering: Predicate<Any>): FilteredLeaves {
        val nonces: MutableList<SecureHash> = mutableListOf()
        val offsets = indexOffsets()
        fun notNullFalseAndNoncesUpdate(elem: Any?, index: Int): Any? {
            return if (elem == null || !filtering.test(elem)) {
                null
            } else {
                nonces.add(computeNonce(privacySalt, index))
                elem
            }
        }

        fun <T : Any> filterAndNoncesUpdate(t: T, index: Int): Boolean {
            return if (filtering.test(t)) {
                nonces.add(computeNonce(privacySalt, index))
                true
            } else {
                false
            }
        }

        // TODO: We should have a warning (require) if all leaves (excluding salt) are visible after filtering.
        //      Consider the above after refactoring FilteredTransaction to implement TraversableTransaction,
        //      so that a WireTransaction can be used when required to send a full tx (e.g. RatesFixFlow in Oracles).
        return FilteredLeaves(
                inputs.filterIndexed { index, it -> filterAndNoncesUpdate(it, index) },
                attachments.filterIndexed { index, it -> filterAndNoncesUpdate(it, index + offsets[0]) },
                outputs.filterIndexed { index, it -> filterAndNoncesUpdate(it, index + offsets[1]) },
                commands.filterIndexed { index, it -> filterAndNoncesUpdate(it, index + offsets[2]) },
                notNullFalseAndNoncesUpdate(notary, offsets[3]) as Party?,
                notNullFalseAndNoncesUpdate(timeWindow, offsets[4]) as TimeWindow?,
                nonces
        )
    }

    // We use index offsets, to get the actual leaf-index per transaction component required for nonce computation.
    private fun indexOffsets(): List<Int> {
        // There is no need to add an index offset for inputs, because they are the first components in the
        // transaction format and it is always zero. Thus, offsets[0] corresponds to attachments,
        // offsets[1] to outputs, offsets[2] to commands and so on.
        val offsets = mutableListOf(inputs.size, inputs.size + attachments.size)
        offsets.add(offsets.last() + outputs.size)
        offsets.add(offsets.last() + commands.size)
        if (notary != null) {
            offsets.add(offsets.last() + 1)
        } else {
            offsets.add(offsets.last())
        }
        if (timeWindow != null) {
            offsets.add(offsets.last() + 1)
        } else {
            offsets.add(offsets.last())
        }
        // No need to add offset for privacySalt as it doesn't require a nonce.
        return offsets
    }

    /**
     * Checks that the given signature matches one of the commands and that it is a correct signature over the tx.
     *
     * @throws SignatureException if the signature didn't match the transaction contents.
     * @throws IllegalArgumentException if the signature key doesn't appear in any command.
     */
    fun checkSignature(sig: TransactionSignature) {
        require(commands.any { it.signers.any { sig.by in it.keys } }) { "Signature key doesn't match any command" }
        sig.verify(id)
    }

    override fun toString(): String {
        val buf = StringBuilder()
        buf.appendln("Transaction:")
        for (input in inputs) buf.appendln("${Emoji.rightArrow}INPUT:      $input")
        for ((data) in outputs) buf.appendln("${Emoji.leftArrow}OUTPUT:     $data")
        for (command in commands) buf.appendln("${Emoji.diamond}COMMAND:    $command")
        for (attachment in attachments) buf.appendln("${Emoji.paperclip}ATTACHMENT: $attachment")
        return buf.toString()
    }
}

/**
 * A ComponentGroup is used to store the full list of transaction components of the same type in serialised form.
 * Practically, a group per component type of a transaction is required; thus, there will be a group for input states,
 * a group for all attachments (if there are any) etc.
 */
@CordaSerializable
data class ComponentGroup(val components: List<OpaqueBytes>)
