package com.amarhelper.console.data.remote.web

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET

/** A minimal reachability probe for browser-based workspaces such as code-server. */
interface WebUiApi {
    @GET(".")
    suspend fun root(): Response<ResponseBody>
}
