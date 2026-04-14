package kolt.build.daemon

import kolt.infra.sha256Hex

// Opaque key for the per-project daemon working directory. Stability
// across kolt runs is required (so the same project reuses one daemon);
// cryptographic strength is not — this is a directory name, not a
// security primitive. Truncating SHA-256 to 16 hex chars (64 bits) keeps
// the directory name short while collision probability stays negligible
// for any realistic number of projects on one machine.
fun projectHashOf(absProjectPath: String): String =
    sha256Hex(absProjectPath.encodeToByteArray()).take(16)
