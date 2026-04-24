package org.interscity.htc
package core.api

import org.apache.pekko.http.scaladsl.model.HttpMethods._
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route

/** Minimal CORS support for the simulator API.
  *
  * Allowed origin is configurable:
  *   env:    HTC_API_CORS_ORIGINS=http://localhost:3000  (comma-separated, or * for all)
  *   config: htc.api.cors-origins = "*"
  *
  * Default: * (allow all origins).
  */
object CorsSupport {

  /** Wraps a route with CORS response headers and OPTIONS preflight handling. */
  def cors(allowedOrigins: String)(route: Route): Route = {
    val headers = List(
      RawHeader("Access-Control-Allow-Origin",  allowedOrigins),
      RawHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS"),
      RawHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With, Accept"),
      RawHeader("Access-Control-Max-Age",       "3600")
    )

    respondWithHeaders(headers) {
      options {
        // Handle all preflight OPTIONS requests
        complete(StatusCodes.OK)
      } ~ route
    }
  }
}
