package com.sslproxy.schema.server.auth

import cats.data.OptionT
import cats.effect.{Clock, IO}
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import com.sslproxy.schema.config.ServerConfig
import io.circe.Json
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString

import java.time.Instant
import java.util.Date
import scala.concurrent.duration.*

final case class Claims(subject: String, expiresAt: Option[Instant])

object JwtTokens:
  private val issuer = "schema-migrator"
  private val ttl = 8.hours

  def create(secret: String, subject: String): IO[(String, Long)] =
    for
      now <- Clock[IO].realTimeInstant
      expiresAt = now.plusSeconds(ttl.toSeconds)
      token <- IO.delay {
        JWT
          .create()
          .withIssuer(issuer)
          .withSubject(subject)
          .withIssuedAt(Date.from(now))
          .withExpiresAt(Date.from(expiresAt))
          .sign(Algorithm.HMAC256(secret))
      }
    yield token -> ttl.toSeconds

  def verify(secret: String, token: String): IO[Either[String, Claims]] =
    IO.delay {
      val verifier = JWT.require(Algorithm.HMAC256(secret)).withIssuer(issuer).build()
      val decoded = verifier.verify(token)
      Right(toClaims(decoded))
    }.recover { case error: JWTVerificationException => Left(error.getMessage) }

  private def toClaims(decoded: DecodedJWT): Claims =
    Claims(
      subject = Option(decoded.getSubject).filter(_.nonEmpty).getOrElse("unknown"),
      expiresAt = Option(decoded.getExpiresAt).map(_.toInstant)
    )

object JwtMiddleware:
  def apply(config: ServerConfig)(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    HttpRoutes { request =>
      if bypassesAuth(request) then routes(request)
      else
        extractToken(request) match
          case None => OptionT.liftF(IO.pure(unauthorized("missing bearer token")))
          case Some(token) =>
            OptionT.liftF {
              JwtTokens.verify(config.jwtSecret, token).flatMap {
                case Right(_) => routes.run(request).getOrElseF(NotFound())
                case Left(error) => IO.pure(unauthorized(error))
              }
            }
    }

  private def bypassesAuth(request: Request[IO]): Boolean =
    request.method == Method.OPTIONS || {
      val path = request.uri.path.renderString
      path == "/health" || path == "/api/health" || path == "/auth/token" || path == "/api/auth/token"
    }

  private def extractToken(request: Request[IO]): Option[String] =
    bearerHeader(request).orElse(queryTokenForStream(request))

  private def queryTokenForStream(request: Request[IO]): Option[String] =
    if isRunStreamPath(request) then request.uri.query.params.get("access_token").filter(_.nonEmpty)
    else None

  private def isRunStreamPath(request: Request[IO]): Boolean =
    val segments = request.uri.path.segments.map(_.decoded())
    segments match
      case Vector("api", "runs", _, "stream") | Vector("runs", _, "stream") => true
      case _ => false

  private def bearerHeader(request: Request[IO]): Option[String] =
    request.headers.headers
      .find(_.name == CIString("Authorization"))
      .map(_.value.trim)
      .filter(_.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length))
      .map(_.drop("Bearer ".length).trim)
      .filter(_.nonEmpty)

  private def unauthorized(message: String): Response[IO] =
    Response[IO](Status.Unauthorized).withEntity(Json.obj("error" -> Json.fromString(message)))
