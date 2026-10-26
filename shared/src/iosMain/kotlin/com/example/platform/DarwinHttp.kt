@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.example.platform

// Foundation declares many of these calls in categories, which Kotlin sees as
// extension functions, hence the whole package.
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual fun newHttp(connectSeconds: Long, readSeconds: Long): Http = DarwinHttp(connectSeconds, readSeconds)

/**
 * [Http] on iPhone, through NSURLSession. Like OkHttp on Android it keeps no
 * cache and no cookies. NSURLSession has a single timeout for "nothing
 * received for this long", which covers connecting and reading, so it gets
 * the longer of the two, on the session and on each request alike.
 */
class DarwinHttp(connectSeconds: Long, readSeconds: Long) : Http {
    private val timeout = maxOf(connectSeconds, readSeconds).toDouble()
    private val session = NSURLSession.sessionWithConfiguration(
        NSURLSessionConfiguration.ephemeralSessionConfiguration().apply {
            setHTTPCookieStorage(null)
            setURLCache(null)
            // Set here as well as on each request: the session's own default
            // is 60 seconds, too short for an Ask answer, which can take
            // minutes while Claude runs its code.
            setTimeoutIntervalForRequest(timeout)
        }
    )

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse =
        send(request(url, "GET", headers))

    override suspend fun post(url: String, headers: Map<String, String>, json: String): HttpResponse =
        send(
            request(url, "POST", headers + ("Content-Type" to "application/json; charset=utf-8")).apply {
                setHTTPBody(json.encodeToByteArray().toNSData())
            }
        )

    private fun request(url: String, method: String, headers: Map<String, String>): NSMutableURLRequest {
        val nsUrl = requireNotNull(NSURL.URLWithString(url)) { "Not a URL: $url" }
        return NSMutableURLRequest.requestWithURL(nsUrl).apply {
            setHTTPMethod(method)
            setCachePolicy(NSURLRequestReloadIgnoringCacheData)
            setTimeoutInterval(timeout)
            headers.forEach { (name, value) -> setValue(value, forHTTPHeaderField = name) }
        }
    }

    private suspend fun send(request: NSMutableURLRequest): HttpResponse {
        val host = request.URL?.host ?: "server"
        return suspendCancellableCoroutine { cont ->
            val task = session.dataTaskWithRequest(request) { data, response, error ->
                val code = (response as? NSHTTPURLResponse)?.statusCode?.toInt()
                when {
                    // A cancelled coroutine has already moved on; nothing to report.
                    cont.isCancelled -> Unit
                    error == null && code != null -> cont.resume(HttpResponse(code, data?.toByteArray() ?: ByteArray(0)))
                    // An error after the status line arrived is an answer that broke off.
                    error != null && code != null ->
                        cont.resumeWithException(
                            HttpException("$host stopped answering: ${error.localizedDescription}", code = code, reason = error.reason())
                        )
                    else -> cont.resumeWithException(HttpException("$host unreachable: ${error?.localizedDescription}", reason = error?.reason()))
                }
            }
            cont.invokeOnCancellation { task.cancel() }
            task.resume()
        }
    }
}

/** iOS's own description ("The request timed out."), as words to follow "Couldn't reach ...:". */
private fun NSError.reason(): String = localizedDescription.trimEnd('.').replaceFirstChar { it.lowercase() }
