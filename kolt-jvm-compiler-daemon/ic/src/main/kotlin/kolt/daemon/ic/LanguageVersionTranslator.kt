package kolt.daemon.ic

// kotlinc rejects patch-level values: `-api-version 2.1.0` →
// `Unknown -api-version value: 2.1.0`. Language/API surface is addressed by
// `major.minor` only.
object LanguageVersionTranslator {

  fun translate(version: String?, compiler: String?): List<String> {
    if (version == null || compiler == null || version == compiler) return emptyList()
    val surface = version.split('.').take(2).joinToString(".")
    return listOf("-language-version", surface, "-api-version", surface)
  }
}
