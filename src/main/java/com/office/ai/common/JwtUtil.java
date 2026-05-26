package com.office.ai.common;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;

import java.util.Date;

public class JwtUtil {
    
    private static final String SECRET = "ai_lite_tool_user_secret_123456";
    private static final long EXPIRE = 604800000L; // 7 days

    public static String createToken(Long userId, String openid) {
        Algorithm algorithm = Algorithm.HMAC256(SECRET);
        return JWT.create()
                .withClaim("userId", userId)
                .withClaim("openid", openid)
                .withExpiresAt(new Date(System.currentTimeMillis() + EXPIRE))
                .sign(algorithm);
    }

    public static Long getUserId(String token) {
        try {
            DecodedJWT jwt = JWT.require(Algorithm.HMAC256(SECRET)).build().verify(token);
            return jwt.getClaim("userId").asLong();
        } catch (Exception e) {
            return null;
        }
    }
}