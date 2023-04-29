package com.solo.httpclient

import android.util.Log
import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.HttpURLConnection
import java.util.*
import kotlin.time.minutes

//ex: Cache-Control: max-age=20, stale-while-revalidate=40
val SWR_REGEX = "^.*\\s*[Ss]tale-[Ww]hile-[Rr]evalidate\\s*=\\s*(\\d*)\\s*".toRegex()

class SWRInterceptor(
    private val requestRunner: ((Request) -> Response),
    private val currentSecondsGetter: (() -> Long) = { System.currentTimeMillis() / 1000 },
    private val debugLogger: ((String) -> Unit)? = null
) :
    Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originRequest = chain.request()
        //if FORCE_NETWORK or FORCE_CACHE or prefer cache, leave it to the original flow
        if (originRequest.cacheControl.noCache ||originRequest.cacheControl.onlyIfCached) {
            return chain.proceed(originRequest)
        }

        //fetch cache response if available
        val cacheResponse = getCacheOnlyResponse(originRequest) ?: return chain.proceed(originRequest)

        //parse stale-while-revalidate value
        val responseSeconds = cacheResponse.receivedResponseAtMillis / 1000
        val cacheControlString = getCacheControlHeader(cacheResponse)
        val maxAgeSeconds = cacheResponse.cacheControl.maxAgeSeconds.toLong()
        val swrSeconds = SWR_REGEX.find(cacheControlString)?.groups?.last()?.value?.toLongOrNull()?:0L
        val nowSeconds = currentSecondsGetter.invoke()
        val swrStart = responseSeconds + maxAgeSeconds
        val swrEnd = swrStart + swrSeconds

        if (nowSeconds < swrStart) {
            //cache is fresh, return cache response
            return cacheResponse
        } else if (nowSeconds in swrStart..swrEnd) {
            //cache is stale, verify availability with network request
            val newRequest = chain.request().newBuilder()
                .cacheControl(CacheControl.FORCE_NETWORK)
                .build()
            requestRunner.invoke(newRequest)
            //meanwhile return cache response
            return cacheResponse
        } else {
            //totally expired, do a network request
            val newRequest = chain.request().newBuilder()
                .cacheControl(CacheControl.FORCE_NETWORK)
                .build()
            return chain.proceed(newRequest)
        }
    }

    private fun getCacheControlHeader(response: Response): String =
        (response.header("cache-control") ?: response.header("Cache-Control")
        ?: response.header("Cache-control") ?: "").toLowerCase()


    private fun getCacheOnlyResponse(request: Request): Response? {
        try {
            // Use the cache without hitting the network first
            // 504 code indicates that the Cache is stale
            val preferCache = CacheControl.Builder()
                .onlyIfCached()
                .build()
            val cacheRequest = request.newBuilder().cacheControl(preferCache).build()

            val cacheResponse = requestRunner(cacheRequest)

            if (cacheResponse.code != HttpURLConnection.HTTP_GATEWAY_TIMEOUT) {
                return cacheResponse
            }
        } catch (ioe: IOException) {
            ioe.printStackTrace()
            debugLogger?.invoke("IOException: ${ioe.message}")
            // Failures are ignored as we can fallback to the network
            // and hopefully repopulate the cache.
        }
        return null
    }
}