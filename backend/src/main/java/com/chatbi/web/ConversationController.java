package com.chatbi.web;

import com.chatbi.domain.entity.AppUser;
import com.chatbi.domain.entity.Conversation;
import com.chatbi.service.ConversationService;
import com.chatbi.service.CurrentUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
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
    public List<Conversation> list(@RequestHeader(value = "X-ChatBI-User", required = false) String username) {
        return conversationService.list(currentUserService.resolve(username));
    }

    @GetMapping("/{id}")
    public ConversationService.ConversationDetail detail(
            @PathVariable UUID id,
            @RequestHeader(value = "X-ChatBI-User", required = false) String username) {
        AppUser user = currentUserService.resolve(username);
        return conversationService.detail(id, user);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @RequestHeader(value = "X-ChatBI-User", required = false) String username) {
        conversationService.delete(id, currentUserService.resolve(username));
        return ResponseEntity.noContent().build();
    }
}
