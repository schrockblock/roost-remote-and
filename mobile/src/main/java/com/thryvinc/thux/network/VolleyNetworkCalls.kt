package com.thryvinc.thux.network

import android.util.Log
import com.android.volley.*
import com.android.volley.toolbox.HttpHeaderParser
import com.android.volley.toolbox.JsonRequest
import com.cesarferreira.pluralize.pluralize
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.thryvinc.thux.*
import com.thryvinc.thux.models.SessionManager
import com.thryvinc.thux.models.toSnakeCase
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.UnsupportedEncodingException
import java.lang.reflect.Type
import java.nio.charset.Charset

open class FunctionalJsonRequest<T>(method: Int = Method.GET,
                                    url: String,
                                    stringBody: String?,
                                    val applyHeaders: (MutableMap<String, String>) -> MutableMap<String, String>,
                                    val parseResponseString: (String) -> T?,
                                    val listener: (T?) -> Unit,
                                    val errorListener: (VolleyError) -> Unit,
                                    val shouldStub: Boolean,
                                    val stubHolder: StubHolderInterface? = null):
        JsonRequest<T?>(method, url, stringBody, listener, errorListener) {

    companion object {
        fun <S> parseVolleyResponse(response: NetworkResponse, stringParser: (String) -> S): Response<S> {
            try {
                val charset = charset(HttpHeaderParser.parseCharset(response.headers, JsonRequest.PROTOCOL_CHARSET))
                val jsonString = String(response.data, charset)
                return Response.success<S>(jsonString into stringParser, HttpHeaderParser.parseCacheHeaders(response))
            } catch (e: UnsupportedEncodingException) {
                return Response.error<S>(ParseError(e))
            } catch (je: JSONException) {
                print(String(response.data, Charset.defaultCharset()))
                return Response.error<S>(ParseError(je))
            }

        }
    }

    override fun parseNetworkResponse(response: NetworkResponse?): Response<T?> {
        if (response != null) {
            val responseParser = parseResponseString intoSecond ::parseVolleyResponse
            return response into responseParser
        }
        return Response.error(ParseError())
    }

    override fun getHeaders(): MutableMap<String, String> {
        val headers = HashMap<String, String>(super.getHeaders())
        return applyHeaders(headers)
    }
}

open class NetworkCall<T>(val serverConfiguration: ServerConfiguration?,
                          open val method: Int = Request.Method.GET,
                          open val endpoint: String,
                          open var stringBody: String? = null,
                          open var parseResponseString: (String) -> T? = ::toNull,
                          open var listener: (T?) -> Unit = ::doNothing,
                          open var errorListener: (VolleyError) -> Unit,
                          open var stubHolder: StubHolderInterface? = null) {
    open var url: (String) -> String = { it into ::urlWithServerConfiguration }
    open var applyHeaders: (MutableMap<String, String>) -> MutableMap<String, String> = identity()

    protected fun urlWithServerConfiguration(endpoint: String): String {
        if (serverConfiguration != null) {
            return endpoint into serverConfiguration::urlForEndpoint
        }
        return "invalid.server.configuration"
    }

    fun createRequest(): JsonRequest<T?> {
        return FunctionalJsonRequest<T>(method,
                endpoint into url,
                stringBody,
                applyHeaders,
                parseResponseString,
                listener,
                errorListener,
                serverConfiguration?.shouldStub ?: true,
                stubHolder)
    }

    fun fire() {
        val request = createRequest()
        if (BuildConfig.DEBUG) {
            Log.d("request","url: ${request.url}")
            Log.d("request","method: ${request.method}")
            Log.d("request","data: ${stringBody}")
        }
        VolleyManager.addToQueue(request)
    }
}

open class CredsLoginCall<T>(serverConfiguration: ServerConfiguration?,
                             endpoint: String = "sessions",
                             val wrapKey: String? = "user",
                             val usernameKey: String? = "username",
                             val passwordKey: String? = "password",
                             parseResponse: (String) -> T?,
                             listener: (T?) -> Unit,
                             errorListener: (VolleyError) -> Unit,
                             stubHolder: StubHolderInterface? = null):
        NetworkCall<T>(serverConfiguration, Request.Method.POST,
                endpoint,
                parseResponseString = parseResponse,
                listener = listener,
                errorListener = errorListener,
                stubHolder = stubHolder) {
    var username: String = ""
    var password: String = ""
    override var stringBody: String?
        get() = createBody()
        set(_) {}

    fun createBody(): String {
        var jsonObject = JSONObject()
        jsonObject.put(usernameKey, username)
        jsonObject.put(passwordKey, password)
        if (wrapKey != null) {
            val wrapperJson = JSONObject()
            wrapperJson.put(wrapKey, jsonObject)
            jsonObject = wrapperJson
        }
        return jsonObject.toString()
    }
}

