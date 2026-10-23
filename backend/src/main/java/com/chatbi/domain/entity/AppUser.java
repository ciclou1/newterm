package com.chatbi.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("app_user")
public class AppUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String displayName;
    private String role;
    private Long departmentId;
    private Boolean isAdmin;
    private Boolean enabled;
    /** BCrypt hash. Null means password login is disabled for this account. */
    private String passwordHash;
    /** Comma-separated application roles: ADMIN, MANAGER, VIEWER, SERVICE. */
    private String roles;
    private OffsetDateTime lastLoginAt;
}
