package com.example.platform

/**
 * Plain HTTP for the shared code: one request, the whole answer back. An
 * answer with an error status (404, 500) is still an answer; [HttpException]
 * means no answer arrived. Cancelling the calling coroutine cancels the request.
 */
interface Http {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse

    /** Sends [json] as the request body, as application/json in UTF-8. */
    suspend fun post(url: String, headers: Map<String, String>, json: String): HttpResponse
}

class HttpResponse(val code: Int, val body: ByteArray) {
    val isSuccessful: Boolean get() = code in 200..299

    /**
     * The body as UTF-8 text, which JSON always is. A leading byte-order mark
     * is dropped, as OkHttp's Response.string() drops it.
     */
    fun text(): String = body.decodeToString().removePrefix("﻿")
}

/** The server couldn't be reached, or the connection broke before the whole answer arrived. */
class HttpException(message: String, cause: Throwable? = null) : Exception(message, cause)
