package com.chatbi.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("sem_table")
public class SemTable {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tableName;
    private String businessName;
    private String domain;
    private String description;
    private Boolean enabled;
    private OffsetDateTime updatedAt;
}
