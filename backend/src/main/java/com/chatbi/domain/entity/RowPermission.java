package com.chatbi.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("row_permission")
public class RowPermission {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private String tableName;
    private String columnName;
    private String allowedValues;
    private String description;
    private Boolean enabled;
}
