package com.chatbi.web;

import com.chatbi.domain.entity.AppUser;
import com.chatbi.domain.entity.Conversation;
import com.chatbi.service.ConversationService;
import com.chatbi.service.CurrentUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final CurrentUserService currentUserService;
    private final ConversationService conversationService;

    public ConversationController(CurrentUserService currentUserService,
                                  ConversationService conversationService) {
        this.currentUserService = currentUserService;
        this.conversationService = conversationService;
    }

    @GetMapping
    public List<Conversation> list() {
        return conversationService.list(currentUserService.getCurrent());
    }

    @GetMapping("/{id}")
    public ConversationService.ConversationDetail detail(@PathVariable UUID id) {
        AppUser user = currentUserService.getCurrent();
        return conversationService.detail(id, user);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        conversationService.delete(id, currentUserService.getCurrent());
        return ResponseEntity.noContent().build();
    }
}
