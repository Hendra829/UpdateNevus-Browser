package com.nevus.mediabridge.vpn

/**
 * Android allows exactly one active [android.net.VpnService] system-wide — [SECURE_DNS] and
 * [VPN] are therefore mutually exclusive, not independent toggles. Selecting one tears the
 * other down first.
 */
enum class NetworkMode { OFF, SECURE_DNS, VPN }

/** Public DNS-over-HTTPS (RFC 8484) resolvers — existing public endpoints, not self-hosted. */
enum class DohProvider(val label: String, val endpoint: String) {
    CLOUDFLARE("Cloudflare (1.1.1.1)", "https://cloudflare-dns.com/dns-query"),
    GOOGLE("Google (8.8.8.8)", "https://dns.google/dns-query"),
    QUAD9("Quad9 (9.9.9.9)", "https://dns.quad9.net/dns-query"),
}
