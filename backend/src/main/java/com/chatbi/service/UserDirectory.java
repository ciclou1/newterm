package com.chatbi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chatbi.domain.entity.AppUser;
import com.chatbi.mapper.AppUserMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserDirectory {

    private final AppUserMapper userMapper;

    public UserDirectory(AppUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public AppUser findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return userMapper.selectOne(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getUsername, username.trim())
                .last("LIMIT 1"));
    }

    public List<AppUser> enabledUsers() {
        return userMapper.selectList(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getEnabled, true)
                .orderByAsc(AppUser::getId));
    }
}
