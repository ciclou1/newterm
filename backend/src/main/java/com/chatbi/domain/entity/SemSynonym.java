package com.chatbi.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("sem_synonym")
public class SemSynonym {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String targetType;
    private String targetKey;
    private String synonym;
    private BigDecimal weight;
}
