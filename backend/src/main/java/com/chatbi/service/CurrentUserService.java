package com.chatbi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chatbi.common.ChatBiException;
import com.chatbi.config.ChatBiProperties;
import com.chatbi.domain.entity.AppUser;
import com.chatbi.mapper.AppUserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CurrentUserService {

    private final AppUserMapper userMapper;
    private final ChatBiProperties properties;

    public CurrentUserService(AppUserMapper userMapper, ChatBiProperties properties) {
        this.userMapper = userMapper;
        this.properties = properties;
    }

    public AppUser resolve(String headerUsername) {
        String username = headerUsername == null || headerUsername.isBlank()
                ? properties.getSecurity().getDefaultUser() : headerUsername.trim();
        AppUser user = userMapper.selectOne(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getUsername, username).last("LIMIT 1"));
        if (user == null) {
            throw new ChatBiException("USER_NOT_FOUND", "用户不存在：" + username, HttpStatus.UNAUTHORIZED);
        }
        if (Boolean.FALSE.equals(user.getEnabled())) {
            throw new ChatBiException("USER_DISABLED", "用户已禁用：" + username, HttpStatus.FORBIDDEN);
        }
        return user;
    }

    public void requireAdmin(AppUser user) {
        if (!Boolean.TRUE.equals(user.getIsAdmin())) {
            throw ChatBiException.forbidden("ADMIN_REQUIRED", "该操作需要平台管理员权限");
        }
    }

    public List<AppUser> all() {
        return userMapper.selectList(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getEnabled, true).orderByAsc(AppUser::getId));
    }
}
