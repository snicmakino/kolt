package kolt.selfupdate

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.infra.DownloadError
import kolt.infra.readFileAsString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// GitHub's API replies 403 to requests without a User-Agent, so the UA is a
// hard requirement of the contract rather than a courtesy header.
const val GITHUB_RELEASES_LATEST_URL =
  "https://api.github.com/repos/snicmakino/kolt/releases/latest"

// `downloadFile` is a top-level libcurl function; this fun interface is the
// kolt seam idiom (cf. ResolverDeps / PluginFetcherDeps) so tests pass the
// real `::downloadFile` against a loopback server and production wires the
// same function by default.
fun interface Downloader {
  fun downloadFile(
    url: String,
    destPath: String,
    headers: Map<String, String>?,
  ): Result<Unit, DownloadError>
}

class GithubReleasesClient(
  private val downloader: Downloader,
  private val userAgent: String,
  private val tempPathFactory: () -> String,
) {
  // Endpoint defaults to the canonical GitHub URL (API Contract) but stays
  // overridable so loopback-server tests can drive the same code path.
  fun fetchLatest(
    url: String = GITHUB_RELEASES_LATEST_URL
  ): Result<LatestRelease, SelfUpdateError> {
    val tempPath = tempPathFactory()
    val downloaded = downloader.downloadFile(url, tempPath, mapOf("User-Agent" to userAgent))
    downloaded.getError()?.let {
      return Err(it.toSelfUpdateError())
    }

    val body =
      readFileAsString(tempPath).getOrElse {
        return Err(SelfUpdateError.Metadata("could not read downloaded releases metadata"))
      }

    return try {
      Ok(lenientJson.decodeFromString<LatestRelease>(body))
    } catch (e: Exception) {
      Err(SelfUpdateError.Metadata("releases/latest body was not valid JSON: ${e.message}"))
    }
  }

  fun validateTag(tagName: String): Result<String, SelfUpdateError> {
    val match = SEMVER_TAG.matchEntire(tagName)
    return if (match == null) {
      Err(SelfUpdateError.Metadata("tag '$tagName' is not a vX.Y.Z stable release tag"))
    } else {
      val (major, minor, patch) = match.destructured
      Ok("$major.$minor.$patch")
    }
  }

  // Resolves a named release asset's download URL; a missing asset is a
  // named SelfUpdateError.Asset so the caller can report which artifact is absent.
  fun assetUrl(release: LatestRelease, name: String): Result<String, SelfUpdateError> {
    val asset = release.assetByName(name)
    return if (asset == null) {
      Err(SelfUpdateError.Asset(name, "asset not published in releases/latest"))
    } else {
      Ok(asset.browserDownloadUrl)
    }
  }

  private fun DownloadError.toSelfUpdateError(): SelfUpdateError =
    when (this) {
      // Connect/timeout/transport failures are network-domain.
      is DownloadError.NetworkError -> SelfUpdateError.Network(url, message)
      is DownloadError.WriteFailed -> SelfUpdateError.Metadata("could not write metadata to $path")
      is DownloadError.HttpFailed ->
        // 5xx (and transport-ish) are network; 4xx are server-side
        // metadata problems (404 = no release, 403 = UA rejected).
        if (statusCode >= 500) {
          SelfUpdateError.Network(url, "HTTP $statusCode from $url")
        } else {
          SelfUpdateError.Metadata("releases/latest returned HTTP $statusCode")
        }
    }

  private companion object {
    val SEMVER_TAG = Regex("""^v(\d+)\.(\d+)\.(\d+)$""")
    val lenientJson = Json { ignoreUnknownKeys = true }
  }
}

@Serializable
data class LatestRelease(
  @SerialName("tag_name") val tagName: String,
  val assets: List<ReleaseAsset>,
) {
  fun assetByName(name: String): ReleaseAsset? = assets.firstOrNull { it.name == name }
}

@Serializable
data class ReleaseAsset(
  val name: String,
  @SerialName("browser_download_url") val browserDownloadUrl: String,
)
