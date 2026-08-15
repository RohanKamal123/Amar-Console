package com.amarhelper.console.data.net

import com.amarhelper.console.core.result.ApiResult
import kotlinx.coroutines.CancellationException
import retrofit2.Response

/**
 * Runs a network call and folds every outcome into [ApiResult].
 *
 * Nothing above this line ever sees an exception: repositories return values, and the
 * UI switches on [com.amarhelper.console.core.result.AppError]. Coroutine cancellation
 * is re-thrown so structured concurrency keeps working — it is not an app error.
 */
suspend inline fun <T> safeApiCall(crossinline block: suspend () -> T): ApiResult<T> = try {
    ApiResult.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    ApiResult.Failure(ErrorMapper.fromThrowable(e))
}

/**
 * As [safeApiCall], but for endpoints where the HTTP status itself carries meaning.
 *
 * An empty body is a failure only when the caller expected data. Endpoints that return
 * `204 No Content` — OpenCode's `prompt_async`, for one — are declared as `Response<Unit>`
 * and succeed with no body at all.
 */
suspend inline fun <reified T> safeResponseCall(crossinline block: suspend () -> Response<T>): ApiResult<T> = try {
    val response = block()
    val body = response.body()
    when {
        response.isSuccessful && body != null -> ApiResult.Success(body)
        response.isSuccessful && T::class == Unit::class -> ApiResult.Success(Unit as T)
        response.isSuccessful -> ApiResult.Failure(
            com.amarhelper.console.core.result.AppError.Malformed("The backend returned an empty body."),
        )
        else -> ApiResult.Failure(ErrorMapper.fromStatus(response.code(), response))
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    ApiResult.Failure(ErrorMapper.fromThrowable(e))
}
