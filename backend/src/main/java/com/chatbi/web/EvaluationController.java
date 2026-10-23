package com.chatbi.web;

import com.chatbi.domain.entity.AppUser;
import com.chatbi.eval.EvaluationService;
import com.chatbi.service.CurrentUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/eval")
public class EvaluationController {

    private final EvaluationService evaluationService;
    private final CurrentUserService currentUserService;

    public EvaluationController(EvaluationService evaluationService,
                                CurrentUserService currentUserService) {
        this.evaluationService = evaluationService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/questions")
    public EvaluationService.EvalDataset questions() {
        currentUserService.requireAdmin();
        return evaluationService.dataset();
    }

    @GetMapping("/jobs")
    public List<EvaluationService.EvalJobView> jobs() {
        currentUserService.requireAdmin();
        return evaluationService.recent();
    }

    @GetMapping("/jobs/{id}")
    public EvaluationService.EvalJobView job(@PathVariable String id) {
        currentUserService.requireAdmin();
        EvaluationService.EvalJobView view = evaluationService.job(id);
        if (view == null) {
            throw new IllegalArgumentException("评测任务不存在: " + id);
        }
        return view;
    }

    @PostMapping("/run")
    public EvaluationService.EvalJobView run(@RequestBody(required = false) RunRequest request) {
        AppUser user = currentUserService.requireAdminUser();
        RunRequest body = request == null ? new RunRequest(null, null) : request;
        // Never let the caller substitute a different (possibly restricted)
        // evaluation user: golden SQL is unfiltered, so the run must use the
        // authenticated admin.
        return evaluationService.start(body.category(), body.limit(), user.getUsername());
    }

    public record RunRequest(String category, Integer limit) {
    }
}
