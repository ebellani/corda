package net.corda.node.services.schema

import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.AbstractParty
import net.corda.core.node.services.Vault
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import net.corda.testing.LogHelper
import net.corda.node.services.api.SchemaService
import net.corda.node.utilities.DatabaseTransactionManager
import net.corda.node.utilities.configureDatabase
import net.corda.testing.MEGA_CORP
import net.corda.testing.node.MockServices.Companion.makeTestDataSourceProperties
import net.corda.testing.node.MockServices.Companion.makeTestDatabaseProperties
import net.corda.testing.node.MockServices.Companion.makeTestIdentityService
import org.hibernate.annotations.Cascade
import org.hibernate.annotations.CascadeType
import org.junit.After
import org.junit.Before
import org.junit.Test
import rx.subjects.PublishSubject
import javax.persistence.*
import kotlin.test.assertEquals


class HibernateObserverTests {

    @Before
    fun setUp() {
        LogHelper.setLevel(HibernateObserver::class)
    }

    @After
    fun cleanUp() {
        LogHelper.reset(HibernateObserver::class)
    }

    class SchemaFamily

    @Entity
    @Table(name = "Parents")
    class Parent : PersistentState() {
        @OneToMany(fetch = FetchType.LAZY)
        @JoinColumns(JoinColumn(name = "transaction_id", referencedColumnName = "transaction_id"), JoinColumn(name = "output_index", referencedColumnName = "output_index"))
        @OrderColumn
        @Cascade(CascadeType.PERSIST)
        var children: MutableSet<Child> = mutableSetOf()
    }

    @Suppress("unused")
    @Entity
    @Table(name = "Children")
    class Child {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "child_id", unique = true, nullable = false)
        var childId: Int? = null

        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumns(JoinColumn(name = "transaction_id", referencedColumnName = "transaction_id"), JoinColumn(name = "output_index", referencedColumnName = "output_index"))
        var parent: Parent? = null
    }

    class TestState : QueryableState {
        override fun supportedSchemas(): Iterable<MappedSchema> {
            throw UnsupportedOperationException()
        }

        override fun generateMappedObject(schema: MappedSchema): PersistentState {
            throw UnsupportedOperationException()
        }

        override val contract: String
            get() = throw UnsupportedOperationException()

        override val participants: List<AbstractParty>
            get() = throw UnsupportedOperationException()
    }

    // This method does not use back quotes for a nice name since it seems to kill the kotlin compiler.
    @Test
    fun testChildObjectsArePersisted() {
        val testSchema = object : MappedSchema(SchemaFamily::class.java, 1, setOf(Parent::class.java, Child::class.java)) {}
        val rawUpdatesPublisher = PublishSubject.create<Vault.Update<ContractState>>()
        val schemaService = object : SchemaService {
            override val schemaOptions: Map<MappedSchema, SchemaService.SchemaOptions> = emptyMap()

            override fun selectSchemas(state: ContractState): Iterable<MappedSchema> = setOf(testSchema)

            override fun generateMappedObject(state: ContractState, schema: MappedSchema): PersistentState {
                val parent = Parent()
                parent.children.add(Child())
                parent.children.add(Child())
                return parent
            }
        }
        val database = configureDatabase(makeTestDataSourceProperties(), makeTestDatabaseProperties(), { schemaService }, createIdentityService = ::makeTestIdentityService)

        @Suppress("UNUSED_VARIABLE")
        val observer = HibernateObserver(rawUpdatesPublisher, database.hibernateConfig)
        database.transaction {
            rawUpdatesPublisher.onNext(Vault.Update(emptySet(), setOf(StateAndRef(TransactionState(TestState(), MEGA_CORP), StateRef(SecureHash.sha256("dummy"), 0)))))
            val parentRowCountResult = DatabaseTransactionManager.current().connection.prepareStatement("select count(*) from Parents").executeQuery()
            parentRowCountResult.next()
            val parentRows = parentRowCountResult.getInt(1)
            parentRowCountResult.close()
            val childrenRowCountResult = DatabaseTransactionManager.current().connection.prepareStatement("select count(*) from Children").executeQuery()
            childrenRowCountResult.next()
            val childrenRows = childrenRowCountResult.getInt(1)
            childrenRowCountResult.close()
            assertEquals(1, parentRows, "Expected one parent")
            assertEquals(2, childrenRows, "Expected two children")
        }

        database.close()
    }
}