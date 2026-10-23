package com.chatbi.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("sem_metric")
public class SemMetric {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String metricCode;
    private String metricName;
    private String description;
    private String expression;
    private String baseTable;
    private String timeColumn;
    private String unit;
    private String valueFormat;
    private String synonymsText;
    private Boolean enabled;
    private OffsetDateTime updatedAt;
}
