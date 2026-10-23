package com.chatbi.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sem_column")
public class SemColumn {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tableId;
    private String columnName;
    private String businessName;
    private String dataType;
    private String description;
    private String unit;
    private Boolean isDimension;
    private Boolean isMeasure;
    private Boolean sensitive;
    private Integer ordinal;
}
