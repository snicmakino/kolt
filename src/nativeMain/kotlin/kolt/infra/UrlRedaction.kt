package kolt.infra

private const val SCHEME_SEP = "://"

internal fun redactUrlUserinfo(url: String): String {
  val schemeEnd = url.indexOf(SCHEME_SEP)
  if (schemeEnd < 0) return url
  val authorityStart = schemeEnd + SCHEME_SEP.length

  // `@` inside the path (e.g. `https://host/foo@bar`) is intentionally preserved:
  // userinfo only exists between `://` and the first path/query/fragment separator.
  var at = -1
  for (i in authorityStart until url.length) {
    val c = url[i]
    if (c == '/' || c == '?' || c == '#') break
    if (c == '@') {
      at = i
      break
    }
  }
  if (at < 0) return url

  return url.substring(0, authorityStart) + url.substring(at + 1)
}
