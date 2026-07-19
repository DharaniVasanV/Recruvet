package com.fakejobpostsystem.security;

import java.util.Map;

import org.springframework.security.oauth2.core.user.OAuth2User;

public final class OAuth2UserInfo {

    private OAuth2UserInfo() {
    }

    public static String getEmail(OAuth2User oAuth2User) {
        return stringAttribute(oAuth2User, "email");
    }

    public static String getGoogleId(OAuth2User oAuth2User) {
        String sub = stringAttribute(oAuth2User, "sub");
        return sub != null ? sub : stringAttribute(oAuth2User, "id");
    }

    public static String stringAttribute(OAuth2User oAuth2User, String key) {
        if (oAuth2User == null || key == null) {
            return null;
        }
        Map<String, Object> attributes = oAuth2User.getAttributes();
        if (attributes == null) {
            return null;
        }
        Object value = attributes.get(key);
        return value == null ? null : value.toString();
    }
}
