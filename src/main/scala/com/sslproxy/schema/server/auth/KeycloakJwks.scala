package com.sslproxy.schema.server.auth

import cats.effect.{Clock, IO, Ref}
import cats.syntax.all.*
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import com.sslproxy.schema.config.ServerConfig
import com.sslproxy.schema.effect.{Retry, RetryPolicy}
import io.circe.{Decoder, Json}
import io.circe.parser.parse
import org.http4s.Uri
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.time.Instant
import java.util.Base64
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

final class KeycloakJwks private (
  issuer: String,
  jwksUri: Uri,
  clientId: Option[String],
  audience: Option[String],
  fetchJwks: IO[KeycloakJwks.Jwks],
  cache: Ref[IO, KeycloakJwks.KeyCache]
):
  import KeycloakJwks.*

  def verify(token: String): IO[Either[String, Claims]] =
    decodeHeader(token).flatMap {
      case Left(error) => IO.pure(Left(error))
      case Right(decodedHeader) =>
        Option(decodedHeader.getKeyId).filter(_.nonEmpty) match
          case None => IO.pure(Left("Keycloak token is missing a key id"))
          case Some(kid) =>
            publicKeyFor(kid).flatMap {
              case Left(error) => IO.pure(Left(error))
              case Right(publicKey) =>
                verifyWithKey(token, publicKey)
            }
    }

  private def verifyWithKey(token: String, publicKey: RSAPublicKey): IO[Either[String, Claims]] =
    IO.delay {
      val decoded = JWT
        .require(Algorithm.RSA256(publicKey, null))
        .withIssuer(issuer)
        .build()
        .verify(token)
      validateAudience(decoded).map(_ => toClaims(decoded, clientId))
    }.recover { case error: JWTVerificationException => Left(error.getMessage) }

  private def validateAudience(decoded: DecodedJWT): Either[String, Unit] =
    audience.orElse(clientId) match
      case None => Right(())
      case Some(expected) =>
        val audiences = Option(decoded.getAudience).map(_.asScala.toSet).getOrElse(Set.empty)
        val azp = Option(decoded.getClaim("azp").asString())
        if audiences.contains(expected) || azp.contains(expected) then Right(())
        else Left(s"Keycloak token audience does not include '$expected'")

  private def publicKeyFor(kid: String): IO[Either[String, RSAPublicKey]] =
    for
      now <- Clock[IO].realTimeInstant
      current <- cache.get
      cacheFresh = current.expiresAt.isAfter(now)
      unknownKidRefreshAllowed = current.lastUnknownKidRefresh.forall(_.plusSeconds(UnknownKidRefreshInterval.toSeconds).isBefore(now))
      result <- current.keys.get(kid) match
        case Some(publicKey) if cacheFresh => IO.pure(Right(publicKey))
        case None if cacheFresh && unknownKidRefreshAllowed =>
          refreshKeys(now, lastUnknownKidRefresh = Some(now))
            .map(_.flatMap(_.get(kid).toRight(s"Keycloak JWKS did not contain key '$kid'")))
        case None if cacheFresh => IO.pure(Left(s"Keycloak JWKS did not contain key '$kid'"))
        case _ => refreshKeys(now).map(_.flatMap(_.get(kid).toRight(s"Keycloak JWKS did not contain key '$kid'")))
    yield result

  private def refreshKeys(
    now: Instant,
    lastUnknownKidRefresh: Option[Instant] = None
  ): IO[Either[String, Map[String, RSAPublicKey]]] =
    lastUnknownKidRefresh.traverse_(refreshedAt => cache.update(_.copy(lastUnknownKidRefresh = Some(refreshedAt)))) *>
      fetchJwks
        .flatMap(jwks => IO.fromEither(keysFromJwks(jwks).leftMap(new IllegalArgumentException(_))))
        .flatTap(keys =>
          cache.update(current =>
            KeyCache(keys, now.plusSeconds(CacheTtl.toSeconds), lastUnknownKidRefresh.orElse(current.lastUnknownKidRefresh))
          )
        )
        .attempt
        .map {
          case Right(keys) => Right(keys)
          case Left(error) => Left(s"failed to refresh Keycloak JWKS from $jwksUri: ${error.getMessage}")
        }

