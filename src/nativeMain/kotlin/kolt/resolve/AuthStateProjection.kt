package kolt.resolve

// Display-time projection over `RepositoryAuth` that carries no secret state.
// Lives in `kolt.resolve` so the renderer can import it without pulling in
// the secret-carrying `RepositoryAuth` type from `kolt.config`. The
// "from kolt.local.toml" suffix is hardcoded because Requirement 2.1 rejects
// credential literals from kolt.toml at parse time, so any configured
// credential that reaches the resolver provably originated in kolt.local.toml.
sealed class AuthStateProjection {
  data object NotConfigured : AuthStateProjection()

  data object ConfiguredToken : AuthStateProjection()

  data object ConfiguredBasic : AuthStateProjection()

  fun toDisplayString(): String =
    when (this) {
      NotConfigured -> "not configured"
      ConfiguredToken -> "configured (token, from kolt.local.toml)"
      ConfiguredBasic -> "configured (basic, from kolt.local.toml)"
    }
}
