package dev.mokouliszt.overlayai

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress

/**
 * キャリアDNSが openai 系ドメインを解決できない環境
 * （"Unable to resolve host ... No address associated with hostname"）への対策。
 *
 * システムDNSを優先し、失敗時に Cloudflare DoH へフォールバックする
 * （Chrome の Secure DNS 相当）。auth.openai.com / chatgpt.com 双方に効く。
 */
object Net {

    private val doh: Dns by lazy {
        val bootstrap = OkHttpClient.Builder().build()
        DnsOverHttps.Builder().client(bootstrap)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            // DoHサーバ自身の名前解決にシステムDNSを使わせない（固定IPでブートストラップ）
            .bootstrapDnsHosts(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("1.0.0.1")
            )
            .build()
    }

    /** システムDNS優先・失敗時DoHフォールバック。 */
    private val resilientDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            try {
                Dns.SYSTEM.lookup(hostname)
            } catch (e: Exception) {
                doh.lookup(hostname)
            }
    }

    fun client(builder: OkHttpClient.Builder): OkHttpClient =
        builder.dns(resilientDns).build()
}
