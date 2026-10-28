package io.legado.app.help

import java.net.URI

/** Origin and reverse-proxy base path form the boundary for authenticated image requests. */
internal object ReaderServerResourceUrl {
    fun isImageResource(serverUrl: String, resourceUrl: String): Boolean = try {
        val server = URI(serverUrl.trim().trimEnd('/'))
        val resource = URI(resourceUrl)
        val scheme = server.scheme?.lowercase()
        (scheme == "https" || scheme == "http") &&
                resource.scheme?.lowercase() == scheme &&
                !server.host.isNullOrEmpty() && server.host.equals(resource.host, ignoreCase = true) &&
                port(server) == port(resource) &&
                resource.userInfo == null && resource.fragment == null &&
                resource.path.startsWith(server.path.trimEnd('/') + "/api/v1/books/") &&
                resource.path.removePrefix(server.path.trimEnd('/'))
                    .matches(Regex("/api/v1/books/[^/]+/(cover|assets)"))
    } catch (_: Exception) {
        false
    }

    private fun port(uri: URI): Int = if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
}