object KeycloakJwks:
  private val CacheTtl = 10.minutes
  private val UnknownKidRefreshInterval = 30.seconds
  private val JwksFetchTimeout = 3.seconds
  private val JwksRetryPolicy = RetryPolicy(maxAttempts = 3, baseDelay = 100.millis)

  final case class Jwks(keys: List[Jwk])
  final case class Jwk(kty: String, kid: String, n: String, e: String, use: Option[String], alg: Option[String])
  private final case class KeyCache(
    keys: Map[String, RSAPublicKey],
    expiresAt: Instant,
    lastUnknownKidRefresh: Option[Instant]
  )

  given Decoder[Jwk] = Decoder.forProduct6("kty", "kid", "n", "e", "use", "alg")(Jwk.apply)
  given Decoder[Jwks] = Decoder.forProduct1("keys")(Jwks.apply)

  def create(config: ServerConfig, client: Client[IO]): IO[KeycloakJwks] =
    for
      issuer <- IO.fromEither(configuredIssuer(config))
      jwksUri <- IO.fromEither(configuredJwksUri(config, issuer))
      cache <- Ref.of[IO, KeyCache](KeyCache(Map.empty, Instant.EPOCH, None))
    yield KeycloakJwks(
      issuer = issuer,
      jwksUri = jwksUri,
      clientId = config.keycloakClientId,
      audience = config.keycloakAudience,
      fetchJwks = resilientFetch(client.expect[Jwks](jwksUri)),
      cache = cache
    )

  def createForTest(
    issuer: String,
    jwksUri: Uri,
    clientId: Option[String],
    audience: Option[String],
    fetchJwks: IO[Jwks]
  ): IO[KeycloakJwks] =
    Ref.of[IO, KeyCache](KeyCache(Map.empty, Instant.EPOCH, None)).map { cache =>
      KeycloakJwks(
        issuer = normalizeIssuer(issuer),
        jwksUri = jwksUri,
        clientId = clientId,
        audience = audience,
        fetchJwks = resilientFetch(fetchJwks),
        cache = cache
      )
    }

  private def resilientFetch(fetchJwks: IO[Jwks]): IO[Jwks] =
    Retry.withBackoff[IO, Jwks](
      JwksRetryPolicy,
      {
        case NonFatal(_) => true
        case _ => false
      }
    )(fetchJwks.timeout(JwksFetchTimeout))

  private def configuredIssuer(config: ServerConfig): Either[Throwable, String] =
    config.keycloakIssuer
      .map(normalizeIssuer)
      .filter(_.nonEmpty)
      .toRight(new IllegalArgumentException("BEDROCK_KEYCLOAK_ISSUER must be set when Keycloak auth is enabled"))

  private def configuredJwksUri(config: ServerConfig, issuer: String): Either[Throwable, Uri] =
    val value = config.keycloakJwksUri.map(_.trim).filter(_.nonEmpty).getOrElse(s"$issuer/protocol/openid-connect/certs")
    Uri.fromString(value).leftMap(error => new IllegalArgumentException(error.message))

  private def normalizeIssuer(value: String): String =
    value.trim.replaceAll("/+$", "")

  private def decodeHeader(token: String): IO[Either[String, DecodedJWT]] =
    IO.delay(Right(JWT.decode(token))).recover { case NonFatal(error) => Left(error.getMessage) }

  private def keysFromJwks(jwks: Jwks): Either[String, Map[String, RSAPublicKey]] =
    jwks.keys
      .filter(jwk => jwk.kty.equalsIgnoreCase("RSA") && jwk.kid.nonEmpty)
      .traverse(jwk => publicKey(jwk).map(jwk.kid -> _))
      .map(_.toMap)

  private def publicKey(jwk: Jwk): Either[String, RSAPublicKey] =
    Either
      .catchNonFatal {
        val decoder = Base64.getUrlDecoder
        val modulus = new BigInteger(1, decoder.decode(jwk.n))
        val exponent = new BigInteger(1, decoder.decode(jwk.e))
        val spec = RSAPublicKeySpec(modulus, exponent)
        KeyFactory.getInstance("RSA").generatePublic(spec).asInstanceOf[RSAPublicKey]
      }
      .leftMap(error => s"invalid RSA key '${jwk.kid}': ${error.getMessage}")

  private def toClaims(decoded: DecodedJWT, clientId: Option[String]): Claims =
    val payload = payloadJson(decoded).getOrElse(Json.obj())
    Claims(
      subject = Option(decoded.getSubject).filter(_.nonEmpty).getOrElse("unknown"),
      expiresAt = Option(decoded.getExpiresAt).map(_.toInstant),
      role = roleFromPayload(payload, clientId)
    )

  private def payloadJson(decoded: DecodedJWT): Either[String, Json] =
    Either
      .catchNonFatal {
        val bytes = Base64.getUrlDecoder.decode(decoded.getPayload)
        new String(bytes, StandardCharsets.UTF_8)
      }
      .leftMap(_.getMessage)
      .flatMap(text => parse(text).leftMap(_.getMessage))

  private def roleFromPayload(payload: Json, clientId: Option[String]): String =
    val cursor = payload.hcursor
    val directRoles =
      List(
        cursor.get[String]("role").toOption,
        cursor.get[String]("https://bedrock/role").toOption
      ).flatten
    val topLevelRoles = cursor.get[List[String]]("roles").getOrElse(Nil)
    val scopes = cursor.get[String]("scope").getOrElse("").split("\\s+").toList.filter(_.nonEmpty)
    val realmRoles = cursor.downField("realm_access").get[List[String]]("roles").getOrElse(Nil)
    val resourceRoles = clientId.toList.flatMap { id =>
      cursor.downField("resource_access").downField(id).get[List[String]]("roles").getOrElse(Nil)
    }
    val normalized = (directRoles ++ topLevelRoles ++ scopes ++ realmRoles ++ resourceRoles).flatMap(UserRole.normalize)
    if normalized.contains(UserRole.Admin) then UserRole.Admin
    else if normalized.contains(UserRole.Operator) then UserRole.Operator
    else if normalized.contains(UserRole.Viewer) then UserRole.Viewer
    else UserRole.Viewer
