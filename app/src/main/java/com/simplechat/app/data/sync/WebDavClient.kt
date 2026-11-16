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

    private fun call(request: Request): Response = try {
        client.newCall(request).execute()
    } catch (e: IOException) {
        // DNS / 超时 / TLS：都归一句"网络错误"，后面带上系统原文
        throw AppTextException(R.string.sync_err_network, e.message.orEmpty())
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

        private const val UserAgent = "myDeepSeek-Android"

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