open class TokenLoginCall<T>(serverConfiguration: ServerConfiguration?,
                             endpoint: String = "sessions",
                             val wrapKey: String? = "token",
                             val tokenKey: String? = "token",
                             parseResponse: (String) -> T?,
                             listener: (T?) -> Unit,
                             errorListener: (VolleyError) -> Unit,
                             stubHolder: StubHolderInterface? = null):
        NetworkCall<T>(serverConfiguration, Request.Method.POST,
                endpoint,
                parseResponseString = parseResponse,
                listener = listener,
                errorListener = errorListener,
                stubHolder = stubHolder) {
    var token: String = ""
    override var stringBody: String?
        get() = createBody()
        set(_) {}

    fun createBody(): String {
        var jsonObject = JSONObject()
        jsonObject.put(tokenKey, token)
        if (wrapKey != null) {
            val wrapperJson = JSONObject()
            wrapperJson.put(wrapKey, jsonObject)
            jsonObject = wrapperJson
        }
        return jsonObject.toString()
    }
}

open class AuthenticatedCall<T>(serverConfiguration: ServerConfiguration?,
                                method: Int = Request.Method.GET,
                                endpoint: String,
                                stringBody: String? = null,
                                parseResponse: (String) -> T?,
                                listener: (T?) -> Unit,
                                errorListener: (VolleyError) -> Unit,
                                stubHolder: StubHolderInterface? = null):
        NetworkCall<T>(serverConfiguration, method,
                endpoint,
                stringBody,
                parseResponse,
                listener,
                errorListener,
                stubHolder) {
    override var applyHeaders: (MutableMap<String, String>) -> MutableMap<String, String>
        get() = if (SessionManager.session != null) (SessionManager.session!!)::addAuthHeaders else identity()
        set(_) {}
}

open class IndexCall<T>(serverConfiguration: ServerConfiguration?,
                        endpoint: String,
                        parseResponse: (String) -> List<T>?,
                        listener: (List<T>?) -> Unit,
                        errorListener: (VolleyError?) -> Unit,
                        stubHolder: StubHolderInterface? = null):
        AuthenticatedCall<List<T>>(
                serverConfiguration = serverConfiguration,
                endpoint = endpoint,
                parseResponse = parseResponse,
                listener = listener,
                errorListener = errorListener,
                stubHolder = stubHolder
        )

open class UrlParameteredCall<T>(serverConfiguration: ServerConfiguration?,
                                 method: Int = Request.Method.GET,
                                 endpoint: String,
                                 stringBody: String? = null,
                                 parseResponse: (String) -> T?,
                                 listener: (T?) -> Unit,
                                 errorListener: (VolleyError?) -> Unit,
                                 stubHolder: StubHolderInterface? = null):
        AuthenticatedCall<T>(serverConfiguration,
                method,
                endpoint,
                stringBody,
                parseResponse,
                listener,
                errorListener,
                stubHolder) {
    open var urlParams: MutableMap<String, String> = HashMap()
    override var url: (String) -> String
        get() = super.url o (urlParams intoFirst ::addUrlParamsToUrl)
        set(_) {}

    open fun addUrlParamsToUrl(params: MutableMap<String, String>, url: String): String {
        if (params.isNotEmpty()) return "$url?${params into ::paramsString}"
        return url
    }

    companion object {
        fun paramsString(params: Map<String, String>): String {
            return params.keys.map { "$it=${params[it]}" }.joinToString(separator = "&")
        }
    }
}

open class UrlParameteredIndexCall<T>(serverConfiguration: ServerConfiguration?,
                                      endpoint: String,
                                      parseResponse: (String) -> List<T>?,
                                      listener: (List<T>?) -> Unit,
                                      errorListener: (VolleyError?) -> Unit,
                                      stubHolder: StubHolderInterface? = null):
        IndexCall<T>(serverConfiguration,
                endpoint,
                parseResponse,
                listener,
                errorListener,
                stubHolder) {
    open var urlParams: MutableMap<String, String> = HashMap()
    override var url: (String) -> String
        get() = super.url o (urlParams intoFirst ::addUrlParamsToUrl)
        set(_) {}

    open fun addUrlParamsToUrl(params: MutableMap<String, String>, url: String): String {
        if (params.isNotEmpty()) return "$url?${params into ::paramsString}"
        return url
    }

    companion object {
        fun paramsString(params: Map<String, String>): String {
            return params.keys.map { "$it=${params[it]}" }.joinToString(separator = "&")
        }
    }
}

open class PagedCall<T>(serverConfiguration: ServerConfiguration?,
                        endpoint: String,
                        stringBody: String? = null,
                        parseResponse: (String) -> T?,
                        listener: (T?) -> Unit,
                        errorListener: (VolleyError?) -> Unit,
                        stubHolder: StubHolderInterface? = null):
        UrlParameteredCall<T>(serverConfiguration,
                Request.Method.GET,
                endpoint,
                stringBody,
                parseResponse,
                listener,
                errorListener,
                stubHolder) {
    open var pageParamKey = "page"
    open var page = 0

    override fun addUrlParamsToUrl(params: MutableMap<String, String>, url: String): String {
        params[pageParamKey] = page.toString()
        return super.addUrlParamsToUrl(params, url)
    }
}

open class PagedIndexCall<T>(serverConfiguration: ServerConfiguration?,
                             endpoint: String,
                             parseResponse: (String) -> List<T>?,
                             listener: (List<T>?) -> Unit,
                             errorListener: (VolleyError?) -> Unit,
                             stubHolder: StubHolderInterface? = null):
        PagedCall<List<T>>(
                serverConfiguration = serverConfiguration,
                endpoint = endpoint,
                parseResponse = parseResponse,
                listener = listener,
                errorListener = errorListener,
                stubHolder = stubHolder
        )
