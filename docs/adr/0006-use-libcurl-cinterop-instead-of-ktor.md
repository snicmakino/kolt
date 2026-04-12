# ADR 0006: Use libcurl cinterop instead of Ktor Client

## Status

Accepted (2026-04-09)

## Context

keel uses HTTP to download JAR files and POM metadata from Maven Central.
The original implementation used Ktor Client with the Curl engine (`ktor-client-curl`).

Ktor brought significant transitive dependencies into the build:

- **14 Kotlin/Native klib modules** (ktor-client-core, ktor-http, ktor-io, ktor-utils,
  ktor-network, ktor-websockets, ktor-sse, ktor-http-cio, ktor-network-tls,
  ktor-client-cio, ktor-serialization, kotlinx-coroutines-core, kotlinx-io-core,
  kotlinx-io-bytestring, atomicfu, etc.)
- **~7 MB total klib size**, dominated by `ktor-client-curl-cinterop` (4.3 MB alone)
- Full coroutine runtime (`kotlinx-coroutines-core`, 864 KB) required just to wrap
  synchronous libcurl calls in `runBlocking`

keel's HTTP needs are minimal: synchronous GET requests to download files. Features
provided by Ktor (WebSocket, SSE, CIO engine, HTTP/2, content negotiation) are unused.

Kotlin/Native build times are inherently slow due to LLVM compilation. With Ktor, a
full build took ~1m 40s. Reducing dependency count is one of the few levers available.

## Decision

Replace Ktor Client with direct libcurl cinterop.

- Define a `.def` file (`src/nativeInterop/cinterop/libcurl.def`) that binds `curl/curl.h`
- Use `curl_easy_*` API directly: `curl_easy_init`, `curl_easy_setopt`, `curl_easy_perform`,
  `curl_easy_getinfo`, `curl_easy_cleanup`
- Write downloaded data directly to a file via `CURLOPT_WRITEFUNCTION` callback
  (streaming, no in-memory buffering)
- Remove all Ktor and kotlinx-coroutines dependencies from `build.gradle.kts`

## Consequences

### Positive

- **Faster builds**: ~14 klib modules eliminated from compilation and linking
- **Smaller binary**: removed ~7 MB of unused klib dependencies
- **Simpler code**: no coroutine wrapping (`runBlocking`), no `HttpClient` lifecycle
  management (`withHttpClient`). `downloadFile` is a plain synchronous function
- **Streaming writes**: Ktor implementation used `readRawBytes()` (full in-memory buffer),
  libcurl callback writes directly to file via `fwrite`
- **Same underlying library**: Ktor's Curl engine was already a wrapper around libcurl,
  so network behavior and TLS handling are identical

### Negative

- **Build-time dependency on libcurl-dev**: developers and CI must have `libcurl4-openssl-dev`
  (or equivalent) installed for the cinterop header generation step
- **Platform-specific include paths**: the `.def` file contains
  `-I/usr/include/x86_64-linux-gnu` which may need adjustment for other architectures
  (aarch64, macOS)
- **No JVM target compatibility**: cinterop is Kotlin/Native only. If keel ever needs a
  JVM build variant, HTTP would require a separate implementation (e.g., `java.net.URL`)
- **Lower-level error handling**: must manually check `curl_easy_perform` return codes and
  `CURLINFO_RESPONSE_CODE` instead of Ktor's typed `HttpResponse`

### Neutral

- **macOS compatibility**: libcurl ships with macOS, so `linkerOpts.osx = -lcurl` works
  without additional installation
- **Runtime behavior unchanged**: same libcurl underneath, same TLS stack, same connection
  pooling (per-handle)

## Alternatives Considered

1. **Keep Ktor, accept slow builds** — rejected because build time directly impacts
   development velocity on a tool whose value proposition is fast builds
2. **Use `platform.posix` sockets for raw HTTP** — rejected because HTTPS (TLS) would
   require additional C library bindings (OpenSSL/mbedTLS), adding more complexity than
   it removes
3. **Shell out to `curl` command** — rejected because it adds process fork/exec overhead
   per request and complicates error handling
