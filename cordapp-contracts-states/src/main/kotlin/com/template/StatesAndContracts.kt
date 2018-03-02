package com.template

import net.corda.core.contracts.*
import net.corda.core.crypto.NullKeys
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.transactions.LedgerTransaction
import net.corda.finance.utils.sumCashBy
import java.time.Instant
import java.util.*

// *****************
// * Contract Code *
// *****************
// This is used to identify our contract when building a transaction
val IOU_CONTRACT_ID = "com.template.IOUContract"

open class IOUContract : Contract {
//    class Create: CommandData

    // A transaction is considered valid if the verify() function of the contract of each of the transaction's input
    // and output states does not throw an exception.
    override fun verify(tx: LedgerTransaction) {
        // Verification logic goes here.
        val command = tx.commands.requireSingleCommand<Commands.Create>()

        requireThat {
            "No inputs should be consumed with issuing an IOU." using (tx.inputs.isEmpty())
            "There should be one output state of type IOUState." using (tx.outputs.size == 1)

            val out = tx.outputsOfType<IOUState>().single()
            "The IOU's value must be non-negative." using (out.value > 0)
            "The lender and the borrower cannot be the same entity." using (out.lender != out.borrower)

            "There must be two signers." using (command.signers.toSet().size == 2)
            "The borrower and lender must be signers." using (command.signers.containsAll(listOf(out.borrower.owningKey, out.lender.owningKey)))
        }
    }

    // Used to indicate the transaction's intent.
    interface Commands : CommandData {
        class Create: CommandData
    }
}

// *********
// * State *
// *********
data class IOUState(val value: Int,
                    val lender: Party,
                    val borrower: Party) : ContractState {
    override val participants get() = listOf(lender, borrower)
}


class CommercialPaper : Contract {
    override fun verify(tx: LedgerTransaction) {
        val groups = tx.groupStates(State::withoutOwner)

        val command = tx.commands.requireSingleCommand<CommercialPaper.Commands>()

        val timeWindow: TimeWindow? = tx.timeWindow

        for ((inputs, outputs, _) in groups) {
            when (command.value) {
                is Commands.Move -> {
                    val input = inputs.single()
                    requireThat {
                        "the transaction is signed by the owner of the CP" using (input.owner.owningKey in command.signers)
                        "the state is propagated" using (outputs.size == 1)
                    }
                }
                is Commands.Redeem -> {
                    val input = inputs.single()
                    val received = tx.outputs.map { it.data }.sumCashBy(input.owner)
                    val time = timeWindow?.fromTime ?: throw IllegalArgumentException("Redemptions must be timestamped")
                    requireThat {
                        "the paper must have matured" using (time >= input.maturityDate)
                        "the received amount equals the face value" using (received == input.faceValue)
                        "the paper must be destroyed" using outputs.isEmpty()
                        "the transaction is signed by the owner of the CP" using (input.owner.owningKey in command.signers)
                    }
                }
                is Commands.Issue -> {
                    val output = outputs.single()
                    val time = timeWindow?.untilTime ?: throw IllegalArgumentException("Issuance must be timestamped")
                    requireThat {
                        "output states are issued by a command signer" using (output.issuance.party.owningKey in command.signers)
                        "output values sum to more tha the inputs" using (output.faceValue.quantity > 0)
                        "the maturity date is not in the past" using (time < output.maturityDate)
                        "can't reissue an existing state" using inputs.isEmpty()
                    }
                }

                else -> throw IllegalArgumentException("Unrecognised command")
            }
        }
    }

    interface Commands : CommandData {
        class Move: TypeOnlyCommandData(), Commands
        class Redeem: TypeOnlyCommandData(), Commands
        class Issue: TypeOnlyCommandData(), Commands
    }
}

data class State(
        val issuance: PartyAndReference,
        override val owner: AbstractParty,
        val faceValue: Amount<Issued<Currency>>,
        val maturityDate: Instant
) : OwnableState {
    override val participants = listOf(owner)

    fun withoutOwner() = copy(owner = AnonymousParty(NullKeys.NullPublicKey))
    override fun withNewOwner(newOwner: AbstractParty) =  CommandAndState(CommercialPaper.Commands.Move(), copy(owner = newOwner))
}
