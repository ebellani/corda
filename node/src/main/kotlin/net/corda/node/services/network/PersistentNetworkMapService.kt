package net.corda.node.services.network

import net.corda.core.crypto.toBase58String
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.internal.ThreadBox
import net.corda.core.messaging.SingleMessageRecipient
import net.corda.core.serialization.SerializationDefaults
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.node.services.api.ServiceHubInternal
import net.corda.node.utilities.*
import net.corda.nodeapi.ArtemisMessagingComponent
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import javax.persistence.*
import java.util.*

/**
 * A network map service backed by a database to survive restarts of the node hosting it.
 *
 * Majority of the logic is inherited from [AbstractNetworkMapService].
 *
 * This class needs database transactions to be in-flight during method calls and init, otherwise it will throw
 * exceptions.
 */
class PersistentNetworkMapService(services: ServiceHubInternal, minimumPlatformVersion: Int)
    : AbstractNetworkMapService(services, minimumPlatformVersion) {

    // Only the node_party_path column is needed to reconstruct a PartyAndCertificate but we have the others for human readability
    @Entity
    @Table(name = "${NODE_DATABASE_PREFIX}network_map_nodes")
    class NetworkNode(
            @Id @Column(name = "node_party_key")
            var publicKey: String = "",

            @Column(name = "node_party_name")
            var name: String = "",

            @Lob @Column(name = "node_party_certificate")
            var certificate: ByteArray = ByteArray(0),

            @Lob @Column(name = "node_party_path")
            var certPath: ByteArray = ByteArray(0),

            @Lob @Column
            var registrationInfo: ByteArray = ByteArray(0)
    )

    private companion object {
        private val factory = CertificateFactory.getInstance("X.509")

        fun createNetworkNodesMap(): PersistentMap<PartyAndCertificate, NodeRegistrationInfo, NetworkNode, String> {
            return PersistentMap(
                    toPersistentEntityKey = { it.owningKey.toBase58String() },
                    fromPersistentEntity = {
                        Pair(PartyAndCertificate(factory.generateCertPath(ByteArrayInputStream(it.certPath))),
                                it.registrationInfo.deserialize(context = SerializationDefaults.STORAGE_CONTEXT))
                    },
                    toPersistentEntity = { key: PartyAndCertificate, value: NodeRegistrationInfo ->
                        NetworkNode(
                                publicKey = key.owningKey.toBase58String(),
                                name =  key.name.toString(),
                                certificate = key.certificate.encoded,
                                certPath = key.certPath.encoded,
                                registrationInfo = value.serialize(context = SerializationDefaults.STORAGE_CONTEXT).bytes
                        )
                    },
                    persistentEntityClass = NetworkNode::class.java
            )
        }

        fun createNetworkSubscribersMap(): PersistentMap<SingleMessageRecipient, LastAcknowledgeInfo, NetworkSubscriber, String> {
            return PersistentMap(
                    toPersistentEntityKey = { it.getPrimaryKeyBasedOnSubType() },
                    fromPersistentEntity = {
                        Pair(it.key.deserialize(context = SerializationDefaults.STORAGE_CONTEXT),
                                it.value.deserialize(context = SerializationDefaults.STORAGE_CONTEXT))
                    },
                    toPersistentEntity = { k: SingleMessageRecipient, v: LastAcknowledgeInfo ->
                        NetworkSubscriber(
                                id = k.getPrimaryKeyBasedOnSubType(),
                                key = k.serialize(context = SerializationDefaults.STORAGE_CONTEXT).bytes,
                                value = v.serialize(context = SerializationDefaults.STORAGE_CONTEXT).bytes
                        )
                    },
                    persistentEntityClass = NetworkSubscriber::class.java
            )
        }

        fun SingleMessageRecipient.getPrimaryKeyBasedOnSubType() =
                if (this is ArtemisMessagingComponent.ArtemisPeerAddress) {
                    this.hostAndPort.toString()
                } else {
                    this.toString()
                }
    }

    override val nodeRegistrations: MutableMap<PartyAndCertificate, NodeRegistrationInfo> =
            Collections.synchronizedMap(createNetworkNodesMap())

    @Entity
    @Table(name = "${NODE_DATABASE_PREFIX}network_map_subscribers")
    class NetworkSubscriber(
            @Id @Column
            var id: String = "",

            @Lob @Column
            var key: ByteArray = ByteArray(0),

            @Lob @Column
            var value: ByteArray = ByteArray(0)
    )

    override val subscribers = ThreadBox(createNetworkSubscribersMap())

    init {
        // Initialise the network map version with the current highest persisted version, or zero if there are no entries.
        _mapVersion.set(nodeRegistrations.values.map { it.mapVersion }.max() ?: 0)
        setup()
    }
}
