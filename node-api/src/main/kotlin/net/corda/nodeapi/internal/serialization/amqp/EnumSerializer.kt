package net.corda.nodeapi.internal.serialization.amqp

import org.apache.qpid.proton.codec.Data
import java.lang.reflect.Type
import java.io.NotSerializableException

/**
 * Our definition of an enum with the AMQP spec is a list (of two items, a string and an int) that is
 * a restricted type with a number of choices associated with it
 */
class EnumSerializer(declaredType: Type, declaredClass: Class<*>, factory: SerializerFactory) : AMQPSerializer<Any> {
    override val type: Type = declaredType
    override val typeDescriptor = "$DESCRIPTOR_DOMAIN:${fingerprintForType(type, factory)}"
    private val typeNotation: TypeNotation

    init {
        typeNotation = RestrictedType(
                SerializerFactory.nameForType(declaredType),
                null, emptyList(), "list", Descriptor(typeDescriptor, null),
                declaredClass.enumConstants.zip(IntRange(0, declaredClass.enumConstants.size)).map {
                    Choice(it.first.toString(), it.second.toString())
                })
    }

    override fun writeClassInfo(output: SerializationOutput) {
        output.writeTypeNotations(typeNotation)
    }

    override fun readObject(obj: Any, schema: Schema, input: DeserializationInput): Any {
        val enumName = (obj as List<*>)[0] as String
        val enumOrd = obj[1] as Int
        val fromOrd = type.asClass()!!.enumConstants[enumOrd]

        if (enumName != fromOrd?.toString()) {
            throw NotSerializableException("Deserializing obj as enum $type with value $enumName.$enumOrd but "
                    + "ordinality has changed")
        }
        return fromOrd
    }

    override fun writeObject(obj: Any, data: Data, type: Type, output: SerializationOutput) {
        if (obj !is Enum<*>) throw NotSerializableException("Serializing $obj as enum when it isn't")

        data.withDescribed(typeNotation.descriptor) {
            withList {
                data.putString(obj.name)
                data.putInt(obj.ordinal)
            }
        }
    }
}