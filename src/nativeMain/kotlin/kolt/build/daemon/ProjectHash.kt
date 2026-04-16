package kolt.build.daemon

import kolt.infra.sha256Hex

// Truncated to 16 hex chars — collision probability negligible for realistic project counts.
fun projectHashOf(absProjectPath: String): String =
    sha256Hex(absProjectPath.encodeToByteArray()).take(16)
