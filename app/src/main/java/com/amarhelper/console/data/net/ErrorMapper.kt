package com.amarhelper.console.data.net

import com.amarhelper.console.core.result.AppError
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import retrofit2.Response

/** Turns transport and HTTP failures into the app's [AppError] vocabulary. */
object ErrorMapper {

    fun fromThrowable(throwable: Throwable): AppError = when (throwable) {
        is CancellationException -> AppError.Cancelled
        // Android refused the request before it left the device. Saying "check your VPN"
        // here sends the user hunting for a network problem that does not exist.
        is UnknownServiceException -> AppError.Offline(
            "Android blocked a plain-HTTP request to this host. Use https, or a private " +
                "or tailnet address.",
        )
        is SocketTimeoutException -> AppError.Timeout()
        is UnknownHostException -> AppError.Offline(
            "Can't resolve that host. Check the URL and that your VPN is connected.",
        )
        is ConnectException -> AppError.Offline(
            "Connection refused. The service may be down or the port wrong.",
        )
        is SSLException -> AppError.Offline(
            "TLS handshake failed. Check the certificate on that host.",
        )
        is SerializationException -> AppError.Malformed()
        is HttpException -> fromStatus(throwable.code(), throwable.response())
        is IOException -> AppError.Offline()
        else -> AppError.Http(code = -1, message = throwable.message ?: "Unexpected error.")
    }

    fun fromStatus(code: Int, response: Response<*>? = null): AppError = when (code) {
        401 -> AppError.Unauthorized()
        403 -> AppError.Forbidden()
        404 -> AppError.NotFound()
        408 -> AppError.Timeout()
        409 -> AppError.Conflict()
        429 -> AppError.RateLimited(retryAfterSeconds = response?.retryAfterSeconds())
        500, 502, 503, 504 -> AppError.ServerError(code)
        else -> if (code in 500..599) AppError.ServerError(code) else AppError.Http(code)
    }

    private fun Response<*>.retryAfterSeconds(): Long? =
        headers()["Retry-After"]?.toLongOrNull()
}
