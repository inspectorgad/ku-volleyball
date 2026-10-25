package com.example.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual fun newHttp(connectSeconds: Long, readSeconds: Long): Http = OkHttpHttp(connectSeconds, readSeconds)

/** [Http] on Android, through OkHttp. */
class OkHttpHttp(connectSeconds: Long, readSeconds: Long) : Http {
    private val client = OkHttpClient.Builder()
        .connectTimeout(connectSeconds, TimeUnit.SECONDS)
        .readTimeout(readSeconds, TimeUnit.SECONDS)
        // A call runs on OkHttp's own thread, which rethrows anything that is
        // not an IOException and so would crash the app (some phones' DNS
        // throws SecurityException when the app's network access is turned
        // off). As an IOException it becomes an ordinary failed request.
        .addInterceptor { chain ->
            try {
                chain.proceed(chain.request())
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException(e)
            }
        }
        .build()

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse =
        send(Request.Builder().url(url).headers(headers.toHeaders()).build())

    override suspend fun post(url: String, headers: Map<String, String>, json: String): HttpResponse =
        send(
            Request.Builder().url(url).headers(headers.toHeaders())
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
        )

    private suspend fun send(request: Request): HttpResponse {
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw HttpException("${request.url.host} unreachable", e, reason = e.reason())
        }
        return withContext(Dispatchers.IO) {
            response.use {
                try {
                    HttpResponse(it.code, it.body?.bytes() ?: ByteArray(0))
                } catch (e: IOException) {
                    throw HttpException("${request.url.host} stopped answering", e, code = it.code, reason = e.reason())
                }
            }
        }
    }
}

/** Why a request failed, in the words a reader sees after "Couldn't reach ...:". */
private fun IOException.reason(): String = when (this) {
    is SocketTimeoutException -> "it took too long to answer"
    is UnknownHostException -> "its address could not be looked up"
    is ConnectException -> "the connection was refused"
    is SSLException -> "the secure connection failed"
    else -> message ?: javaClass.simpleName
}

/** Runs the call off the main thread; cancelling the coroutine cancels the call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isCancelled) response.close() else cont.resume(response)
        }
    })
}
