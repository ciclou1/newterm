package com.chatbi.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("few_shot_example")
public class FewShotExample {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String question;
    private String sqlText;
    private String tablesUsed;
    private String tags;
    private Boolean enabled;
}
