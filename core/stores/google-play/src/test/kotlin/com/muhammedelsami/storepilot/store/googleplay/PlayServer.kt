package com.muhammedelsami.storepilot.store.googleplay

import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import java.net.URI

/** Answers Play API requests from a list of routes and records every request. */
class PlayServer {
    class Request(val method: String, val url: String, val body: String) {
        val path: String
            get() = URI(url).rawPath

        val query: String
            get() = URI(url).rawQuery.orEmpty()
    }

    private class Route(
        val method: String,
        val path: String,
        val status: Int,
        val json: String,
        val headers: Map<String, String>,
    )

    private val routes = mutableListOf<Route>()
    val requests = mutableListOf<Request>()

    val transport = object : MockHttpTransport() {
        override fun buildRequest(method: String, url: String): LowLevelHttpRequest =
            object : MockLowLevelHttpRequest(url) {
                override fun execute(): LowLevelHttpResponse {
                    val request = Request(method, url, contentAsString)
                    requests += request
                    val route = routes.lastOrNull { it.method == method && it.path == request.path }
                        ?: error("Unexpected request: $method $url")
                    return MockLowLevelHttpResponse()
                        .setStatusCode(route.status)
                        .setContentType("application/json; charset=UTF-8")
                        .setContent(route.json)
                        .apply { route.headers.forEach { (name, value) -> addHeader(name, value) } }
                }
            }
    }

    /** [path] is relative to the app, for example `/edits/e1/tracks/production`. */
    fun on(method: String, path: String, json: String = "{}", status: Int = 200, headers: Map<String, String> = emptyMap()) {
        routes += Route(method, APP_PATH + path, status, json, headers)
    }

    /** Answers the resumable upload of a bundle or APK with [versionCode]. */
    fun onUpload(kind: String, versionCode: Long) {
        routes += Route("POST", "/upload$APP_PATH/edits/$EDIT_ID/$kind", 200, "", mapOf("Location" to UPLOAD_URL))
        routes += Route("PUT", URI(UPLOAD_URL).rawPath, 200, """{"versionCode": $versionCode}""", emptyMap())
    }

    fun onEditStart() = on("POST", "/edits", """{"id": "$EDIT_ID"}""")

    fun requests(method: String, path: String): List<Request> =
        requests.filter { it.method == method && it.path == APP_PATH + path }

    companion object {
        const val PACKAGE_NAME = "com.example.app"
        const val EDIT_ID = "e1"
        private const val APP_PATH = "/androidpublisher/v3/applications/$PACKAGE_NAME"
        private const val UPLOAD_URL = "https://upload.test/session"
    }
}
