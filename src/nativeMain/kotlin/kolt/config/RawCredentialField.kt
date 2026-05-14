package kolt.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = RawCredentialFieldSerializer::class)
internal sealed class RawCredentialField {
  data class Literal(val value: String) : RawCredentialField() {
    override fun toString(): String = "RawCredentialField.Literal(<redacted>)"
  }

  data class Env(val varName: String) : RawCredentialField() {
    override fun toString(): String = "RawCredentialField.Env(varName=$varName)"
  }
}

@Serializable
internal data class RawCredentialFieldShape(val literal: String? = null, val env: String? = null)

internal object RawCredentialFieldSerializer : KSerializer<RawCredentialField> {
  override val descriptor: SerialDescriptor = RawCredentialFieldShape.serializer().descriptor

  override fun serialize(encoder: Encoder, value: RawCredentialField) {
    val raw =
      when (value) {
        is RawCredentialField.Literal -> RawCredentialFieldShape(literal = value.value)
        is RawCredentialField.Env -> RawCredentialFieldShape(env = value.varName)
      }
    encoder.encodeSerializableValue(RawCredentialFieldShape.serializer(), raw)
  }

  override fun deserialize(decoder: Decoder): RawCredentialField {
    val raw = decoder.decodeSerializableValue(RawCredentialFieldShape.serializer())
    val setFields = listOfNotNull(raw.literal?.let { "literal" }, raw.env?.let { "env" })
    if (setFields.size != 1) {
      throw SerializationException(
        "credential field value must set exactly one of { literal, env }, " +
          "but ${setFields.size} were set: $setFields"
      )
    }
    return when (setFields.single()) {
      "literal" -> RawCredentialField.Literal(raw.literal!!)
      "env" -> RawCredentialField.Env(raw.env!!)
      else -> error("unreachable")
    }
  }
}
