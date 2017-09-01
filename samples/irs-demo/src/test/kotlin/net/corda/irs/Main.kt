package net.corda.irs

import net.corda.core.node.services.ServiceInfo
import net.corda.core.utilities.getOrThrow
import net.corda.testing.DUMMY_BANK_A
import net.corda.testing.DUMMY_BANK_B
import net.corda.testing.DUMMY_NOTARY
import net.corda.irs.api.NodeInterestRates
import net.corda.node.services.transactions.SimpleNotaryService
import net.corda.testing.driver.driver
import java.util.concurrent.CompletableFuture.allOf

/**
 * This file is exclusively for being able to run your nodes through an IDE (as opposed to running deployNodes)
 * Do not use in a production environment.
 */
fun main(args: Array<String>) {
    driver(dsl = {
        val controllerFuture = startNode(DUMMY_NOTARY.name, setOf(ServiceInfo(SimpleNotaryService.type), ServiceInfo(NodeInterestRates.Oracle.type)))
                .toCompletableFuture()
        val nodeAFuture = startNode(DUMMY_BANK_A.name).toCompletableFuture()
        val nodeBFuture = startNode(DUMMY_BANK_B.name).toCompletableFuture()
        allOf(controllerFuture, nodeAFuture, nodeBFuture).getOrThrow()

        val (controller, nodeA, nodeB) = listOf(controllerFuture, nodeAFuture, nodeBFuture).map { it.getOrThrow() }

        startWebserver(controller)
        startWebserver(nodeA)
        startWebserver(nodeB)

        waitForAllNodesToFinish()
    }, useTestClock = true, isDebug = true)
}
