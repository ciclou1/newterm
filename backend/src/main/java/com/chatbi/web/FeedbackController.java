package com.chatbi.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chatbi.common.ChatBiException;
import com.chatbi.domain.entity.AppUser;
import com.chatbi.domain.entity.QueryAudit;
import com.chatbi.domain.entity.QueryFeedback;
import com.chatbi.mapper.QueryAuditMapper;
import com.chatbi.mapper.QueryFeedbackMapper;
import com.chatbi.service.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final CurrentUserService currentUserService;
    private final QueryAuditMapper auditMapper;
    private final QueryFeedbackMapper feedbackMapper;

    public FeedbackController(CurrentUserService currentUserService,
                              QueryAuditMapper auditMapper,
                              QueryFeedbackMapper feedbackMapper) {
        this.currentUserService = currentUserService;
        this.auditMapper = auditMapper;
        this.feedbackMapper = feedbackMapper;
    }

    @PostMapping
    public FeedbackResponse submit(@Valid @RequestBody FeedbackRequest request,
                                   @RequestHeader(value = "X-ChatBI-User", required = false) String username) {
        AppUser user = currentUserService.resolve(username);
        QueryAudit audit = auditMapper.selectOne(new LambdaQueryWrapper<QueryAudit>()
                .eq(QueryAudit::getId, request.auditId()).eq(QueryAudit::getUserId, user.getUsername()));
        if (audit == null) {
            throw ChatBiException.notFound("查询记录不存在或不属于当前用户");
        }
        QueryFeedback feedback = new QueryFeedback();
        feedback.setAuditId(request.auditId());
        feedback.setUserId(user.getUsername());
        feedback.setRating(request.rating());
        feedback.setComment(request.comment());
        feedback.setCreatedAt(OffsetDateTime.now());
        feedbackMapper.insert(feedback);
        return new FeedbackResponse(feedback.getId());
    }

    public record FeedbackRequest(@NotNull Long auditId,
                                  @Min(1) @Max(5) Integer rating,
                                  @Size(max = 500) String comment) {
    }

    public record FeedbackResponse(Long id) {
    }
}
