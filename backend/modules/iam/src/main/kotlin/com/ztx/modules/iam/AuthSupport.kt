package com.ztx.modules.iam

import io.vertx.core.json.JsonObject
import io.vertx.pgclient.data.Inet
import io.vertx.ext.web.RoutingContext
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** 平台与企业认证共用的密码、令牌和 HTTP 基础处理；业务策略仍由各自服务负责。 */
internal object AuthSupport {
    private val random = SecureRandom()

    fun hashPassword(password: String, iterations: Int = 310_000): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val hash = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return listOf(
            "pbkdf2",
            iterations.toString(),
            Base64.getEncoder().encodeToString(salt),
            Base64.getEncoder().encodeToString(hash)
        ).joinToString("$")
    }

    fun verifyPassword(
        password: String,
        encoded: String,
        allowedIterations: IntRange = 100_000..1_000_000
    ): Boolean = runCatching {
        val parts = encoded.split('$')
        require(parts.size == 4 && parts[0] == "pbkdf2")
        val iterations = parts[1].toInt()
        require(iterations in allowedIterations)
        val salt = Base64.getDecoder().decode(parts[2])
        val expected = Base64.getDecoder().decode(parts[3])
        require(expected.isNotEmpty() && expected.size <= 128)
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, expected.size * 8)
        val actual = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        MessageDigest.isEqual(actual, expected)
    }.getOrDefault(false)

    fun randomToken(bytes: Int = 32): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun postgresInet(ip: String?, invalidAsNull: Boolean = false): Inet? = ip?.let {
        val convert = { Inet().setAddress(InetAddress.getByName(it)) }
        if (invalidAsNull) runCatching(convert).getOrNull() else convert()
    }

    fun body(context: RoutingContext): JsonObject = context.body().asJsonObject() ?: JsonObject()

    fun ip(context: RoutingContext): String? =
        context.request().remoteAddress()?.host()?.takeIf { it.matches(Regex("[0-9a-fA-F:.]+")) }

    fun cookie(name: String, value: String, path: String, secure: Boolean): String =
        "$name=$value; Path=$path; Max-Age=28800; HttpOnly; SameSite=Strict" +
            if (secure) "; Secure" else ""

    fun clearCookie(name: String, path: String, secure: Boolean): String =
        "$name=; Path=$path; Max-Age=0; HttpOnly; SameSite=Strict" +
            if (secure) "; Secure" else ""
}
