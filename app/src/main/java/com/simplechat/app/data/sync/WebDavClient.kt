package com.simplechat.app.data.sync

import com.simplechat.app.R
import com.simplechat.app.data.AppTextException
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * 一个够用的 WebDAV 客户端：**PUT / GET / HEAD / MKCOL / PROPFIND**，别的一概不做。
 *
 * ### 为什么自己写而不引第三方库
 *
 * 同步只需要"把一个文件读回来、写回去"。sardine / dav4jvm 这类库带进的是
 * 一整套 DAV 对象模型（多级属性、锁、配额、ACL…），而真正会踩的坑只有三个：
 * 认证方式、父目录不存在、以及不同网盘的返回码脾气。这三件事自己处理反而更短。
 *
 * ### 全都按**文件**收发，不经过 ByteArray
 *
 * 载荷可能有几十兆（几百个会话的正文），整包读进内存就是一次 OOM。
 * 上传用 `asRequestBody(file)` 由 OkHttp 边读边发，下载直接写文件流。
 *
 * ### 兼容性上的几条硬规则
 *
 * - **只走 HTTP/1.1**。部分 WebDAV 服务端对 h2 的 upgrade 处理不干净，
 *   表现为 400 / 空响应；坚果云、Nextcloud、群晖都稳定支持 1.1。
 * - 认证只用 **Basic**（DAV 的标准做法；坚果云的应用密码就是给这个用的）。
 * - `PUT` 返回 **409** 说明父目录不存在 —— 逐级 `MKCOL` 后重试一次。
 * - 路径**逐段编码**（`addPathSegment`），不做字符串拼接：文件名里有空格、
 *   `#`、中文时手拼一定会错。
 */
