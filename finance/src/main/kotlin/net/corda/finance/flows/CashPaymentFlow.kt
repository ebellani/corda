package net.corda.finance.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Amount
import net.corda.core.contracts.InsufficientBalanceException
import net.corda.core.flows.StartableByRPC
import net.corda.core.flows.TransactionKeyFlow
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.finance.contracts.asset.Cash
import java.util.*

/**
 * Initiates a flow that sends cash to a recipient.
 *
 * @param amount the amount of a currency to pay to the recipient.
 * @param recipient the party to pay the currency to.
 * @param issuerConstraint if specified, the payment will be made using only cash issued by the given parties.
 */
@StartableByRPC
open class CashPaymentFlow(
        val amount: Amount<Currency>,
        val recipient: Party,
        val anonymous: Boolean,
        progressTracker: ProgressTracker,
        val issuerConstraint: Set<Party> = emptySet()) : AbstractCashFlow<AbstractCashFlow.Result>(progressTracker) {
    /** A straightforward constructor that constructs spends using cash states of any issuer. */
    constructor(amount: Amount<Currency>, recipient: Party) : this(amount, recipient, true, tracker())
    /** A straightforward constructor that constructs spends using cash states of any issuer. */
    constructor(amount: Amount<Currency>, recipient: Party, anonymous: Boolean) : this(amount, recipient, anonymous, tracker())
    constructor(request: PaymentRequest) : this(request.amount, request.recipient, request.anonymous, tracker(), request.issuerConstraint)

    @Suspendable
    override fun call(): AbstractCashFlow.Result {
        progressTracker.currentStep = GENERATING_ID
        val txIdentities = if (anonymous) {
            subFlow(TransactionKeyFlow(recipient))
        } else {
            emptyMap<Party, AnonymousParty>()
        }
        val anonymousRecipient = txIdentities[recipient] ?: recipient
        progressTracker.currentStep = GENERATING_TX
        val builder = TransactionBuilder(null as Party?)
        // TODO: Have some way of restricting this to states the caller controls
        val (spendTX, keysForSigning) = try {
            Cash.generateSpend(serviceHub,
                    builder,
                    amount,
                    anonymousRecipient,
                    issuerConstraint)
        } catch (e: InsufficientBalanceException) {
            throw CashException("Insufficient cash for spend: ${e.message}", e)
        }

        progressTracker.currentStep = SIGNING_TX
        val tx = serviceHub.signInitialTransaction(spendTX, keysForSigning)

        progressTracker.currentStep = FINALISING_TX
        finaliseTx(setOf(recipient), tx, "Unable to notarise spend")
        return Result(tx, anonymousRecipient)
    }

    @CordaSerializable
    class PaymentRequest(amount: Amount<Currency>, val recipient: Party, val anonymous: Boolean, val issuerConstraint: Set<Party> = emptySet()) : AbstractRequest(amount)
}