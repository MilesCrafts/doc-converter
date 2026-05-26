package com.office.ai.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.office.ai.common.JwtUtil;
import com.office.ai.common.Result;
import com.office.ai.entity.User;
import com.office.ai.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
public class UserController {

    private final UserMapper userMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String DEFAULT_NICKNAME = "微信用户";
    private static final String DEFAULT_AVATAR = "https://mmbiz.qpic.cn/mmbiz/icTdbqWNOwNRna42FI242Lcia07jQodd2FJGIYQfG0LAJGFxM4FbnQP6yfMxBgJ0F3YRqJCJ1aPAK2dQagdusBZg/0";

    @Value("${WECHAT_APPID:}")
    private String appid;

    @Value("${WECHAT_SECRET:}")
    private String secret;

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> params, HttpServletRequest request) {
        String code = params.get("code");
        if (StrUtil.isBlank(code)) {
            return Result.error("PARAM_ERROR", "缺少 code 参数");
        }

        String openid;
        try {
            // 如果没有配置 appid/secret 或者 code 为 mock_code，使用模拟登录
            if (StrUtil.isBlank(appid) || StrUtil.isBlank(secret) || "mock_code".equals(code)) {
                String guestId = request == null ? null : request.getHeader("X-Guest-Id");
                if (StrUtil.isNotBlank(guestId)) {
                    openid = "mock_openid_" + guestId;
                } else {
                    openid = "mock_openid_" + code;
                }
            } else {
                String url = String.format("https://api.weixin.qq.com/sns/jscode2session?appid=%s&secret=%s&js_code=%s&grant_type=authorization_code", appid, secret, code);
                String response = HttpUtil.get(url);
                JsonNode jsonNode = objectMapper.readTree(response);
                if (jsonNode.has("errcode") && jsonNode.get("errcode").asInt() != 0) {
                    log.error("微信登录失败: {}", response);
                    return Result.error("LOGIN_FAILED", "微信登录失败");
                }
                openid = jsonNode.get("openid").asText();
            }

            String normalizedOpenid = "wx_" + openid;
            String nickName = params.get("nickName");
            String avatarUrl = params.get("avatarUrl");

            // 查询或创建用户
            User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                    .and(w -> w.eq(User::getOpenid, normalizedOpenid).or().eq(User::getOpenid, openid)));
            if (user == null) {
                user = new User();
                user.setOpenid(normalizedOpenid);
                user.setNickname(StrUtil.isNotBlank(nickName) ? nickName : DEFAULT_NICKNAME);
                user.setAvatar(StrUtil.isNotBlank(avatarUrl) ? avatarUrl : DEFAULT_AVATAR);
                userMapper.insert(user);
            } else {
                boolean shouldUpdate = false;
                if (StrUtil.isNotBlank(user.getOpenid()) && openid.equals(user.getOpenid())) {
                    user.setOpenid(normalizedOpenid);
                    shouldUpdate = true;
                }
                if (StrUtil.isNotBlank(nickName) && !nickName.equals(user.getNickname())) {
                    boolean isDefaultOverwrite = DEFAULT_NICKNAME.equals(nickName)
                            && StrUtil.isNotBlank(user.getNickname())
                            && !DEFAULT_NICKNAME.equals(user.getNickname());
                    if (!isDefaultOverwrite) {
                        user.setNickname(nickName);
                        shouldUpdate = true;
                    }
                }
                if (StrUtil.isNotBlank(avatarUrl) && !avatarUrl.equals(user.getAvatar())) {
                    boolean isDefaultOverwrite = DEFAULT_AVATAR.equals(avatarUrl)
                            && StrUtil.isNotBlank(user.getAvatar())
                            && !DEFAULT_AVATAR.equals(user.getAvatar());
                    if (!isDefaultOverwrite) {
                        user.setAvatar(avatarUrl);
                        shouldUpdate = true;
                    }
                }
                if (shouldUpdate) {
                    user.setUpdateTime(LocalDateTime.now());
                    userMapper.updateById(user);
                }
            }

            // 生成 token
            String token = JwtUtil.createToken(user.getId(), user.getOpenid());

            Map<String, Object> data = new HashMap<>();
            data.put("token", token);
            Map<String, Object> userInfo = new HashMap<>();
            userInfo.put("id", user.getId());
            userInfo.put("openId", user.getOpenid());
            userInfo.put("nickName", user.getNickname());
            userInfo.put("avatarUrl", user.getAvatar());
            data.put("userInfo", userInfo);

            return Result.success(data);

        } catch (Exception e) {
            log.error("登录异常", e);
            return Result.error("LOGIN_ERROR", "登录异常");
        }
    }

    @PostMapping("/profile")
    public Result<Map<String, Object>> updateProfile(@RequestBody Map<String, String> params, HttpServletRequest request) {
        Long userId = getUserIdFromRequest(request);
        if (userId == null) {
            return Result.error("UNAUTHORIZED", "未登录");
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            return Result.error("NOT_FOUND", "用户不存在");
        }

        String nickName = params.get("nickName");
        if (StrUtil.isBlank(nickName)) {
            nickName = params.get("nickname");
        }
        String avatarUrl = params.get("avatarUrl");
        if (StrUtil.isBlank(avatarUrl)) {
            avatarUrl = params.get("avatar");
        }

        boolean shouldUpdate = false;
        if (StrUtil.isNotBlank(nickName) && !nickName.equals(user.getNickname())) {
            user.setNickname(nickName);
            shouldUpdate = true;
        }
        if (StrUtil.isNotBlank(avatarUrl) && !avatarUrl.equals(user.getAvatar())) {
            user.setAvatar(avatarUrl);
            shouldUpdate = true;
        }
        if (shouldUpdate) {
            user.setUpdateTime(LocalDateTime.now());
            userMapper.updateById(user);
        }

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("id", user.getId());
        userInfo.put("openId", user.getOpenid());
        userInfo.put("nickName", user.getNickname());
        userInfo.put("avatarUrl", user.getAvatar());
        return Result.success(userInfo);
    }

    @GetMapping("/profile")
    public Result<Map<String, Object>> getProfile(HttpServletRequest request) {
        Long userId = getUserIdFromRequest(request);
        if (userId == null) {
            return Result.error("UNAUTHORIZED", "未登录");
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            return Result.error("NOT_FOUND", "用户不存在");
        }

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("id", user.getId());
        userInfo.put("openId", user.getOpenid());
        userInfo.put("nickName", user.getNickname());
        userInfo.put("avatarUrl", user.getAvatar());
        return Result.success(userInfo);
    }

    private Long getUserIdFromRequest(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (StrUtil.isNotBlank(authHeader) && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            return JwtUtil.getUserId(token);
        }
        return null;
    }
}
