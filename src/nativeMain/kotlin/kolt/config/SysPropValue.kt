package kolt.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// Three-variant sum type for entries inside `[test.sys_props]` and `[run.sys_props]`.
// TOML schema is uniform inline-table: each entry is `{ literal = "..." }`,
// `{ classpath = "..." }`, or `{ project_dir = "..." }`. The polymorphic
// "string OR inline-table" alternative was rejected after probing ktoml 0.7.1
// (would require reaching into TomlNode internals from a custom serializer);
// see ADR/spec for the decision and Req 2.1 amendment.
@Serializable(with = SysPropValueSerializer::class)
sealed class SysPropValue {
  data class Literal(val value: String) : SysPropValue()

  data class ClasspathRef(val bundleName: String) : SysPropValue()

  data class ProjectDir(val relativePath: String) : SysPropValue()
}

@Serializable
internal data class RawSysPropValue(
  val literal: String? = null,
  val classpath: String? = null,
  @SerialName("project_dir") val projectDir: String? = null,
)

internal object SysPropValueSerializer : KSerializer<SysPropValue> {
  override val descriptor: SerialDescriptor = RawSysPropValue.serializer().descriptor

  override fun serialize(encoder: Encoder, value: SysPropValue) {
    val raw =
      when (value) {
        is SysPropValue.Literal -> RawSysPropValue(literal = value.value)
        is SysPropValue.ClasspathRef -> RawSysPropValue(classpath = value.bundleName)
        is SysPropValue.ProjectDir -> RawSysPropValue(projectDir = value.relativePath)
      }
    encoder.encodeSerializableValue(RawSysPropValue.serializer(), raw)
  }

  override fun deserialize(decoder: Decoder): SysPropValue {
    val raw = decoder.decodeSerializableValue(RawSysPropValue.serializer())
    val setFields =
      listOfNotNull(
        raw.literal?.let { "literal" },
        raw.classpath?.let { "classpath" },
        raw.projectDir?.let { "project_dir" },
      )
    if (setFields.size != 1) {
      throw SerializationException(
        "sys_props value must set exactly one of { literal, classpath, project_dir }, " +
          "but ${setFields.size} were set: $setFields"
      )
    }
    return when (setFields.single()) {
      "literal" -> SysPropValue.Literal(raw.literal!!)
      "classpath" -> SysPropValue.ClasspathRef(raw.classpath!!)
      "project_dir" -> SysPropValue.ProjectDir(raw.projectDir!!)
      else -> error("unreachable")
    }
  }
}
