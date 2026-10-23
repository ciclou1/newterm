package com.chatbi.web;

import com.chatbi.common.RequestIdFilter;
import com.chatbi.domain.entity.AppUser;
import com.chatbi.domain.entity.Conversation;
import com.chatbi.metrics.ChatBiMetrics;
import com.chatbi.nl2sql.AgentResult;
import com.chatbi.nl2sql.Nl2SqlAgent;
import com.chatbi.presentation.QueryResponse;
import com.chatbi.presentation.ResultPresenter;
import com.chatbi.service.AuditService;
import com.chatbi.service.ConversationService;
import com.chatbi.service.CurrentUserService;
import com.chatbi.service.QuotaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final CurrentUserService currentUserService;
    private final ConversationService conversationService;
    private final Nl2SqlAgent agent;
    private final AuditService auditService;
    private final ResultPresenter presenter;
    private final ChatBiMetrics metrics;
    private final QuotaService quotaService;

    public ChatController(CurrentUserService currentUserService,
                          ConversationService conversationService,
                          Nl2SqlAgent agent,
                          AuditService auditService,
                          ResultPresenter presenter,
                          ChatBiMetrics metrics,
                          QuotaService quotaService) {
        this.currentUserService = currentUserService;
        this.conversationService = conversationService;
        this.agent = agent;
        this.auditService = auditService;
        this.presenter = presenter;
        this.metrics = metrics;
        this.quotaService = quotaService;
    }

    @PostMapping("/query")
    public QueryResponse query(@Valid @RequestBody ChatQueryRequest request,
                               HttpServletRequest servletRequest) {
        AppUser user = currentUserService.getCurrent();
        QuotaService.Reservation reservation = quotaService.reserve(user.getUsername());
        try {
            ConversationService.ConversationContext context =
                    conversationService.begin(request.conversationId(), user, request.question());
            AgentResult result = agent.ask(request.question(), user, context.history());
            String requestId = String.valueOf(servletRequest.getAttribute(RequestIdFilter.REQUEST_ID));
            Long auditId = auditService.record(requestId, context.conversation().getId(), user, result);
            QueryResponse response = presenter.present(result, requestId,
                    context.conversation().getId(), auditId);
            conversationService.complete(context.conversation(), response);
            metrics.record(result);
            return response;
        } finally {
            quotaService.release(reservation);
        }
    }

    public record ChatQueryRequest(@NotBlank(message = "问题不能为空")
                                   @Size(max = 500, message = "问题不能超过 500 字") String question,
                                   UUID conversationId) {
    }
}
