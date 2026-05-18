package kolt.selfupdate

sealed interface SelfUpdateError {
  data class Network(val url: String, val detail: String) : SelfUpdateError

  data class Metadata(val detail: String) : SelfUpdateError

  data class Asset(val name: String, val detail: String) : SelfUpdateError

  data class Extract(val detail: String) : SelfUpdateError

  data class Layout(val detectedPath: String, val detail: String) : SelfUpdateError

  data class Platform(val sysname: String, val machine: String) : SelfUpdateError

  data class Home(val detail: String) : SelfUpdateError
}
