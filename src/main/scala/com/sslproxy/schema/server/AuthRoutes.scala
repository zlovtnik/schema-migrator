package com.sslproxy.schema.server

import cats.effect.IO
import com.sslproxy.schema.config.ServerConfig
import com.sslproxy.schema.server.auth.JwtTokens
import com.sslproxy.schema.store.{AuthTokenResponse, AuthTokenRequest, Models}
import io.circe.syntax.*
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

object AuthRoutes:
  import Models.given

  def routes(config: ServerConfig): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case request @ POST -> Root / "auth" / "token" =>
      request.as[AuthTokenRequest].flatMap { tokenRequest =>
        tokenRequest.secret match
          case Some(secret) if secret == config.devAuthSecret =>
            val subject = tokenRequest.subject.filter(_.nonEmpty).getOrElse("dev")
            JwtTokens
              .create(config.jwtSecret, subject)
              .flatMap { case (token, expiresIn) =>
                RouteJson.ok(AuthTokenResponse(token = token, token_type = "Bearer", expires_in = expiresIn).asJson)
              }
          case _ => Forbidden(RouteJson.error("invalid development auth secret"))
      }
    }
