package com.sslproxy.schema.server.auth

import cats.data.OptionT
import cats.effect.{Clock, IO, SyncIO}
import cats.syntax.all.*
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
import org.typelevel.vault.Key

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Date
import scala.concurrent.duration.*

final case class Claims(subject: String, expiresAt: Option[Instant], role: String)

object UserRole:
  val Admin = "admin"
  val Operator = "operator"
  val Viewer = "viewer"

  private val levels: Map[String, Int] =
    Map(Viewer -> 0, Operator -> 1, Admin -> 2)

  def normalize(value: String): Option[String] =
    value.trim.toLowerCase match
      case Admin => Some(Admin)
      case Operator => Some(Operator)
      case Viewer => Some(Viewer)
      case _ => None

  def normalize(value: Option[String], default: String): Either[String, String] =
    value
      .map(normalize)
      .sequence
      .map(_.getOrElse(default))
      .toRight("role must be one of admin, operator, or viewer")

  def atLeast(actual: String, required: String): Boolean =
    levels.getOrElse(actual, -1) >= levels.getOrElse(required, Int.MaxValue)

object AuthContext:
  val claimsKey: Key[Claims] = Key.newKey[SyncIO, Claims].unsafeRunSync()

  def claims(request: Request[IO]): Either[Response[IO], Claims] =
    request.attributes.lookup(claimsKey).toRight(unauthorized("missing auth context"))

  def requireRole(request: Request[IO], required: String)(use: Claims => IO[Response[IO]]): IO[Response[IO]] =
    claims(request) match
      case Left(response) => IO.pure(response)
      case Right(current) =>
        if UserRole.atLeast(current.role, required) then use(current)
        else Forbidden(Json.obj("error" -> Json.fromString(s"$required role required")))

  private def unauthorized(message: String): Response[IO] =
    Response[IO](Status.Unauthorized).withEntity(Json.obj("error" -> Json.fromString(message)))

object JwtTokens:
  private val issuer = "schema-migrator"
  private val ttl = 8.hours

  def create(secret: String, subject: String, role: String = UserRole.Admin): IO[(String, Long)] =
    for
      now <- Clock[IO].realTimeInstant
      expiresAt = now.plusSeconds(ttl.toSeconds)
      token <- IO.delay {
        JWT
          .create()
          .withIssuer(issuer)
          .withSubject(subject)
          .withClaim("role", role)
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
      expiresAt = Option(decoded.getExpiresAt).map(_.toInstant),
      role = Option(decoded.getClaim("role").asString()).flatMap(UserRole.normalize).getOrElse(UserRole.Viewer)
    )

object JwtMiddleware:
  type TokenVerifier = String => IO[Either[String, Claims]]

  def apply(config: ServerConfig)(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    apply(config, None)(routes)

  def apply(config: ServerConfig, keycloakVerifier: Option[TokenVerifier])(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    HttpRoutes { request =>
      if bypassesAuth(config, request) then routes(request)
      else
        extractToken(request) match
          case None => OptionT.liftF(IO.pure(unauthorized("missing bearer token")))
          case Some(token) =>
            OptionT.liftF {
              verify(config, keycloakVerifier, token).flatMap {
                case Right(claims) =>
                  routes.run(request.withAttribute(AuthContext.claimsKey, claims)).getOrElseF(NotFound())
                case Left(error) => IO.pure(unauthorized(error))
              }
            }
    }

  private def verify(
    config: ServerConfig,
    keycloakVerifier: Option[TokenVerifier],
    token: String
  ): IO[Either[String, Claims]] =
    config.apiBearerToken.filter(expected => constantTimeEquals(expected.trim, token)) match
      case Some(_) => IO.pure(Right(Claims("static-api-token", None, UserRole.Admin)))
      case None =>
        JwtTokens.verify(config.jwtSecret, token).flatMap {
          case Right(claims) => IO.pure(Right(claims))
          case Left(hmacError) if config.keycloakEnabled =>
            keycloakVerifier match
              case Some(verifier) => verifier(token)
              case None => IO.pure(Left(s"Keycloak auth is enabled but no verifier is configured: $hmacError"))
          case Left(error) => IO.pure(Left(error))
        }

  private def bypassesAuth(config: ServerConfig, request: Request[IO]): Boolean =
    request.method == Method.OPTIONS || {
      val path = request.uri.path.renderString
      val devAuthPath = config.devAuthEnabled && (path == "/auth/token" || path == "/api/auth/token")
      path == "/health" || path == "/api/health" || devAuthPath
    }

  private def extractToken(request: Request[IO]): Option[String] =
    bearerHeader(request)

  private def bearerHeader(request: Request[IO]): Option[String] =
    request.headers.headers
      .find(_.name == CIString("Authorization"))
      .map(_.value.trim)
      .filter(_.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length))
      .map(_.drop("Bearer ".length).trim)
      .filter(_.nonEmpty)

  private def constantTimeEquals(expected: String, actual: String): Boolean =
    MessageDigest.isEqual(
      expected.getBytes(StandardCharsets.UTF_8),
      actual.getBytes(StandardCharsets.UTF_8)
    )

  private def unauthorized(message: String): Response[IO] =
    Response[IO](Status.Unauthorized).withEntity(Json.obj("error" -> Json.fromString(message)))