class WebDavClient(
    private val client: OkHttpClient = defaultClient(),
) {

    /** 上一次请求的时间戳（毫秒）；0 = 还没发过。 */
    private var lastRequestAt = 0L
    private val paceLock = Any()

    /** 一次同步所需的全部连接信息。 */
    data class Config(
        /** 已经去掉结尾斜杠的服务器地址。 */
        val server: String,
        val user: String,
        val password: String,
        /** 远程文件路径，形如 `myDeepSeek/sync.jsonl`。 */
        val path: String,
    )

    /**
     * 试连：对服务器根做一次 `Depth: 0` 的 PROPFIND。
     *
     * 不写探针文件 —— 校验配置不该在用户网盘里留下任何东西。
     * 207 / 200 都算通；401 与 403 分开报，用户才知道该改密码还是改路径。
     */
    fun probe(config: Config) {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>
        """.trimIndent().toRequestBody(XmlMedia)

        val request = authorized(config, baseUrl(config))
            .method("PROPFIND", body)
            .header("Depth", "0")
            .build()

        call(request).use { response ->
            when {
                response.isSuccessful -> Unit
                response.code == 404 -> throw AppTextException(R.string.sync_err_not_found)
                else -> throw httpError(response)
            }
        }
    }

    /**
     * 把远程文件**流式**下载到 [target]。
     *
     * @return false 表示远端还没有这个文件（第一次同步），不是错误。
     */
    fun downloadToFile(config: Config, remotePath: String, target: File): Boolean {
        val request = authorized(config, targetUrl(config, remotePath)).get().build()
        return call(request).use { response ->
            when {
                response.code == 404 -> false
                !response.isSuccessful -> throw httpError(response)
                else -> {
                    val body = response.body ?: throw AppTextException(R.string.sync_err_network, "empty body")
                    target.parentFile?.mkdirs()
                    // 边读边写：不把整包读进内存
                    body.byteStream().use { input ->
                        target.outputStream().use { output -> input.copyTo(output, Buffer) }
                    }
                    true
                }
            }
        }
    }

    /** 上传一个本地文件（整体覆盖）。父目录不存在时自动补建后重试一次。 */
    fun uploadFile(
        config: Config,
        remotePath: String,
        file: File,
        mediaType: MediaType = OctetStream,
    ) {
        fun put(): Response = call(
            authorized(config, targetUrl(config, remotePath))
                .put(file.asRequestBody(mediaType))
                .build(),
        )

        var response = put()
        if (response.code == 409) {
            // 父目录不存在：逐级 MKCOL 之后重试一次。409 也可能来自服务端的其它限制，
            // 所以第二次再 409 就如实报错，不无限重试。
            response.close()
            ensureParentCollections(config, remotePath)
            response = put()
        }

        response.use { result ->
            when {
                result.isSuccessful -> Unit
                result.code == 409 -> throw AppTextException(R.string.sync_err_no_folder)
                else -> throw httpError(result)
            }
        }
    }

    /**
     * 列出一个目录下的**文件名**（`PROPFIND` + `Depth: 1`）。
     *
     * 同步靠它来算"远端有哪些会话、各自是哪一版"，因此不需要任何清单文件 ——
     * 目录本身就是清单。目录不存在（第一次同步）返回空列表。
     *
     * 只取文件名：`href` 是 URL 编码过的，且各服务端的命名空间前缀写法不一
     * （`d:href` / `D:href` / `href`），所以用宽松匹配而不是严格的 XML 解析 ——
     * 我们只要最后一个路径段，不需要文档结构。
     */
    fun listNames(config: Config, remoteDir: String): List<String> {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>
        """.trimIndent().toRequestBody(XmlMedia)

        val request = authorized(config, targetUrl(config, remoteDir))
            .method("PROPFIND", body)
            .header("Depth", "1")
            .build()

        return call(request).use { response ->
            when {
                response.code == 404 -> emptyList()
                !response.isSuccessful -> throw httpError(response)
                else -> {
                    val xml = response.body?.string().orEmpty()
                    HrefRegex.findAll(xml)
                        .mapNotNull { match -> match.groupValues[1].trim().ifEmpty { null } }
                        .mapNotNull { href -> lastSegment(href) }
                        .toList()
                }
            }
        }
    }

    /** 删一个远端文件；本来就不在（404）也算成功。 */
    fun delete(config: Config, remotePath: String) {
        val request = authorized(config, targetUrl(config, remotePath)).delete().build()
        call(request).use { response ->
            when {
                response.isSuccessful || response.code == 404 -> Unit
                else -> throw httpError(response)
            }
        }
    }

    /**
     * 远端是否存在这个文件（`HEAD`）。
     *
     * 图片上传前先用它判一下 —— 内容寻址的名字不变就意味着内容没变，
     * 没必要每次都把几十兆图片重传一遍。
     */
    fun exists(config: Config, remotePath: String): Boolean {
        val request = authorized(config, targetUrl(config, remotePath)).head().build()
        return call(request).use { response ->
            when {
                response.isSuccessful -> true
                response.code == 404 -> false
                response.code == 405 -> false // 有的服务端不许 HEAD：当作"没有"，随后会 PUT
                else -> throw httpError(response)
            }
        }
    }

    // ── 内部 ─────────────────────────────────────────────

    /**
     * 逐级创建父目录。
     *
     * 返回码**全部忽略**：`201` 是新建成功、`405` 是已经存在，
     * 两者都是想要的结果；真的建不出来时，随后的 PUT 会再报一次 409，
     * 那时才该让用户看到错误。
     */
    private fun ensureParentCollections(config: Config, remotePath: String) {
        val segments = pathSegments(remotePath)
        if (segments.size <= 1) return

        var url = baseUrl(config)
        for (index in 0 until segments.size - 1) {
            url = url.newBuilder().addPathSegment(segments[index]).build()
            runCatching {
                call(authorized(config, url).method("MKCOL", null).build()).close()
            }
        }
    }

    /**
     * 发一个请求，**自带节流与风控退避**。
     *
     * 坚果云这类服务端限的是"单位时间请求数"，同步又天生是密集请求
     * （几百个会话 + 几百张图）。不节流的话第一次全量同步必然撞上 503 ——
     * 请求本身完全合法，只是密。
     *
     * 两条措施，缺一不可：
     *
     * 1. **每个请求之间至少隔 [MinIntervalMs]** —— 把突发摊平成稳定节奏。
     *    以 120ms 计，一分钟约 500 个请求，远低于常见阈值。
     * 2. **429 / 503 / 509 指数退避后重试**，并尊重 `Retry-After`。
     *    以前这类响应会被翻译成"服务器返回 HTTP 503"直接判死整次同步，
     *    于是一半数据传上去、另一半永远传不上去 —— 越重试越像攻击。
     *
     * 所有请求方法（PUT/GET/HEAD/PROPFIND/DELETE/MKCOL）都是幂等的，
     * 所以重试是安全的。
     */
    private fun call(request: Request): Response {
        var attempt = 0
        while (true) {
            pace()
            val response = try {
                client.newCall(request).execute()
            } catch (e: IOException) {
                if (attempt < MaxRetries) {
                    backoff(attempt)
                    attempt++
                    continue
                }
                // DNS / 超时 / TLS：都归一句"网络错误"，后面带上系统原文
                throw AppTextException(R.string.sync_err_network, e.message.orEmpty())
            }

            if (response.code in ThrottledCodes && attempt < MaxRetries) {
                // 服务端给的等待秒数优先（有的直接写 Retry-After: 60）
                val retryAfter = response.header("Retry-After")?.trim()?.toLongOrNull()
                response.close()
                backoff(attempt, explicitMs = retryAfter?.times(1000))
                attempt++
                continue
            }
            return response
        }
    }

    /** 把请求摊平：两次请求之间至少隔 [MinIntervalMs]。 */
    private fun pace() {
        synchronized(paceLock) {
            val now = System.currentTimeMillis()
            val wait = MinIntervalMs - (now - lastRequestAt)
            if (lastRequestAt > 0 && wait > 0) sleepQuietly(wait)
            lastRequestAt = System.currentTimeMillis()
        }
    }

    /** 指数退避：1.2s → 2.4s → 4.8s … 封顶 [MaxBackoffMs]。 */
    private fun backoff(attempt: Int, explicitMs: Long? = null) {
        val wait = explicitMs ?: (BaseBackoffMs shl attempt.coerceIn(0, 5))
        sleepQuietly(wait.coerceIn(MinBackoffMs, MaxBackoffMs))
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (interrupted: InterruptedException) {
            // 被要求中断就当这一轮结束：调用方（协程取消）会看到后续的 IOException
            Thread.currentThread().interrupt()
        }
    }

    private fun authorized(config: Config, url: HttpUrl): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(config.user, config.password, Charsets.UTF_8))
            // 部分服务端（含坚果云）在 UA 为空时会拒绝请求
            .header("User-Agent", UserAgent)

    private fun baseUrl(config: Config): HttpUrl =
        config.server.trim().toHttpUrlOrNull()
            ?: throw AppTextException(R.string.sync_err_url)

    private fun targetUrl(config: Config, remotePath: String): HttpUrl {
        var url = baseUrl(config)
        pathSegments(remotePath).forEach { segment ->
            url = url.newBuilder().addPathSegment(segment).build()
        }
        return url
    }

    /** `href` 的最后一个路径段（URL 解码后）；没有就用 null。 */
    private fun lastSegment(href: String): String? {
        val trimmed = href.trimEnd('/')
        val last = trimmed.substringAfterLast('/')
        if (last.isEmpty()) return null
        return runCatching { java.net.URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
    }

    private fun pathSegments(path: String): List<String> =
        path.split('/').map { it.trim() }.filter { it.isNotEmpty() }

    private fun httpError(response: Response): Throwable = when (response.code) {
        401 -> AppTextException(R.string.sync_err_auth)
        403 -> AppTextException(R.string.sync_err_forbidden)
        404 -> AppTextException(R.string.sync_err_not_found)
        507 -> AppTextException(R.string.sync_err_storage)
        else -> AppTextException(R.string.sync_err_http, response.code)
    }

    companion object {
        private val JsonMedia = "application/json; charset=utf-8".toMediaType()
        private val OctetStream = "application/octet-stream".toMediaType()
        private val XmlMedia = "application/xml; charset=utf-8".toMediaType()

        private const val Buffer = 64 * 1024

        /**
         * 两次请求的最小间隔。
         *
         * 120ms ≈ 每分钟 500 个请求。首次全量同步会因此多花一两分钟，
         * 但换来的是"不再被风控掐断" —— 慢而成功，比快而始终传不完强。
         */
        private const val MinIntervalMs = 120L

        /** 指数退避的起点与上下限。 */
        private const val BaseBackoffMs = 1_200L
        private const val MinBackoffMs = 1_000L
        private const val MaxBackoffMs = 60_000L

        /** 重试次数。配合退避，最坏情况会等约两分钟才放弃。 */
        private const val MaxRetries = 5

        /** 服务端明确表示"请求太密/太忙"的那几个码。 */
        private val ThrottledCodes = setOf(429, 503, 509)

        private const val UserAgent = "myDeepSeek-Android"

        /**
         * 匹配 `<d:href>` / `<D:href>` / `<href>`。
         *
         * 宽容是有意的：不同服务端的前缀、大小写、属性写法都不一样，而我们只关心
         * 标签里的文本。真正的 XML 解析要么引一个库，要么写一堆命名空间处理 ——
         * 就为了取个文件名，不值。
         */
        private val HrefRegex = Regex(
            "<(?:[A-Za-z0-9]+:)?href[^>]*>([^<]+)</(?:[A-Za-z0-9]+:)?href>",
            RegexOption.IGNORE_CASE,
        )

        /** 同步文件本身用 JSON 的 MIME，便于网盘按类型识别/预览。 */
        val SyncPayloadMedia: MediaType = JsonMedia

        /**
         * 同步用的独立 OkHttp 客户端。
         *
         * 不与对话用的那个共用：聊天要 `readTimeout` 长、并发小，而同步是
         * **一坨几百 KB 到几十 MB 的读写**，超时语义完全不同（写要更宽松，
         * 连不上要更快失败）。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
