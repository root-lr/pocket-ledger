package io.pocketledger.identity

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * JWT signés en ES256. Clés fournies en PEM (Docker secrets en prod) ; à défaut, une paire éphémère
 * est générée au démarrage (dev uniquement : les jetons ne survivent pas à un redémarrage).
 * Rotation : publier la nouvelle clé dans le JWKS avant de signer avec.
 */
@Configuration
class JwtConfig(
    @Value("\${pocketledger.jwt.private-key:}") private val privatePem: String,
    @Value("\${pocketledger.jwt.public-key:}") private val publicPem: String,
    @Value("\${pocketledger.jwt.key-id:pocketledger-1}") private val keyId: String,
    @Value("\${pocketledger.jwt.issuer:https://api.pocketledger.dev}") private val issuer: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun signingKey(): ECKey {
        val (publicKey, privateKey) =
            if (privatePem.isNotBlank() && publicPem.isNotBlank()) {
                val factory = KeyFactory.getInstance("EC")
                (factory.generatePublic(X509EncodedKeySpec(pem(publicPem))) as ECPublicKey) to
                    (factory.generatePrivate(PKCS8EncodedKeySpec(pem(privatePem))) as ECPrivateKey)
            } else {
                log.warn("Aucune clé JWT configurée : paire ES256 éphémère générée (dev uniquement)")
                val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
                val pair = generator.generateKeyPair()
                (pair.public as ECPublicKey) to (pair.private as ECPrivateKey)
            }
        return ECKey
            .Builder(Curve.P_256, publicKey)
            .privateKey(privateKey)
            .keyID(keyId)
            .build()
    }

    @Bean
    fun jwtEncoder(signingKey: ECKey): JwtEncoder = NimbusJwtEncoder(ImmutableJWKSet(JWKSet(signingKey)))

    @Bean
    fun jwtDecoder(signingKey: ECKey): JwtDecoder {
        val processor = DefaultJWTProcessor<SecurityContext>()
        processor.jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.ES256, ImmutableJWKSet(JWKSet(signingKey.toPublicJWK())))
        return NimbusJwtDecoder(processor).apply { setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer)) }
    }

    private fun pem(value: String): ByteArray =
        Base64.getDecoder().decode(
            value
                .replace(Regex("-----(BEGIN|END) [A-Z ]+-----"), "")
                .replace(Regex("\\s"), ""),
        )
}

@RestController
class JwksController(private val signingKey: ECKey) {
    @GetMapping("/.well-known/jwks.json")
    fun jwks(): Map<String, Any> = JWKSet(signingKey.toPublicJWK()).toJSONObject()
}
