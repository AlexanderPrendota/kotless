package io.kotless.dsl.dispatcher

import io.kotless.dsl.conversion.ConversionService
import io.kotless.dsl.events.HttpRequest
import io.kotless.dsl.events.HttpResponse
import io.kotless.dsl.lang.KotlessContext
import io.kotless.dsl.lang.http.*
import io.kotless.dsl.reflection.FunctionCaller
import io.kotless.dsl.reflection.ReflectionScanner
import org.slf4j.LoggerFactory
import java.lang.reflect.InvocationTargetException

internal object Dispatcher {
    private val logger = LoggerFactory.getLogger(Dispatcher::class.java)

    private val pipeline by lazy { preparePipeline(ReflectionScanner.objectsWithSubtype<HttpRequestInterceptor>().sortedBy { it.priority }) }

    fun dispatch(request: HttpRequest, resourceKey: RouteKey): HttpResponse {
        return try {
            KotlessContext.HTTP.request = request
            pipeline(request, resourceKey)
        } finally {
            KotlessContext.HTTP.reset()
        }
    }

    private fun preparePipeline(left: List<HttpRequestInterceptor>): (HttpRequest, RouteKey) -> HttpResponse {
        if (left.isNotEmpty()) {
            val interceptor = left.first()
            return { req, key ->
                interceptor.intercept(req, key, preparePipeline(left.drop(1)))
            }
        } else {
            return { req, key -> processRequest(req, key) }
        }
    }

    private fun processRequest(request: HttpRequest, resourceKey: RouteKey): HttpResponse {
        logger.info("Passing request to route {}", resourceKey)
        val func = RoutesCache[resourceKey] ?: return notFound()
        logger.debug("Found $func for key $resourceKey")

        val result = try {
            FunctionCaller.call(func, request.allParams)
        } catch (e: Exception) {
            logger.error("Failed on call of function ${func.name}", if (e is InvocationTargetException) e.targetException else e)
            return serverError(e.message)
        }

        logger.info("Route returned result")
        return when (result) {
            is HttpResponse -> result
            else -> okResponse(result?.toString(), resourceKey.mimeType)
        }
    }
}
