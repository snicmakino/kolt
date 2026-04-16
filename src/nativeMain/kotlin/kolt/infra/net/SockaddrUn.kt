package kolt.infra.net

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import platform.linux.sockaddr_un
import platform.posix.AF_UNIX
import platform.posix.memset
import platform.posix.socklen_t

// unix(7): AF_UNIX pathnames capped at 108 bytes including NUL.
internal const val SUN_PATH_CAPACITY = 108

@OptIn(ExperimentalForeignApi::class)
internal fun fillSockaddrUn(addr: sockaddr_un, pathBytes: ByteArray): socklen_t {
    memset(addr.ptr, 0, sizeOf<sockaddr_un>().convert())
    addr.sun_family = AF_UNIX.convert()
    val sunPath = addr.sun_path
    for (i in pathBytes.indices) {
        sunPath[i] = pathBytes[i]
    }
    sunPath[pathBytes.size] = 0
    val sunPathOffset = sunPath.rawValue.toLong() - addr.ptr.rawValue.toLong()
    return (sunPathOffset.toInt() + pathBytes.size + 1).convert()
}
