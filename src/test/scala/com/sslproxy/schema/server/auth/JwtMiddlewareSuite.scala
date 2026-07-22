package com.sslproxy.schema.server.auth

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.sslproxy.schema.config.ServerConfig
import org.http4s.*
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString
import munit.FunSuite

import java.nio.file.Paths
import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.time.Instant
import java.util.{Base64, Date}
import scala.jdk.CollectionConverters.*

class JwtMiddlewareSuite extends FunSuite:
  test("middleware rejects missing tokens and accepts signed bearer token for protected routes") {
    val config = serverConfig()
    val routes = JwtMiddleware(config)(HttpRoutes.of[IO] { case GET -> Root / "protected" => Ok("ok") }).orNotFound
    val token = JwtTokens.create(config.jwtSecret, "tester").map(_._1).unsafeRunSync()

    val missing = routes.run(Request[IO](Method.GET, Uri.unsafeFromString("/protected"))).unsafeRunSync()
    val bearer = routes
      .run(
        Request[IO](Method.GET, Uri.unsafeFromString("/protected"))
          .putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $token"))
      )
      .unsafeRunSync()
    val query =
      routes.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/protected?access_token=$token"))).unsafeRunSync()

    assertEquals(missing.status, Status.Unauthorized)
    assertEquals(bearer.status, Status.Ok)
    assertEquals(query.status, Status.Unauthorized)
  }

  test("middleware accepts configured static bearer token and rejects invalid static tokens") {
    val config = serverConfig(apiBearerToken = Some("static-token"))
    val routes = JwtMiddleware(config)(HttpRoutes.of[IO] { case GET -> Root / "protected" => Ok("ok") }).orNotFound

    val accepted = routes
      .run(
        Request[IO](Method.GET, Uri.unsafeFromString("/protected"))
          .putHeaders(Header.Raw(CIString("Authorization"), "Bearer static-token"))
      )
      .unsafeRunSync()
    val rejected = routes
      .run(
        Request[IO](Method.GET, Uri.unsafeFromString("/protected"))
          .putHeaders(Header.Raw(CIString("Authorization"), "Bearer wrong-token"))
      )
      .unsafeRunSync()

    assertEquals(accepted.status, Status.Ok)
    assertEquals(rejected.status, Status.Unauthorized)
  }

  test("middleware exposes signed roles and maps static token to admin") {
    val config = serverConfig(apiBearerToken = Some("static-token"))
    val routes = JwtMiddleware(config)(HttpRoutes.of[IO] { case request @ GET -> Root / "role" =>
      AuthContext.requireRole(request, UserRole.Viewer)(claims => Ok(claims.role))
    }).orNotFound
    val operatorToken = JwtTokens.create(config.jwtSecret, "tester", UserRole.Operator).map(_._1).unsafeRunSync()

    val operator = routes
      .run(
        Request[IO](Method.GET, Uri.unsafeFromString("/role"))
          .putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $operatorToken"))
      )
      .unsafeRunSync()
    val static = routes
      .run(
        Request[IO](Method.GET, Uri.unsafeFromString("/role"))
          .putHeaders(Header.Raw(CIString("Authorization"), "Bearer static-token"))
      )
      .unsafeRunSync()

    assertEquals(operator.as[String].unsafeRunSync(), UserRole.Operator)
    assertEquals(static.as[String].unsafeRunSync(), UserRole.Admin)
  }

  test("auth context fails closed when middleware did not attach claims") {
    val route = HttpRoutes
      .of[IO] { case request @ GET -> Root / "protected" =>
        AuthContext.requireRole(request, UserRole.Viewer)(_ => Ok("ok"))
      }
      .orNotFound

    val response = route.run(Request[IO](Method.GET, Uri.unsafeFromString("/protected"))).unsafeRunSync()

    assertEquals(response.status, Status.Unauthorized)
  }

  test("middleware rejects query token fallback on run stream routes") {
    val config = serverConfig()
    val routes = JwtMiddleware(config)(HttpRoutes.of[IO] { case GET -> Root / "api" / "runs" / _ / "stream" =>
      Ok("ok")
    }).orNotFound
    val token = JwtTokens.create(config.jwtSecret, "tester").map(_._1).unsafeRunSync()

    val stream = routes
      .run(Request[IO](Method.GET, Uri.unsafeFromString(s"/api/runs/run-1/stream?access_token=$token")))
      .unsafeRunSync()

    assertEquals(stream.status, Status.Unauthorized)
  }

  test("middleware only bypasses dev auth token route when enabled") {
    val route = HttpRoutes.of[IO] { case POST -> Root / "api" / "auth" / "token" => Ok("ok") }

    val disabled = JwtMiddleware(serverConfig(devAuthEnabled = false))(route).orNotFound
      .run(Request[IO](Method.POST, Uri.unsafeFromString("/api/auth/token")))
      .unsafeRunSync()
    val enabled = JwtMiddleware(serverConfig(devAuthEnabled = true))(route).orNotFound
      .run(Request[IO](Method.POST, Uri.unsafeFromString("/api/auth/token")))
      .unsafeRunSync()

    assertEquals(disabled.status, Status.Unauthorized)
    assertEquals(enabled.status, Status.Ok)
  }

  test("middleware accepts Keycloak RS256 tokens and extracts client roles") {
    val fixture = rsaFixture()
    val config = keycloakConfig()
    val verifier = keycloakVerifier(config, fixture)
    val routes = JwtMiddleware(config, Some(verifier))(HttpRoutes.of[IO] { case request @ GET -> Root / "role" =>
      AuthContext.requireRole(request, UserRole.Viewer)(claims => Ok(s"${claims.subject}:${claims.role}"))
    }).orNotFound
    val token = keycloakToken(fixture, subject = "keycloak-user", resourceRoles = List(UserRole.Admin))

    val response = routes
      .run(
        Request[IO](Method.GET, Uri.unsafeFromString("/role"))
          .putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $token"))
      )
      .unsafeRunSync()

    assertEquals(response.status, Status.Ok)
    assertEquals(response.as[String].unsafeRunSync(), "keycloak-user:admin")
  }

  test("middleware rejects Keycloak tokens with bad issuer, expiry, or signature") {
    val fixture = rsaFixture()
    val wrongKey = rsaFixture()
    val config = keycloakConfig()
    val verifier = keycloakVerifier(config, fixture)
    val routes =
      JwtMiddleware(config, Some(verifier))(HttpRoutes.of[IO] { case GET -> Root / "protected" => Ok("ok") }).orNotFound
    val badIssuer = keycloakToken(fixture, issuer = "https://keycloak.example.com/realms/other")
    val expired = keycloakToken(fixture, expiresAt = Instant.now.minusSeconds(60))
    val badSignature = keycloakToken(wrongKey.copy(kid = fixture.kid))

    List(badIssuer, expired, badSignature).foreach { token =>
      val response = routes
        .run(
          Request[IO](Method.GET, Uri.unsafeFromString("/protected"))
            .putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $token"))
        )
        .unsafeRunSync()
      assertEquals(response.status, Status.Unauthorized)
    }
  }

  test("Keycloak verifier uses client id as token scope when audience is not configured") {
    val fixture = rsaFixture()
    val config = keycloakConfig().copy(keycloakAudience = None, keycloakClientId = Some("bedrock-ui"))
    val verifier = keycloakVerifier(config, fixture)

    val accepted = verifier(keycloakToken(fixture)).unsafeRunSync()
    val broadRealmToken = keycloakToken(fixture, tokenAudience = "account", authorizedParty = "account")
    val rejected = verifier(broadRealmToken).unsafeRunSync()

    assert(accepted.isRight)
    assert(rejected.isLeft)
  }

  test("Keycloak verifier forces one bounded JWKS refresh for unknown kids") {
    val fixture = rsaFixture("known-key")
    val unknownKey = rsaFixture("unknown-key")
    val config = keycloakConfig()
    val fetches = Ref.of[IO, Int](0).unsafeRunSync()
    def jwksFor(keys: RsaFixture*) = KeycloakJwks.Jwks(
      keys.toList.map(key =>
        KeycloakJwks.Jwk(
          kty = "RSA",
          kid = key.kid,
          n = base64UrlUInt(key.publicKey.getModulus),
          e = base64UrlUInt(key.publicKey.getPublicExponent),
          use = Some("sig"),
          alg = Some("RS256")
        )
      )
    )
    val verifier = KeycloakJwks
      .createForTest(
        issuer = config.keycloakIssuer.getOrElse(""),
        jwksUri = Uri.unsafeFromString("https://keycloak.example.com/realms/bedrock/protocol/openid-connect/certs"),
        clientId = config.keycloakClientId,
        audience = config.keycloakAudience,
        fetchJwks = fetches.getAndUpdate(_ + 1).map {
          case 0 => jwksFor(fixture)
          case _ => jwksFor(fixture, unknownKey)
        }
      )
      .unsafeRunSync()

    val accepted = verifier.verify(keycloakToken(fixture)).unsafeRunSync()
    val beforeUnknownKid = fetches.get.unsafeRunSync()
    val acceptedAfterRefresh = verifier.verify(keycloakToken(unknownKey)).unsafeRunSync()
    val afterUnknownKid = fetches.get.unsafeRunSync()

    assert(accepted.isRight)
    assert(acceptedAfterRefresh.isRight)
    assertEquals(beforeUnknownKid, 1)
    assertEquals(afterUnknownKid, 2)
  }

  test("Keycloak verifier retries transient JWKS fetch failures") {
    val fixture = rsaFixture()
    val config = keycloakConfig()
    val fetches = Ref.of[IO, Int](0).unsafeRunSync()
    val jwks = KeycloakJwks.Jwks(
      List(
        KeycloakJwks.Jwk(
          kty = "RSA",
          kid = fixture.kid,
          n = base64UrlUInt(fixture.publicKey.getModulus),
          e = base64UrlUInt(fixture.publicKey.getPublicExponent),
          use = Some("sig"),
          alg = Some("RS256")
        )
      )
    )
    val verifier = KeycloakJwks
      .createForTest(
        issuer = config.keycloakIssuer.getOrElse(""),
        jwksUri = Uri.unsafeFromString("https://keycloak.example.com/realms/bedrock/protocol/openid-connect/certs"),
        clientId = config.keycloakClientId,
        audience = config.keycloakAudience,
        fetchJwks = fetches.getAndUpdate(_ + 1).flatMap {
          case 0 => IO.raiseError(RuntimeException("temporary JWKS failure"))
          case _ => IO.pure(jwks)
        }
      )
      .unsafeRunSync()

    val accepted = verifier.verify(keycloakToken(fixture)).unsafeRunSync()
    val fetchCount = fetches.get.unsafeRunSync()

    assert(accepted.isRight)
    assertEquals(fetchCount, 2)
  }

  private def serverConfig(apiBearerToken: Option[String] = None, devAuthEnabled: Boolean = false): ServerConfig =
    ServerConfig(
      host = "127.0.0.1",
      port = 8080,
      corsOrigins = Set("http://localhost:5173"),
      encryptKeyBase64 = None,
      jwtSecret = "jwt-secret",
      devAuthSecret = "dev",
      devAuthEnabled = devAuthEnabled,
      dbTestAllowedHosts = Set.empty,
      patchStageDir = Paths.get("."),
      apiBearerToken = apiBearerToken
    )

  private def keycloakConfig(): ServerConfig =
    serverConfig().copy(
      keycloakEnabled = true,
      keycloakIssuer = Some("https://keycloak.example.com/realms/bedrock"),
      keycloakClientId = Some("bedrock-ui"),
      keycloakAudience = Some("bedrock-ui")
    )

  private final case class RsaFixture(kid: String, publicKey: RSAPublicKey, privateKey: RSAPrivateKey)

  private def rsaFixture(kid: String = "test-key"): RsaFixture =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    val pair = generator.generateKeyPair()
    RsaFixture(kid, pair.getPublic.asInstanceOf[RSAPublicKey], pair.getPrivate.asInstanceOf[RSAPrivateKey])

  private def keycloakVerifier(config: ServerConfig, fixture: RsaFixture): JwtMiddleware.TokenVerifier =
    val jwks = KeycloakJwks.Jwks(
      List(
        KeycloakJwks.Jwk(
          kty = "RSA",
          kid = fixture.kid,
          n = base64UrlUInt(fixture.publicKey.getModulus),
          e = base64UrlUInt(fixture.publicKey.getPublicExponent),
          use = Some("sig"),
          alg = Some("RS256")
        )
      )
    )
    KeycloakJwks
      .createForTest(
        issuer = config.keycloakIssuer.getOrElse(""),
        jwksUri = Uri.unsafeFromString("https://keycloak.example.com/realms/bedrock/protocol/openid-connect/certs"),
        clientId = config.keycloakClientId,
        audience = config.keycloakAudience,
        fetchJwks = IO.pure(jwks)
      )
      .map(_.verify)
      .unsafeRunSync()

  private def keycloakToken(
    fixture: RsaFixture,
    subject: String = "keycloak-user",
    issuer: String = "https://keycloak.example.com/realms/bedrock",
    realmRoles: List[String] = List(UserRole.Viewer),
    resourceRoles: List[String] = List(UserRole.Operator),
    expiresAt: Instant = Instant.now.plusSeconds(300),
    tokenAudience: String = "bedrock-ui",
    authorizedParty: String = "bedrock-ui"
  ): String =
    val realmAccess = Map("roles" -> realmRoles.asJava).asJava
    val resourceAccess = Map(
      "bedrock-ui" -> Map("roles" -> resourceRoles.asJava).asJava
    ).asJava
    JWT
      .create()
      .withKeyId(fixture.kid)
      .withIssuer(issuer)
      .withSubject(subject)
      .withAudience(tokenAudience)
      .withClaim("azp", authorizedParty)
      .withClaim("realm_access", realmAccess)
      .withClaim("resource_access", resourceAccess)
      .withExpiresAt(Date.from(expiresAt))
      .sign(Algorithm.RSA256(null, fixture.privateKey))

  private def base64UrlUInt(value: java.math.BigInteger): String =
    val bytes = value.toByteArray.dropWhile(_ == 0.toByte)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
