package com.chatbi.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sem_enum")
public class SemEnum {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tableName;
    private String columnName;
    private String enumValue;
    private String enumLabel;
    private String description;
}
