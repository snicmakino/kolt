package kolt.build

enum class Profile(val dirName: String) {
  Debug(dirName = "debug"),
  Release(dirName = "release"),
}
