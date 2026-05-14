package kolt.config

import kolt.resolve.AuthStateProjection
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// Typed projection over Bearer / Basic credentials. `toString` is overridden
// on every variant so that incidental stringification (eprintln, error
// formatting, "$repo" interpolation) cannot leak secrets. `equals`/`hashCode`
// remain data-class defaults intentionally — two `Bearer("X")` values are
// equal, which is the right behavior; the leak surface is `toString` only.
sealed class RepositoryAuth {
  data class Bearer(val token: String) : RepositoryAuth() {
    override fun toString(): String = "RepositoryAuth.Bearer(token=<redacted>)"
  }

  data class Basic(val user: String, val password: String) : RepositoryAuth() {
    override fun toString(): String = "RepositoryAuth.Basic(user=$user, password=<redacted>)"
  }
}

@OptIn(ExperimentalEncodingApi::class)
fun RepositoryAuth.toHeaders(): Map<String, String> =
  when (this) {
    is RepositoryAuth.Bearer -> mapOf("Authorization" to "Bearer $token")
    is RepositoryAuth.Basic ->
      mapOf("Authorization" to "Basic ${Base64.encode("$user:$password".encodeToByteArray())}")
  }

fun RepositoryAuth?.toStateProjection(): AuthStateProjection =
  when (this) {
    null -> AuthStateProjection.NotConfigured
    is RepositoryAuth.Bearer -> AuthStateProjection.ConfiguredToken
    is RepositoryAuth.Basic -> AuthStateProjection.ConfiguredBasic
  }
